package app.ember.tv.iptv

import app.ember.tv.data.toHex
import app.ember.tv.tracker.int
import app.ember.tv.tracker.list
import app.ember.tv.tracker.str
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.coroutines.ensureActive

/** Xtream Codes API: live categories and channels from player_api.php. */
object Xtream {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun base(s: IptvSource) = s.url.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }

    private fun api(s: IptvSource, action: String? = null) =
        base(s) + "/player_api.php?username=${enc(s.username)}&password=${enc(s.password)}" + (action?.let { "&action=$it" } ?: "")

    fun epgUrl(s: IptvSource) = base(s) + "/xmltv.php?username=${enc(s.username)}&password=${enc(s.password)}"

    /** Checks the login; returns an error message or null. */
    suspend fun check(s: IptvSource): String? = runCatching {
        val user = (request(s, api(s), true) as? kotlinx.serialization.json.JsonObject)?.get("user_info")
        val ok = user.str("auth") == "1" || user.int("auth") == 1
        if (ok) null else "The server did not accept that username and password."
    }.getOrElse { kotlinx.coroutines.currentCoroutineContext().ensureActive(); ProviderRequests.failure(it) }

    suspend fun channels(s: IptvSource, interactive: Boolean = false): List<Channel> {
        val cats = request(s, api(s, "get_live_categories"), interactive).list()
            .associate { (it.str("category_id") ?: "") to (it.str("category_name") ?: "Other") }
        val ext = if (s.streamFormat == "m3u8") "m3u8" else "ts"
        // Large providers send 30-60 MB here: read one channel at a time instead of holding the
        // whole document as text and a JSON tree (#53).
        return ProviderRequests.read(budgetKey(s), api(s, "get_live_streams"), s.userAgent, interactive) { input ->
            streams(input, s, cats, ext)
        }
    }

    /** Channels from a get_live_streams response, decoded element by element. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    internal fun streams(input: java.io.InputStream, s: IptvSource, cats: Map<String, String>, ext: String): List<Channel> {
        val out = ArrayList<Channel>()
        // AUTO_DETECT: an error object instead of an array yields one element without a stream_id.
        Json.decodeToSequence(input.buffered(64 * 1024), kotlinx.serialization.json.JsonElement.serializer(),
            kotlinx.serialization.json.DecodeSequenceMode.AUTO_DETECT).forEach { c ->
            val id = c.str("stream_id") ?: c.int("stream_id")?.toString() ?: return@forEach
            out += Channel(
                key = "${s.id}:$id",
                sourceId = s.id,
                name = c.str("name")?.trim() ?: return@forEach,
                url = "${base(s)}/live/${enc(s.username)}/${enc(s.password)}/$id.$ext",
                logo = c.str("stream_icon")?.ifBlank { null },
                group = cats[c.str("category_id") ?: ""] ?: "Other",
                epgId = c.str("epg_channel_id")?.ifBlank { null },
                number = c.str("num")?.toIntOrNull() ?: c.int("num"),
                userAgent = s.userAgent.ifBlank { null },
            )
        }
        out.trimToSize()
        return out
    }

    /** Share a budget between duplicate entries for one host/account, without retaining its password. */
    fun budgetKey(s: IptvSource): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest((base(s).lowercase(java.util.Locale.ROOT) + "\n" + s.username).toByteArray())
        .toHex()
    private suspend fun request(s: IptvSource, url: String, interactive: Boolean) =
        ProviderRequests.read(budgetKey(s), url, s.userAgent, interactive) { Json.parseToJsonElement(it.bufferedReader().readText()) }
}
