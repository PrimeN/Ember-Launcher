package app.ember.tv.weather

import app.ember.tv.tracker.Http
import app.ember.tv.tracker.arr
import app.ember.tv.tracker.dbl
import app.ember.tv.tracker.int
import app.ember.tv.tracker.list
import app.ember.tv.tracker.obj
import app.ember.tv.tracker.str
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.net.URLEncoder
import kotlin.math.roundToInt

@kotlinx.serialization.Serializable
data class Place(val name: String, val lat: Double, val lon: Double) {
    fun encode() = "$name|$lat|$lon"

    companion object {
        fun decode(s: String?): Place? {
            val p = s?.split('|') ?: return null
            if (p.size != 3) return null
            return Place(p[0], p[1].toDoubleOrNull() ?: return null, p[2].toDoubleOrNull() ?: return null)
        }
    }
}

@kotlinx.serialization.Serializable
data class WeatherDay(val date: String, val code: Int, val high: Int?, val low: Int?)
@kotlinx.serialization.Serializable
data class WeatherNow(val temp: Int, val code: Int, val high: Int?, val low: Int?, val unit: String,
    val days: List<WeatherDay> = emptyList(), val source:String="Open-Meteo") {
    /** "12° Partly cloudy", for the top bar and the screensaver. */
    val short: String get() = "$temp°$unit ${Weather.describe(code)}".trim()
}

/**
 * Open-Meteo for forecasts and place search (no key needed).
 * The network-address lookup is only used when you ask for it in Settings.
 */
object Weather {
    suspend fun geocode(name: String): Place? {
        val q = URLEncoder.encode(name.trim(), "UTF-8")
        val r = Http.get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1")
        val f = r.arr("results").list().firstOrNull() ?: return null
        val lat = f.dbl("latitude") ?: return null
        val lon = f.dbl("longitude") ?: return null
        val label = listOfNotNull(f.str("name"), f.str("admin1"), f.str("country_code")).joinToString(", ")
        return Place(label, lat, lon)
    }

    /** Rough location from this network's public address. City-level at best. */
    suspend fun approximate(): Place? = runCatching {
        val r = Http.get("https://ipapi.co/json/")
        val lat = r.dbl("latitude") ?: return@runCatching null
        val lon = r.dbl("longitude") ?: return@runCatching null
        Place(listOfNotNull(r.str("city"), r.str("region_code"), r.str("country_code")).joinToString(", "), lat, lon)
    }.getOrNull()

    suspend fun now(p: Place, fahrenheit: Boolean): WeatherNow {
        val unitParam = if (fahrenheit) "&temperature_unit=fahrenheit" else ""
        val r = Http.get(
            "https://api.open-meteo.com/v1/forecast?latitude=${p.lat}&longitude=${p.lon}" +
                "&current=temperature_2m,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                "&forecast_days=5&timezone=auto$unitParam"
        )
        return parse(r, fahrenheit)
    }

    internal fun parse(r: kotlinx.serialization.json.JsonElement, fahrenheit: Boolean): WeatherNow {
        val current = r.obj("current")
        val daily = r.obj("daily")
        fun first(key: String) = (daily.arr(key).list().firstOrNull() as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite)?.roundToInt()
        return WeatherNow(
            temp = current.dbl("temperature_2m")?.takeIf(Double::isFinite)?.roundToInt() ?: error("No temperature"),
            code = current.int("weather_code") ?: -1,
            high = first("temperature_2m_max"),
            low = first("temperature_2m_min"),
            unit = if (fahrenheit) "F" else "C",
            days = daily.arr("time").list().take(5).mapIndexedNotNull { index, date ->
                val day = (date as? JsonPrimitive)?.content?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } ?: return@mapIndexedNotNull null
                fun number(key: String) = (daily.arr(key).list().getOrNull(index) as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite)?.roundToInt()
                WeatherDay(day, number("weather_code") ?: -1, number("temperature_2m_max"), number("temperature_2m_min"))
            },
        )
    }

    /** WMO weather codes, as Open-Meteo returns them. */
    fun describe(code: Int) = when (code) {
        0 -> "Clear"
        1, 2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 65, 66, 67, 80, 81, 82 -> "Rain"
        71, 73, 75, 77, 85, 86 -> "Snow"
        95, 96, 99 -> "Thunderstorm"
        else -> ""
    }
}
