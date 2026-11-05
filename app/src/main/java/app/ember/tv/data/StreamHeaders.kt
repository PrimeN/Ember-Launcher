package app.ember.tv.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Request headers a Stremio addon sends with a stream (`behaviorHints.proxyHeaders.request`).
 * Live TV and some hosters only answer with their User-Agent, Referer or Origin, so the players
 * send them. Only well-formed, single-line headers are kept, and never the ones the player manages
 * itself (Host, Range, Content-Length and connection headers).
 */
object StreamHeaders {
    private val token = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}")
    private val reserved = setOf("host", "range", "content-length", "connection", "transfer-encoding",
        "upgrade", "te", "keep-alive", "proxy-connection", "proxy-authorization", "expect", "trailer")
    private const val MAX_HEADERS = 16
    private const val MAX_VALUE = 2048

    fun parse(behaviorHints: JsonObject?): Map<String, String> {
        val request = (behaviorHints?.get("proxyHeaders") as? JsonObject)?.get("request") as? JsonObject ?: return emptyMap()
        return clean(request.mapNotNull { (name, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { name to it } })
    }

    fun clean(headers: List<Pair<String, String>>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((rawName, rawValue) in headers) {
            val name = rawName.trim(); val value = rawValue.trim()
            if (!token.matches(name) || name.lowercase(java.util.Locale.ROOT) in reserved) continue
            // Match OkHttp's value rules so malformed add-on headers cannot fail playback setup.
            if (value.isEmpty() || value.length > MAX_VALUE || value.any { it != '\t' && (it < ' ' || it > '~') }) continue
            if (out.keys.any { it.equals(name, ignoreCase = true) }) continue
            out[name] = value
            if (out.size == MAX_HEADERS) break
        }
        return out
    }

    fun userAgent(headers: Map<String, String>): String? = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
    fun withoutUserAgent(headers: Map<String, String>): Map<String, String> = headers.filterKeys { !it.equals("User-Agent", true) }
}
