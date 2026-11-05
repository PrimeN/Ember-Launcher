package app.ember.tv.streaming

import app.ember.tv.data.ApiKeys
import app.ember.tv.tracker.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/** RapidAPI v4. Header authentication, no disk offer cache and no request when unconfigured. */
class StreamingAvailabilityClient(
    private val key: () -> String = { ApiKeys.get("STREAMING_AVAILABILITY_API_KEY") },
    private val get: suspend (String, Map<String, String>) -> JsonElement = Http::privateGet,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val available get() = key().isNotBlank()
    private val cache = OfferCache(clock)
    private fun region(value: String): String = value.lowercase(Locale.ROOT).also {
        require(it.matches(Regex("[a-z]{2}"))) { "Use a two-letter country code." }
    }
    private suspend fun request(path: String, credential: String): JsonElement = withTimeout(8_000) {
        get("https://$HOST/$path", mapOf("X-RapidAPI-Key" to credential,
            "X-RapidAPI-Host" to HOST, "Cache-Control" to "no-store"))
    }
    suspend fun services(country: String): List<StreamingService> {
        val credential = key().takeIf { it.isNotBlank() } ?: return emptyList()
        val response = request("countries/${region(country)}?output_language=en", credential)
        currentCoroutineContext().ensureActive()
        check(key() == credential) { "Streaming Availability key changed." }
        return parseServices(response)
    }
    suspend fun offers(imdb: String, country: String): List<Offer> {
        val credential = key().takeIf { it.isNotBlank() } ?: return emptyList()
        if (!imdb.matches(Regex("tt[0-9]{5,12}"))) return emptyList()
        val region = region(country)
        return cache.get(OfferRequests.identity(credential, region, imdb), { key() == credential }) {
            parseOffers(request("shows/$imdb?series_granularity=show&output_language=en", credential), region, imdb, clock())
        }
    }
    companion object {
        const val HOST = "streaming-availability.p.rapidapi.com"
        private fun safeUrl(value: String?): String? = value?.takeIf { it.length <= 4096 }?.toHttpUrlOrNull()
            ?.takeIf { it.scheme == "https" && it.username.isEmpty() && it.password.isEmpty() }?.toString()
        private fun service(data: JsonElement?): StreamingService? {
            val id = data.str("id")?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,80}")) } ?: return null
            val name = data.str("name")?.trim()?.takeIf { it.isNotBlank() && it.length <= 150 } ?: return null
            return StreamingService(id.hashCode(), name, "sub", externalId = id)
        }
        internal fun parseServices(data: JsonElement): List<StreamingService> {
            val list = data.arr("services") ?: error("Streaming Availability returned an invalid service list.")
            return list.flatMap { entry ->
                val parent = service(entry) ?: return@flatMap emptyList()
                listOf(parent) + entry.arr("addons").orEmpty().mapNotNull { raw ->
                    val addon = service(raw) ?: return@mapNotNull null
                    val id = "${parent.externalId}:${addon.externalId}"
                    addon.copy(id = id.hashCode(), name = "${addon.name} on ${parent.name}", type = "addon", externalId = id)
                }
            }.distinctBy { it.externalId }
        }
        internal fun parseOffers(data: JsonElement, region: String, imdb: String, now: Long = System.currentTimeMillis()): List<Offer> {
            check(data.str("imdbId") == imdb) { "Streaming Availability returned a different title." }
            val countries = data.obj("streamingOptions") ?: error("Streaming Availability returned invalid offers.")
            val raw = countries[region.lowercase(Locale.ROOT)] ?: return emptyList()
            check(raw is JsonArray) { "Streaming Availability returned invalid regional offers." }
            return raw.mapNotNull { entry ->
                val expires = entry.long("expiresOn")?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000)
                if (expires != null && expires <= now) return@mapNotNull null
                val s = service(entry.obj("service")) ?: return@mapNotNull null
                val type = when (entry.str("type")) {
                    "subscription" -> "sub"; "free" -> "free"; "rent" -> "rent"; "buy" -> "buy"; "addon" -> "addon"
                    else -> return@mapNotNull null
                }
                val link = safeUrl(entry.str("link")) ?: return@mapNotNull null
                val price = entry.obj("price")
                val currency = price.str("currency")?.uppercase(Locale.ROOT)?.takeIf {
                    it.matches(Regex("[A-Z]{3}")) && runCatching { java.util.Currency.getInstance(it) }.isSuccess
                }
                val amount = price.str("amount")?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && currency != null }
                // Add-on subscriptions are not the base service subscription.
                val addon = if (type == "addon") service(entry.obj("addon")) else null
                if (type == "addon" && addon == null) return@mapNotNull null
                val external = if (addon != null) "${s.externalId}:${addon.externalId}" else s.externalId!!
                Offer(external.hashCode(), if (addon != null) "${addon.name} on ${s.name}" else s.name, type,
                    amount, entry.str("quality"), link, null, externalId = external, currency = currency, expiresAt = expires)
            }.groupBy { it.externalId }.values.map { list ->
                list.minWith(compareBy<Offer> { it.rank }.thenBy { it.price ?: 0.0 }
                    .thenByDescending { when (it.format) { "uhd" -> 3; "qhd" -> 2; "hd" -> 1; else -> 0 } })
            }.sortedWith(compareBy<Offer> { it.rank }.thenBy { it.name })
        }
    }
}

object StreamingAvailability {
    private val client = StreamingAvailabilityClient()
    val available get() = client.available
    suspend fun services(region: String) = client.services(region)
    suspend fun offers(imdb: String, region: String) = client.offers(imdb, region)
}
