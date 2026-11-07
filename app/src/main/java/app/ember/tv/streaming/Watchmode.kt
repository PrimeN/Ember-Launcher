package app.ember.tv.streaming

import app.ember.tv.data.ApiKeys
import app.ember.tv.data.AppEntry
import app.ember.tv.tracker.Http
import app.ember.tv.tracker.dbl
import app.ember.tv.tracker.int
import app.ember.tv.tracker.str
import kotlinx.serialization.Serializable
import kotlinx.coroutines.ensureActive

/** A streaming service from Watchmode's list for your region. */
@Serializable
data class StreamingService(
    val id: Int,
    val name: String,
    /** sub, free, tve (with a TV provider), rent, buy, purchase */
    val type: String,
    val logo: String? = null,
    /** Package of the service's phone app, from its Play Store link. */
    val playStorePackage: String? = null,
    /** String id used by Streaming Availability; never mixed with other providers' ids. */
    val externalId: String? = null,
)

/** One way to watch a title: a service, how (subscription, free, rent, buy) and where to open it. */
data class Offer(
    val serviceId: Int,
    val name: String,
    val type: String,
    val price: Double?,
    val format: String?,
    val webUrl: String?,
    val androidUrl: String?,
    val externalId: String? = null,
    val currency: String? = null,
    val expiresAt: Long? = null,
) {
    private fun cost(): String? = price?.takeIf { it.isFinite() && it >= 0 }?.let { amount ->
        if (currency == null) "$" + "%.2f".format(amount) else runCatching {
            java.text.NumberFormat.getCurrencyInstance().apply {
                currency = java.util.Currency.getInstance(this@Offer.currency)
            }.format(amount)
        }.getOrElse { "${this.currency} " + "%.2f".format(amount) }
    }
    val label: String
        get() = when (type) {
            "sub" -> "Subscription"
            "free" -> "Free"
            "tve" -> "With TV provider"
            "addon" -> "Channel add-on"
            "link" -> "From your addons"
            "rent" -> cost()?.let { "Rent $it" } ?: "Rent"
            "buy" -> cost()?.let { "Buy $it" } ?: "Buy"
            else -> type.replaceFirstChar { it.uppercase() }
        }

    /** Watchmode's format (SD, HD, 4K) as a quality label. Watchmode's HD covers 720p and 1080p. */
    val quality: String
        get() = when (format?.uppercase()) {
            "4K", "UHD" -> "4K"
            "HD" -> "HD"
            "QHD" -> "1440p"
            "SD" -> "SD"
            else -> ""
        }

    /** Subscription and free first, then TV provider, rent, buy. */
    val rank: Int get() = when (type) { "sub" -> 0; "free" -> 1; "tve" -> 2; "addon" -> 3; "rent" -> 4; else -> 5 }
}

/**
 * Watchmode (api.watchmode.com): which services carry a title in your region.
 * Needs WATCHMODE_API_KEY. The free plan allows about 1,000 requests a month, so title
 * results are cached for the session and the service list is fetched at most once a week.
 */
class WatchmodeClient(
    private val key: () -> String = { ApiKeys.get("WATCHMODE_API_KEY") },
    private val get: suspend (String, Map<String, String>) -> kotlinx.serialization.json.JsonElement = Http::privateGet,
    clock: () -> Long = System::currentTimeMillis,
) {
    private val cache = OfferCache(clock)
    val available get() = key().isNotBlank()
    private suspend fun request(path: String, credential: String, country: String): kotlinx.serialization.json.JsonElement =
        kotlinx.coroutines.withTimeout(10_000) {
            check(key() == credential) { "Streaming provider configuration changed." }
            val url = okhttp3.HttpUrl.Builder().scheme("https").host("api.watchmode.com").addPathSegments("v1/$path")
                .addQueryParameter("apiKey", credential).addQueryParameter("regions", country).build().toString()
            get(url, mapOf("Cache-Control" to "no-store"))
        }
    suspend fun services(region: String): List<StreamingService> {
        val credential = key().takeIf { it.isNotBlank() } ?: return emptyList()
        val data = request("sources/", credential, OfferRequests.country(region))
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        check(key() == credential) { "Streaming provider configuration changed." }
        check(data is kotlinx.serialization.json.JsonArray) { "Watchmode returned invalid services." }
        return data.mapNotNull { s ->
            StreamingService(s.int("id") ?: return@mapNotNull null, s.str("name") ?: return@mapNotNull null,
                s.str("type") ?: "", s.str("logo_100px"), s.str("android_playstore_url")
                    ?.substringAfter("id=", "")?.substringBefore('&')?.takeIf { it.isNotBlank() })
        }.distinctBy { it.id }
    }
    suspend fun offers(imdbId: String, region: String): List<Offer> {
        val credential = key().takeIf { it.isNotBlank() } ?: return emptyList()
        val imdb = OfferRequests.imdb(imdbId) ?: return emptyList()
        val country = OfferRequests.country(region)
        return cache.get(OfferRequests.identity(credential, country, imdb), { key() == credential }) {
            val data = request("title/$imdb/sources/", credential, country)
            check(data is kotlinx.serialization.json.JsonArray) { "Watchmode returned invalid offers." }
            val all = data.mapNotNull { o ->
                Offer(o.int("source_id") ?: return@mapNotNull null, o.str("name") ?: return@mapNotNull null,
                    o.str("type") ?: "", o.dbl("price")?.takeIf { it.isFinite() && it >= 0 },
                    o.str("format"), o.str("web_url"), o.str("android_url"))
            }
            all.groupBy { it.serviceId }.values.map { list ->
                list.minWith(compareBy<Offer> { it.rank }.thenBy { it.price ?: 0.0 }.thenByDescending {
                    when (it.format?.uppercase(java.util.Locale.ROOT)) { "4K", "UHD" -> 3; "HD" -> 2; "SD" -> 1; else -> 0 }
                })
            }.sortedWith(compareBy<Offer> { it.rank }.thenBy { it.name.lowercase(java.util.Locale.ROOT) })
        }
    }
}

object Watchmode {
    private val client = WatchmodeClient()
    val available get() = client.available
    suspend fun services(region: String) = client.services(region)
    suspend fun offers(imdbId: String, region: String) = client.offers(imdbId, region)
}

/** Finds the installed TV app for a service. */
object ServiceApps {
    /**
     * Watchmode links the phone apps; the TV apps often have other package names.
     * Map phone package names to their corresponding TV app packages.
     */
    private val tvPackages = mapOf(
        "com.netflix.mediaclient" to "com.netflix.ninja",
        "com.amazon.avod.thirdpartyclient" to "com.amazon.amazonvideo.livingroom",
        "com.google.android.youtube" to "com.google.android.youtube.tv",
        "com.hulu.plus" to "com.hulu.livingroomplus",
        "com.cbs.app" to "com.cbs.ott",
        "com.wbd.stream" to "com.wbd.stream",
        "com.disney.disneyplus" to "com.disney.disneyplus",
        "com.peacocktv.peacockandroid" to "com.peacocktv.peacockandroid",
        "com.crunchyroll.crunchyroid" to "com.crunchyroll.crunchyroid",
    )

    private fun norm(s: String) = s.lowercase().replace("+", "plus").filter { it.isLetterOrDigit() }

    /** The installed app for [service], by package first, then by matching names ("Prime Video"). */
    fun installedPackage(service: StreamingService, apps: List<AppEntry>): String? {
        val candidates = listOfNotNull(service.playStorePackage, service.playStorePackage?.let { tvPackages[it] })
        apps.firstOrNull { it.pkg in candidates }?.let { return it.pkg }
        val n = norm(service.name)
        if (n.length < 3) return null
        return apps.firstOrNull { app ->
            val a = norm(app.label)
            a == n || (a.length >= 4 && (a.startsWith(n) || n.startsWith(a))) || (a.length >= 6 && n.contains(a))
        }?.pkg
    }

    /** Package to install from the store when the app is missing: the TV app when known. */
    fun storePackage(service: StreamingService): String? =
        service.playStorePackage?.let { tvPackages[it] ?: it }
}
