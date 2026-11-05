package app.ember.tv.iptv

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
        return request(s, api(s, "get_live_streams"), interactive).list().mapNotNull { c ->
            val id = c.str("stream_id") ?: c.int("stream_id")?.toString() ?: return@mapNotNull null
            Channel(
                key = "${s.id}:$id",
                sourceId = s.id,
                name = c.str("name")?.trim() ?: return@mapNotNull null,
                url = "${base(s)}/live/${enc(s.username)}/${enc(s.password)}/$id.$ext",
                logo = c.str("stream_icon")?.ifBlank { null },
                group = cats[c.str("category_id") ?: ""] ?: "Other",
                epgId = c.str("epg_channel_id")?.ifBlank { null },
                number = c.str("num")?.toIntOrNull() ?: c.int("num"),
                userAgent = s.userAgent.ifBlank { null },
            )
        }
    }

    /** Share a budget between duplicate entries for one host/account, without retaining its password. */
    fun budgetKey(s: IptvSource): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest((base(s).lowercase(java.util.Locale.ROOT) + "\n" + s.username).toByteArray())
        .joinToString("") { "%02x".format(it) }
    private suspend fun request(s: IptvSource, url: String, interactive: Boolean) =
        ProviderRequests.read(budgetKey(s), url, s.userAgent, interactive) { Json.parseToJsonElement(it.bufferedReader().readText()) }
}
