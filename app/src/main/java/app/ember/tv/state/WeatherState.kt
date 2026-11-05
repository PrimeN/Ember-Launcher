package app.ember.tv.state

import app.ember.tv.screensaver.ScreensaverFeed

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ember.tv.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import app.ember.tv.weather.Place
import app.ember.tv.weather.Weather
import app.ember.tv.weather.WeatherNow
import app.ember.tv.weather.WeatherProviders
import app.ember.tv.weather.SavedForecast
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/** The weather in the menu and on the screensaver (Settings > Weather). Split out of HomeViewModel. */
class WeatherState(
    private val app: Application,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val feed: ScreensaverFeed,
) {
    var weatherText by mutableStateOf<String?>(null); private set
    var details by mutableStateOf<WeatherNow?>(null); private set
    var weatherPlaceName by mutableStateOf(Place.decode(prefs.weatherPlace)?.name); private set
    var fahrenheit by mutableStateOf(prefs.fahrenheit); private set
    var weatherMessage by mutableStateOf<String?>(null); private set
    private var weatherLoadedAt = 0L
    private var loadedPlace: Place? = null
    private var loadedUnit: Boolean? = null
    private var forecastJob: Job? = null
    private var locationJob: Job? = null
    var provider by mutableStateOf(prefs.weatherProvider);private set
    var fallback by mutableStateOf(prefs.weatherFallback);private set
    var updatedAt by mutableLongStateOf(0L);private set
    private val cacheJson=Json {ignoreUnknownKeys=true}
    init {runCatching {prefs.weatherCache?.let {cacheJson.decodeFromString<SavedForecast>(it)}}.getOrNull()
        ?.takeIf {it.usable(Place.decode(prefs.weatherPlace),fahrenheit,System.currentTimeMillis())}?.let {
            details=it.weather;weatherText=it.weather.short;updatedAt=it.at;feed.weather="${it.weather.short} · ${it.place.name.substringBefore(',')}"
        }}
    fun updateProvider(id:String){if(WeatherProviders.choices.none {it.first==id})return;provider=id;prefs.weatherProvider=id;refreshWeather(true)}
    fun updateFallback(on:Boolean){fallback=on;prefs.weatherFallback=on;refreshWeather(true)}
    internal var forecast: suspend (Place, Boolean) -> WeatherNow = {p,f->WeatherProviders.forecast(p,f,provider,fallback)}
    internal var nowMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() }
    internal var geocode: suspend (String) -> Place? = Weather::geocode
    internal var approximate: suspend () -> Place? = Weather::approximate

    /** Reloads the forecast at most every 30 minutes unless forced. */
    fun refreshWeather(force: Boolean = false) {
        val place = Place.decode(prefs.weatherPlace)
        if (place == null) {
            forecastJob?.cancel()
            weatherText = null
            details = null
            feed.weather = null
            return
        }
        val now = nowMillis()
        val unit = fahrenheit
        if (!force && loadedPlace == place && loadedUnit == unit &&
            (forecastJob?.isActive == true || weatherLoadedAt > 0 && now - weatherLoadedAt in 0L until 30 * 60 * 1000L)) return
        forecastJob?.cancel()
        if (details?.unit!=(if(unit)"F" else "C") || loadedPlace!=null && loadedPlace!=place) {details=null;weatherText=null;feed.weather=null}
        loadedPlace = place; loadedUnit = unit
        weatherLoadedAt = 0L
        forecastJob = scope.launch {
            val result = runCatching { forecast(place, unit) }
            ensureActive()
            if (Place.decode(prefs.weatherPlace) != place || fahrenheit != unit) return@launch
            result.onFailure {weatherMessage=if(details!=null)"Showing the last saved forecast." else "Weather is temporarily unavailable."}
            result.onSuccess {
                weatherLoadedAt = now
                weatherText = it.short
                details = it
                updatedAt=System.currentTimeMillis();weatherMessage=null
                prefs.weatherCache=cacheJson.encodeToString(SavedForecast(place,unit,updatedAt,it))
                feed.weather = "${it.short} · ${place.name.substringBefore(',')}"
            }
        }
    }

    internal suspend fun awaitRefresh() { forecastJob?.join() }

    fun setWeatherCity(name: String) {
        if (name.isBlank()) return
        locationJob?.cancel()
        weatherMessage = "Looking up $name..."
        locationJob = scope.launch {
            val p = runCatching { geocode(name.trim()) }.getOrNull()
            ensureActive()
            if (p == null) weatherMessage = "Could not find that place. Try adding the country." else savePlace(p)
        }
    }

    /** Uses this network's public address to guess the city. Only runs when asked. */
    fun useApproximateLocation() {
        locationJob?.cancel()
        weatherMessage = "Finding your area..."
        locationJob = scope.launch {
            val p = runCatching { approximate() }.getOrNull()
            ensureActive()
            if (p == null) weatherMessage = "Could not work out your area. Enter a city instead." else savePlace(p)
        }
    }

    private fun savePlace(p: Place) {
        weatherLoadedAt = 0L
        weatherText = null
        details = null
        feed.weather = null
        prefs.weatherPlace = p.encode()
        weatherPlaceName = p.name
        weatherMessage = "Showing weather for ${p.name}."
        refreshWeather(force = true)
    }

    fun weatherOff() {
        locationJob?.cancel(); forecastJob?.cancel()
        weatherLoadedAt = 0L; loadedPlace = null; loadedUnit = null
        prefs.weatherCache=null;updatedAt=0
        prefs.weatherPlace = null
        weatherPlaceName = null
        weatherText = null
        details = null
        weatherMessage = null
        feed.weather = null
    }

    fun updateFahrenheit(on: Boolean) {
        fahrenheit = on
        prefs.fahrenheit = on
        refreshWeather(force = true)
    }
}
