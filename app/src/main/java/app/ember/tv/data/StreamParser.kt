package app.ember.tv.data

import app.ember.tv.tracker.arr
import app.ember.tv.tracker.int
import app.ember.tv.tracker.list
import app.ember.tv.tracker.obj
import app.ember.tv.tracker.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns a Stremio stream object into a [StreamOption] with readable labels.
 * Normalizes source metadata and distinguishes cached links from pending downloads.
 */
object StreamParser {
    private val dvToken = Regex("""(^|[\s.\-_\[(])DV(?=$|[\s.\-_\])])""")
    private val dolbyVision = Regex("""Dolby\s*Vision""", RegexOption.IGNORE_CASE)
    private val hdrWord = Regex("""\bHDR\b""", RegexOption.IGNORE_CASE)
    private val sizeRe = Regex("""(\d+(?:\.\d+)?)\s?(TB|GB|MB)""", RegexOption.IGNORE_CASE)
    private val seedersRe = Regex("""[👤👥]\s*(\d+)""")
    private val groupDash = Regex("""-(\w+)\s*$""")
    private val groupBracket = Regex("""\[(\w+)]\s*$""")

    private val fhdWord = Regex("""(?<![A-Za-z])FHD(?![A-Za-z])""")
    private val hdWord = Regex("""(?<![A-Za-z\-])HD(?![A-Za-z0-9+\-])""")
    private val sdWord = Regex("""(?<![A-Za-z\-])SD(?![A-Za-z0-9\-])""")

    /** Network schemes an addon may hand to the players. Anything else (file://, content://, ffmpeg
     * pseudo-protocols such as lavfi or tcp) is dropped: an addon must not open local files or
     * bypass Ember's HTTP transport, TLS checks and headers. */
    private val streamSchemes = setOf("http", "https", "rtsp", "rtsps", "rtmp", "rtmps")

    internal fun allowedUrl(url: String): Boolean {
        if (url.startsWith("magnet:?", ignoreCase = true)) return true
        val scheme = url.substringBefore("://", "").lowercase(java.util.Locale.ROOT)
        if (scheme !in streamSchemes) return false
        val host = url.substringAfter("://").substringBefore('/').substringBefore('?').substringAfterLast('@')
        return host.isNotEmpty() && host.none { it.isWhitespace() || it.isISOControl() }
    }

    /**
     * A stream that opens elsewhere instead of playing here (#73): `externalUrl` (often a
     * streaming service's own app or page) or `ytId` (YouTube). Shown with the streaming services.
     */
    fun link(addonName: String, j: JsonElement): app.ember.tv.streaming.Offer? {
        if (!j.str("url").isNullOrBlank() || !j.str("infoHash").isNullOrBlank()) return null
        val yt = j.str("ytId")?.trim()?.takeIf { ytIdRe.matches(it) }
        val external = j.str("externalUrl")?.trim()?.takeIf(::safeExternal)
        if (yt == null && external == null) return null
        val name = j.str("name").orEmpty()
        val text = "$name\n${(j.str("title") ?: j.str("description")).orEmpty()}"
        val web = yt?.let { "https://www.youtube.com/watch?v=$it" } ?: external?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        val deepLink = if (yt == null) external?.takeIf { web == null } else null
        val key = yt?.let { "yt:$it" } ?: external!!
        val service = if (yt != null) "YouTube" else serviceName(external!!) ?: name.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: addonName
        return app.ember.tv.streaming.Offer(
            serviceId = -1 - (key.hashCode() and 0x7fffffff) % 1_000_000, name = service, type = "link", price = null,
            format = when (quality(text)) { "4K" -> "4K"; "FHD", "HD" -> "HD"; "SD" -> "SD"; else -> null },
            webUrl = web, androidUrl = deepLink, externalId = "addon-link:$key",
        )
    }

    private val ytIdRe = Regex("[A-Za-z0-9_-]{11}")
    /** Web links and app links; never schemes that run code, read files or target components. */
    internal fun safeExternal(url: String): Boolean {
        val scheme = url.substringBefore(':', "").lowercase(java.util.Locale.ROOT)
        if (scheme.isEmpty() || !scheme.all { it.isLetterOrDigit() || it in "+.-" } || !scheme.first().isLetter()) return false
        if (scheme in setOf("javascript", "data", "file", "content", "intent", "about", "vbscript", "android-app", "jar", "blob")) return false
        return url.none { it.isWhitespace() || it.isISOControl() } && url.length <= 2048
    }
    private val serviceHosts = listOf(
        "netflix.com" to "Netflix", "disneyplus.com" to "Disney+", "primevideo.com" to "Prime Video", "amazon." to "Prime Video",
        "max.com" to "Max", "hbomax.com" to "Max", "hulu.com" to "Hulu", "tv.apple.com" to "Apple TV", "paramountplus.com" to "Paramount+",
        "peacocktv.com" to "Peacock", "crunchyroll.com" to "Crunchyroll", "youtube.com" to "YouTube", "youtu.be" to "YouTube",
        "crave.ca" to "Crave", "tubitv.com" to "Tubi", "pluto.tv" to "Pluto TV", "plex.tv" to "Plex", "twitch.tv" to "Twitch",
    )
    /** The service a link points to, by its host (netflix.com -> Netflix) or app scheme (netflix:// -> Netflix). */
    internal fun serviceName(url: String): String? {
        val host = url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringAfterLast('@').lowercase(java.util.Locale.ROOT)
        serviceHosts.firstOrNull { (h, _) -> host == h || host.endsWith(".$h") || (h.endsWith(".") && host.contains(h)) }?.let { return it.second }
        val scheme = url.substringBefore(':', "").lowercase(java.util.Locale.ROOT)
        return serviceHosts.firstOrNull { (h, _) -> h.substringBefore('.') == scheme }?.second
    }

    /** One subtitle from an addon, over the web only: a file:// or content:// link would show a local file. */
    fun subtitle(addonName: String, s: JsonElement): SubtitleOption? {
        val url = s.str("url")?.trim()?.takeIf { (it.startsWith("https://", true) || it.startsWith("http://", true)) && allowedUrl(it) } ?: return null
        return SubtitleOption(addonName, s.str("lang") ?: s.str("label") ?: "Unknown", url)
    }

    fun parse(addonName: String, j: JsonElement): StreamOption? {
        val url = j.str("url")?.trim()?.takeIf { it.isNotEmpty() && allowedUrl(it) }
        val hash = j.str("infoHash")?.trim()?.takeIf(String::isNotEmpty)
        if (url == null && hash == null) return null // ytId and externalUrl: see link()

        val name = j.str("name").orEmpty()
        val title = (j.str("title") ?: j.str("description")).orEmpty()
        val text = "$name\n$title"
        val firstLine = title.lineSequence().firstOrNull().orEmpty()

        val size = sizeRe.find(text)?.let { m ->
            val v = m.groupValues[1].toDoubleOrNull() ?: return@let null
            when (m.groupValues[2].uppercase()) {
                "TB" -> v * 1024
                "MB" -> v / 1024
                else -> v
            }
        } ?: j.obj("behaviorHints").let { h -> (h?.get("videoSize") as? JsonPrimitive)?.content?.toLongOrNull()?.let { it / 1_073_741_824.0 } }

        val isMagnet = url == null || url.startsWith("magnet:", ignoreCase = true)
        // Service and cache state, from the addon's structured data when it sends it, otherwise from
        // how it labels the result. See StreamInfoParser.
        val info = (j as? kotlinx.serialization.json.JsonObject)?.let { StreamInfoParser.parse(addonName, it) }
        val cached = !isMagnet && info?.cached == true
        val debrid = !isMagnet && info?.cached != null

        return StreamOption(
            addonName = addonName,
            name = name.ifBlank { addonName },
            title = title,
            url = url,
            infoHash = hash,
            fileIdx = j.int("fileIdx"),
            quality = quality(text),
            hdr = hdr(text),
            codec = codec(text),
            audio = audio(text),
            source = source(text),
            releaseGroup = group(firstLine),
            sizeGb = size,
            seeders = seedersRe.find(text)?.groupValues?.get(1)?.toIntOrNull(),
            cached = cached,
            debrid = debrid,
            info = info,
            headers = if (url != null) StreamHeaders.parse(j.obj("behaviorHints")) else emptyMap(),
            bingeGroup = j.obj("behaviorHints").str("bingeGroup")?.trim()?.takeIf { it.isNotEmpty() }?.take(200),
            videoHash = j.obj("behaviorHints").str("videoHash")?.trim()?.lowercase(java.util.Locale.ROOT)?.takeIf { videoHashRe.matches(it) },
            videoSize = (j.obj("behaviorHints")?.get("videoSize") as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it > 0 },
            subtitles = j.arr("subtitles").list().mapNotNull { subtitle(addonName, it) }.take(30),
        )
    }

    private fun has(text: String, vararg tokens: String) = tokens.any { text.contains(it, ignoreCase = true) }

    /** 4K, FHD (1080p), HD (720p), SD (lower), or blank when the text does not say. */
    fun quality(t: String) = when {
        has(t, "2160p", "4K", "UHD") -> "4K"
        has(t, "1440p", "1080p", "1080i") || fhdWord.containsMatchIn(t) -> "FHD"
        has(t, "720p") || hdWord.containsMatchIn(t) -> "HD"
        has(t, "576p", "540p", "480p", "360p", "240p", "DVDRip", "HDCAM", "TELESYNC") ||
            sdWord.containsMatchIn(t) -> "SD"
        else -> ""
    }

    fun hdr(t: String): String {
        val tags = ArrayList<String>()
        if (dolbyVision.containsMatchIn(t) || dvToken.containsMatchIn(t)) tags += "DV"
        when {
            has(t, "HDR10+", "HDR10Plus", "HDR10 +") -> tags += "HDR10+"
            has(t, "HDR10") -> tags += "HDR10"
            hdrWord.containsMatchIn(t) -> tags += "HDR"
        }
        return tags.joinToString(" + ")
    }

    fun codec(t: String) = when {
        has(t, "VVC", "H.266", "H266") -> "VVC"
        has(t, "x265", "HEVC", "H.265", "H265") -> "HEVC"
        has(t, "x264", "AVC", "H.264", "H264") -> "H.264"
        has(t, "AV1") -> "AV1"
        has(t, "VP9") -> "VP9"
        has(t, "XVID") -> "XviD"
        else -> ""
    }

    fun audio(t: String) = when {
        has(t, "Atmos") -> "Atmos"
        has(t, "DTS-X", "DTS:X") -> "DTS:X"
        has(t, "TrueHD", "True-HD") -> "TrueHD"
        has(t, "DTS-HD MA", "DTS-HD.MA") -> "DTS-HD MA"
        has(t, "DTS-HD") -> "DTS-HD"
        has(t, "DTS") -> "DTS"
        has(t, "DD+", "EAC3", "DDP") -> "DD+"
        has(t, "DD5.1", "AC3") -> "DD 5.1"
        has(t, "AAC") -> "AAC"
        else -> ""
    }

    fun source(t: String) = when {
        has(t, "REMUX") -> "Remux"
        has(t, "BluRay", "BDRip", "BRRip") -> "BluRay"
        has(t, "WEB-DL", "WEBDL") -> "WEB-DL"
        has(t, "WEBRip", "WEB-Rip") -> "WEBRip"
        has(t, "HDTV") -> "HDTV"
        has(t, "DVDRip") -> "DVDRip"
        has(t, "CAM", "HDCAM", "TELESYNC") -> "CAM"
        else -> ""
    }

    fun group(firstLine: String): String =
        groupBracket.find(firstLine)?.groupValues?.get(1)
            ?: groupDash.find(firstLine)?.groupValues?.get(1)
            ?: ""

    /**
     * For auto-play: a direct source close to the last one, from the same addon and at
     * the same quality when possible, otherwise the best direct source.
     */
    /** OpenSubtitles hashes are 64-bit, written as 16 hex digits. */
    private val videoHashRe = Regex("[0-9a-f]{16}")

    /** The next episode's source: the same binge group (#74), then the same addon and quality. */
    fun pickSimilar(options: List<StreamOption>, addonName: String?, quality: String?, bingeGroup: String? = null): StreamOption? =
        options.asSequence().filter { it.isDirect }
            .minWithOrNull(
                compareBy<StreamOption> { it.kind == SourceKind.UNCACHED }
                    .thenByDescending { bingeGroup != null && it.bingeGroup == bingeGroup }
                    .thenByDescending { it.addonName == addonName }
                    .thenByDescending { it.quality == quality }
                    .then(order)
            )

    /** Direct (cached) first, then web links, then un-cached; within each, quality, seeders, size. */
    val order: Comparator<StreamOption> = compareBy<StreamOption> {
        when (it.kind) {
            SourceKind.SERVICE -> 0
            SourceKind.DIRECT -> 1
            SourceKind.WEB -> 2
            SourceKind.UNCACHED -> 3
        }
    }
        .thenByDescending { it.qualityRank }
        .thenByDescending { it.seeders ?: 0 }
        .thenByDescending { it.sizeGb ?: 0.0 }
}
