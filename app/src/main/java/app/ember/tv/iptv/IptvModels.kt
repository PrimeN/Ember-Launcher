package app.ember.tv.iptv

import kotlinx.serialization.Serializable

/** An IPTV provider: an M3U playlist link, or an Xtream Codes login. */
@Serializable
data class IptvSource(
    val id: String,
    val name: String,
    /** "m3u" or "xtream". */
    val kind: String,
    /** M3U: the playlist link. Xtream: the server address, e.g. http://host:8080. */
    val url: String,
    val username: String = "",
    val password: String = "",
    /** Guide (XMLTV) link. Empty: the playlist's own (url-tvg) or the Xtream server's. */
    val epgUrl: String = "",
    /** Sent with every request when the provider needs a particular player name. */
    val userAgent: String = "",
    /** Xtream: "ts" (default, most compatible) or "m3u8" (HLS). */
    val streamFormat: String = "ts",
    val enabled: Boolean = true,
)

@Serializable
data class Channel(
    /** Stable key: source id + the channel's own id or link. */
    val key: String,
    val sourceId: String,
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String = "",
    /** XMLTV channel id (tvg-id) to match the guide. */
    val epgId: String? = null,
    val number: Int? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
)

/** One programme in the guide. Times in milliseconds since 1970 (UTC). */
@Serializable
data class Programme(
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String? = null,
    val image: String? = null,
    val subtitle: String? = null,
    val rating: String? = null,
    val category: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)
