package app.ember.tv.weather

import app.ember.tv.data.Net
import app.ember.tv.data.boundedText
import app.ember.tv.data.withResponse
import app.ember.tv.tracker.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.Request
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

@Serializable internal data class SavedForecast(val place:Place,val fahrenheit:Boolean,val at:Long,val weather:WeatherNow) {
    fun usable(place:Place?,unit:Boolean,now:Long)=this.place==place && unit==fahrenheit && now-at in 0..7*86_400_000L
}
internal object WeatherProviders {
    val choices=listOf("openmeteo" to "Open-Meteo","met" to "MET Norway","wttr" to "wttr.in")
    fun order(primary:String,fallback:Boolean)=listOf(primary.takeIf {id->choices.any {it.first==id}} ?: "openmeteo")
        .let {if(fallback)(it+choices.map {c->c.first}).distinct() else it}
    private suspend fun load(source:String,place:Place,fahrenheit:Boolean):WeatherNow=when(source) {
        "met"->met(read("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=${coordinate(place.lat)}&lon=${coordinate(place.lon)}"),fahrenheit)
        "wttr"->wttr(read("https://wttr.in/${coordinate(place.lat)},${coordinate(place.lon)}?format=j1"),fahrenheit)
        else->Weather.now(place,fahrenheit)
    }
    /** MET Norway blocks requests with more than four decimals (geocoded places have five or more).
     * Fixed notation also avoids Double.toString's exponent form near zero, e.g. 1.0E-4. */
    internal fun coordinate(value:Double):String=java.math.BigDecimal.valueOf(value).setScale(4,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    suspend fun forecast(place:Place,fahrenheit:Boolean,primary:String,fallback:Boolean,
        loader:suspend (String,Place,Boolean)->WeatherNow=::load,timeoutMillis:Long=8_000):WeatherNow {
        require(place.lat.isFinite() && place.lon.isFinite() && place.lat in -90.0..90.0 && place.lon in -180.0..180.0)
        var error:Exception?=null
        for(source in order(primary,fallback))try {return withTimeout(timeoutMillis){loader(source,place,fahrenheit)}}
        catch(e:Exception){if(e is CancellationException && e !is TimeoutCancellationException)throw e;currentCoroutineContext().ensureActive();error=e}
        throw error ?: IllegalStateException("No weather source")
    }
    private suspend fun read(url:String):JsonElement=coroutineScope {
        val request=Request.Builder().url(url).header("User-Agent","EmberLauncher/1.0 https://github.com/PrimeN/Ember-Launcher").build()
        val text=withResponse(Net.client(4,6).newCall(request)){response->withContext(Dispatchers.IO) {
            check(response.isSuccessful){"Weather source unavailable"};response.body.boundedText(1_000_000)}}
        withContext(Dispatchers.Default){Json.parseToJsonElement(text)}
    }
    internal fun metCode(symbol:String?):Int=when(symbol?.substringBefore('_')) {
        "clearsky"->0;"fair","partlycloudy"->2;"cloudy"->3;"fog"->45
        "lightrain","lightrainshowers"->61;"rain","rainshowers","heavyrain","heavyrainshowers"->63
        "sleet","lightsleet","heavysleet","sleetshowers","lightsleetshowers","heavysleetshowers"->71
        "snow","lightsnow","heavysnow","snowshowers","lightsnowshowers","heavysnowshowers"->73
        else->if(symbol?.contains("thunder")==true)95 else -1
    }
    internal fun met(root:JsonElement,f:Boolean,now:Long=System.currentTimeMillis(),zone:ZoneId=ZoneId.systemDefault()):WeatherNow {
        data class Point(val at:Long,val date:String,val hour:Int,val temp:Double,val code:Int)
        val points=root.obj("properties").arr("timeseries").list().take(300).mapNotNull {row->runCatching {
            val instant=Instant.parse(row.str("time"));val local=instant.atZone(zone);val data=row.obj("data")
            val temp=data.obj("instant").obj("details").dbl("air_temperature")?.takeIf(Double::isFinite) ?: return@runCatching null
            val sym=data.obj("next_1_hours").obj("summary").str("symbol_code") ?: data.obj("next_6_hours").obj("summary").str("symbol_code")
            Point(instant.toEpochMilli(),local.toLocalDate().toString(),local.hour,temp,metCode(sym))
        }.getOrNull()}
        val current=points.minByOrNull {kotlin.math.abs(it.at-now)}?.takeIf {kotlin.math.abs(it.at-now)<6*3_600_000L} ?: error("No current weather")
        fun temperature(c:Double)=(if(f)c*9/5+32 else c).roundToInt()
        val today=Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
        val days=points.filter {it.date>=today}.groupBy {it.date}.entries.sortedBy {it.key}.take(5).map {(date,p)->
            WeatherDay(date,p.minBy {kotlin.math.abs(it.hour-12)}.code,temperature(p.maxOf {it.temp}),temperature(p.minOf {it.temp}))}
        return WeatherNow(temperature(current.temp),current.code,days.firstOrNull()?.high,days.firstOrNull()?.low,if(f)"F" else "C",days,"MET Norway")
    }
    internal fun wttrCode(code:Int)=when(code){113->0;116->2;119,122->3;143,248,260->45;263,266,281,284->51
        293,296,299,302,305,308,353,356,359->63;179,182,185,227,230,317,320,323,326,329,332,335,338,350,362,365,368,371,374,377->73
        200,386,389,392,395->95;else->-1}
    internal fun wttr(root:JsonElement,f:Boolean):WeatherNow {
        val current=root.arr("current_condition").list().firstOrNull() ?: error("No current weather")
        fun number(row:JsonElement,key:String)=row.dbl(key)?.takeIf(Double::isFinite)?.roundToInt()
        val days=root.arr("weather").list().take(5).mapNotNull {row->val date=row.str("date")?.takeIf {it.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))} ?: return@mapNotNull null
            val noon=row.arr("hourly").list().minByOrNull {kotlin.math.abs((number(it,"time") ?: 1200)-1200)}
            WeatherDay(date,wttrCode(noon?.let {number(it,"weatherCode")} ?: -1),number(row,if(f)"maxtempF" else "maxtempC"),number(row,if(f)"mintempF" else "mintempC"))}
        return WeatherNow(number(current,if(f)"temp_F" else "temp_C") ?: error("No temperature"),wttrCode(number(current,"weatherCode") ?: -1),
            days.firstOrNull()?.high,days.firstOrNull()?.low,if(f)"F" else "C",days,"wttr.in")
    }
}
