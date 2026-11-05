package app.ember.tv.data

import app.ember.tv.tracker.int
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

    fun parse(addonName: String, j: JsonElement): StreamOption? {
        val url = j.str("url")?.trim()?.takeIf { it.isNotEmpty() && allowedUrl(it) }
        val hash = j.str("infoHash")?.trim()?.takeIf(String::isNotEmpty)
        if (url == null && hash == null) return null // ytId and externalUrl streams are not supported yet

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
    fun pickSimilar(options: List<StreamOption>, addonName: String?, quality: String?): StreamOption? =
        options.asSequence().filter { it.isDirect }
            .minWithOrNull(
                compareBy<StreamOption> { it.kind == SourceKind.UNCACHED }
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
