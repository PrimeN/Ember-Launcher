package app.ember.tv.iptv

/**
 * Reads an M3U / M3U8 playlist:
 *   #EXTM3U url-tvg="http://guide.xml"
 *   #EXTINF:-1 tvg-id="bbc1.uk" tvg-name="BBC One" tvg-logo="http://..." group-title="UK",BBC One HD
 *   #EXTVLCOPT:http-user-agent=...
 *   http://host/stream.ts
 */
object M3u {
    data class Parsed(val channels: List<Channel>, val epgUrl: String?)

    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(text: String, source: IptvSource): Parsed = parseLines(text.lineSequence(), source)

    /**
     * Films and series that Xtream-style playlists list next to the channels (links under /movie/
     * or /series/, or video files). Live TV leaves them out: they can be tens of thousands of
     * entries and are not channels.
     */
    fun isVod(url: String): Boolean {
        val u = url.lowercase().substringBefore('?')
        return "/movie/" in u || "/series/" in u || u.endsWith(".mp4") || u.endsWith(".mkv") || u.endsWith(".avi")
    }

    /** Reads the playlist line by line, so a large one is never held in memory as a whole. */
    fun parseLines(lines: Sequence<String>, source: IptvSource, skipVod: Boolean = false): Parsed {
        var epg: String? = null
        val out = ArrayList<Channel>()
        var info: String? = null
        var ua: String? = null
        var ref: String? = null
        var group: String? = null
        for (raw in lines) {
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXTM3U", true) -> {
                    val a = attrs(line)
                    epg = a["url-tvg"] ?: a["x-tvg-url"] ?: a["tvg-url"]
                    epg = epg?.split(',')?.firstOrNull { it.isNotBlank() }?.trim()
                }
                line.startsWith("#EXTINF", true) -> info = line
                line.startsWith("#EXTGRP:", true) -> group = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:", true) -> {
                    val opt = line.substringAfter(':')
                    when {
                        opt.startsWith("http-user-agent=", true) -> ua = opt.substringAfter('=')
                        opt.startsWith("http-referrer=", true) || opt.startsWith("http-referer=", true) -> ref = opt.substringAfter('=')
                    }
                }
                line.startsWith("#") -> {}
                skipVod && isVod(line) -> {
                    info = null
                    ua = null
                    ref = null
                    group = null
                }
                else -> {
                    val i = info
                    val a = i?.let { attrs(it) }.orEmpty()
                    // The name is after the first comma that is outside the attribute quotes.
                    val name = i?.let { nameOf(it) }?.ifBlank { null } ?: a["tvg-name"] ?: line.substringAfterLast('/')
                    val id = a["tvg-id"]?.ifBlank { null }
                    out += Channel(
                        key = source.id + ":" + (id?.let { "$it|$name" } ?: line),
                        sourceId = source.id,
                        name = name,
                        url = line,
                        logo = a["tvg-logo"]?.ifBlank { null },
                        group = (a["group-title"] ?: group).orEmpty().ifBlank { "Other" },
                        epgId = id,
                        number = (a["tvg-chno"] ?: a["channel-number"])?.toIntOrNull(),
                        userAgent = ua ?: a["user-agent"] ?: a["http-user-agent"],
                        referrer = ref ?: a["http-referrer"],
                    )
                    info = null
                    ua = null
                    ref = null
                    group = null
                }
            }
        }
        return Parsed(out.distinctBy { it.key }, epg)
    }

    private fun attrs(line: String): Map<String, String> =
        attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    /** The title is everything after the first comma outside the attribute quotes, so names may contain commas. */
    private fun nameOf(info: String): String {
        var inQuote = false
        info.forEachIndexed { i, c ->
            if (c == '"') inQuote = !inQuote
            if (c == ',' && !inQuote) return info.substring(i + 1).trim()
        }
        return ""
    }
}
