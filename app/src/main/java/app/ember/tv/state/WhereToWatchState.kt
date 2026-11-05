package app.ember.tv.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ember.tv.data.AppEntry
import app.ember.tv.data.Prefs
import app.ember.tv.streaming.Offer
import app.ember.tv.streaming.ServiceApps
import app.ember.tv.streaming.StreamingService
import app.ember.tv.streaming.TmdbWatch
import app.ember.tv.streaming.Watchmode
import app.ember.tv.streaming.StreamingAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Streaming services: "Where to watch" from Watchmode, TMDB or Streaming Availability. Split out of
 * HomeViewModel. [apps] are the installed apps; [onProviderChanged] clears offers already shown.
 */
class WhereToWatchState(
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val apps: () -> List<AppEntry>,
    private val onProviderChanged: () -> Unit,
) {
    internal var clock: () -> Long = System::currentTimeMillis
    internal var providerReady: (String) -> Boolean = { provider -> when (provider) {
        "watchmode" -> Watchmode.available; "tmdb" -> TmdbWatch.available
        "streaming" -> StreamingAvailability.available; else -> false
    } }
    internal var serviceLoader: suspend (String, String) -> List<StreamingService> = { provider, region -> when (provider) {
        "tmdb" -> TmdbWatch.services(region); "streaming" -> StreamingAvailability.services(region)
        else -> Watchmode.services(region)
    } }
    private val serviceJson = Json { ignoreUnknownKeys = true }
    private var servicesJob: Job? = null
    private var servicesRevision = 0L
    var streamingServices by mutableStateOf<List<StreamingService>>(emptyList()); private set
    var watchRegion by mutableStateOf(prefs.watchRegion); private set
    var watchProvider by mutableStateOf(prefs.watchProvider); private set
    var servicesOn by mutableStateOf(prefs.servicesOn); private set
    var servicesOff by mutableStateOf(prefs.servicesOff); private set
    var servicesMessage by mutableStateOf<String?>(null); private set
    var servicesLoading by mutableStateOf(false); private set

    /** True when a source is chosen and has its key. Off means no Where to watch anywhere. */
    val whereToWatchReady: Boolean
        get() = providerReady(watchProvider)

    /** Kept for older callers: Where to watch is usable. */
    val watchmodeAvailable: Boolean get() = whereToWatchReady

    fun updateWatchProvider(p: String) {
        if (p !in setOf("off", "tmdb", "watchmode", "streaming")) return
        if (p == watchProvider) return
        watchProvider = p
        prefs.watchProvider = p
        servicesMessage = null
        streamingServices = emptyList()
        onProviderChanged()
        refreshStreamingServices(force = true)
    }

    /** Service ids are per source, so switching sources does not mix up on/off choices. */
    private fun svcKey(service: StreamingService) = when (watchProvider) {
        "tmdb" -> "tmdb:${service.id}"
        "streaming" -> "streaming:${service.externalId ?: service.id}"
        else -> service.id.toString()
    }

    /** The installed TV app for a service, if any. */
    fun installedPackage(s: StreamingService): String? = ServiceApps.installedPackage(s, apps())

    /** Installed apps count as on unless switched off; others are off unless switched on. */
    fun isServiceOn(s: StreamingService): Boolean {
        val id = svcKey(s)
        return id in servicesOn || (installedPackage(s) != null && id !in servicesOff)
    }

    fun toggleService(s: StreamingService) {
        val id = svcKey(s)
        val installed = installedPackage(s) != null
        if (isServiceOn(s)) {
            servicesOn = servicesOn - id
            if (installed) servicesOff = servicesOff + id
        } else {
            servicesOff = servicesOff - id
            if (!installed) servicesOn = servicesOn + id
        }
        prefs.servicesOn = servicesOn
        prefs.servicesOff = servicesOff
    }

    /** Services shown in Settings: subscription, free and TV-provider ones, installed first. */
    val listedServices: List<StreamingService>
        get() = streamingServices.filter { it.type in setOf("sub", "free", "tve", "addon") }
            .sortedWith(compareByDescending<StreamingService> { installedPackage(it) != null }.thenBy { it.name.lowercase() })

    /** Loads the region's service list from the saved copy, and from the source at most once a week. */
    fun refreshStreamingServices(force: Boolean = false) {
        val revision = ++servicesRevision
        servicesJob?.cancel()
        servicesLoading = false
        if (!whereToWatchReady) {
            streamingServices = emptyList()
            return
        }
        val source = "$watchProvider:$watchRegion"
        val saved = prefs.servicesJson?.takeIf { prefs.servicesSource == source }
            ?.let { runCatching { serviceJson.decodeFromString<List<StreamingService>>(it) }.getOrNull() }
        if (saved != null) streamingServices = saved
        val recent = clock() - prefs.servicesFetchedAt in 0 until 7 * 24 * 60 * 60 * 1000L
        if (!force && saved != null && recent) return
        val provider = watchProvider
        val region = watchRegion
        servicesLoading = true
        servicesJob = scope.launch {
            try {
                    val list = serviceLoader(provider, region)
                    ensureActive()
                    if (revision == servicesRevision && provider == watchProvider && region == watchRegion) {
                        streamingServices = list
                        servicesMessage = null
                        prefs.servicesJson = serviceJson.encodeToString(list)
                        prefs.servicesSource = source
                        prefs.servicesFetchedAt = clock()
                    }
            } catch (e: Exception) {
                ensureActive()
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                if (revision == servicesRevision && provider == watchProvider && region == watchRegion)
                    servicesMessage = "Could not load streaming services from " +
                        (when (provider) { "tmdb" -> "TMDB"; "streaming" -> "Streaming Availability"; else -> "Watchmode" }) + ". Check the API key and provider plan."
            } finally {
                if (revision == servicesRevision) servicesLoading = false
            }
        }
    }

    internal suspend fun awaitRefresh() { servicesJob?.join() }

    fun updateWatchRegion(code: String) {
        val c = code.trim().uppercase(java.util.Locale.ROOT)
        if (!c.matches(Regex("[A-Z]{2}"))) {
            servicesMessage = "Enter a two-letter country code, such as US or CA."
            return
        }
        watchRegion = c
        prefs.watchRegion = c
        streamingServices = emptyList()
        onProviderChanged()
        servicesMessage = "Region set to $c."
        refreshStreamingServices(force = true)
    }

    fun serviceFor(o: Offer): StreamingService? = streamingServices.firstOrNull {
        if (watchProvider == "streaming") o.externalId != null && it.externalId == o.externalId else it.id == o.serviceId
    }

    /** Shown under Where to watch when its service is on. Rent and buy stores count once switched on. */
    fun offerEnabled(o: Offer): Boolean = serviceFor(o)?.let { isServiceOn(it) }
        // Missing regional entries still obey explicit on/off choices, including installed apps.
        ?: (watchProvider in setOf("tmdb", "streaming") && isServiceOn(StreamingService(o.serviceId, o.name, o.type, externalId = o.externalId)))

    /** Installed app package and store package for an offer's service. */
    fun offerPackages(o: Offer): Pair<String?, String?> {
        val s = serviceFor(o) ?: StreamingService(o.serviceId, o.name, o.type, externalId = o.externalId)
        return installedPackage(s) to ServiceApps.storePackage(s)
    }

    /** Credit line for the title screen, when the source asks for one. */
    val offersCredit: String? get() = when (watchProvider) {
        "tmdb" -> "Data from JustWatch, via TMDB"
        "streaming" -> "Data from Streaming Availability"
        else -> null
    }

    /** An API key was saved or removed on the TV. */
    fun keyChanged(id: String) {
        val selected = when (watchProvider) {
            "tmdb" -> "TMDB_API_KEY"; "watchmode" -> "WATCHMODE_API_KEY"
            "streaming" -> "STREAMING_AVAILABILITY_API_KEY"; else -> null
        }
        if (id == selected) {
            onProviderChanged()
            servicesMessage = null
            prefs.servicesFetchedAt = 0L
            refreshStreamingServices(force = true)
        }
    }
}
