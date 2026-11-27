package app.ember.tv.data

import kotlinx.serialization.Serializable

/**
 * #471: sources you never want to see (Settings › Watching › Choosing a stream › Hide sources).
 * A hidden source is left out of the title screen's list, the Play button, dead-link skipping and
 * the next episode. Copies on your own media servers are never hidden.
 */
@Serializable
data class SourceRules(
    /** Un-cached debrid links and torrents: they have to download before they play. */
    val uncached: Boolean = false,
    /** CAM, TS and TELESYNC copies. */
    val cam: Boolean = false,
    /** Pictures to hide: "4K", "FHD", "HD", "SD". A source that doesn't say counts as SD. */
    val qualities: Set<String> = emptySet(),
    /** "DV", "HDR" (HDR10, HDR10+ or plain HDR) and "SDR". */
    val hdr: Set<String> = emptySet(),
    /** Codecs to hide, as StreamParser.codec names them. */
    val codecs: Set<String> = emptySet(),
    /** Release types to hide, as StreamParser.source names them: "Remux", "BluRay", "WEB-DL"… */
    val releases: Set<String> = emptySet(),
    /** Files bigger than this many GB, 0 for no limit. */
    val maxSizeGb: Int = 0,
    /** Files smaller than this many GB (samples and very compressed copies), 0 for no limit. */
    val minSizeGb: Int = 0,
    /** Words or tags anywhere in the source's text, any case: "HDTS", "Dual audio", a release group. */
    val words: List<String> = emptyList(),
    /** Addons whose sources are hidden, by name. */
    val addons: Set<String> = emptySet(),
) {
    /** How many rules are on, for the summary line. */
    val count: Int get() = listOf(uncached, cam, maxSizeGb > 0, minSizeGb > 0).count { it } +
        qualities.size + hdr.size + codecs.size + releases.size + words.size + addons.size
    val active: Boolean get() = count > 0

    /** Why [s] is hidden, in a few words, or null when it shows. */
    fun reason(s: StreamOption): String? {
        if (s.serverRef != null || s.serverPlayback != null) return null
        val text = "${s.name}\n${s.title}"
        return when {
            s.addonName in addons -> s.addonName
            uncached && s.kind == SourceKind.UNCACHED -> "Un-cached"
            cam && (s.source == "CAM" || camWord.containsMatchIn(text)) -> "CAM"
            s.qualityLabel in qualities -> s.qualityLabel
            hdr.isNotEmpty() && hdrKey(s.hdr) in hdr -> hdrKey(s.hdr)
            codecs.isNotEmpty() && s.codec.isNotBlank() && s.codec in codecs -> s.codec
            releases.isNotEmpty() && s.source.isNotBlank() && s.source in releases -> s.source
            maxSizeGb > 0 && (s.sizeGb ?: 0.0) > maxSizeGb -> "Over $maxSizeGb GB"
            minSizeGb > 0 && s.sizeGb != null && s.sizeGb < minSizeGb -> "Under $minSizeGb GB"
            else -> words.firstOrNull { w -> matches(w, text, s.releaseGroup) }
        }
    }

    fun hides(s: StreamOption): Boolean = reason(s) != null

    /** The sources that show, then those hidden. */
    fun split(list: List<StreamOption>): Pair<List<StreamOption>, List<StreamOption>> =
        if (!active) list to emptyList() else list.partition { !hides(it) }

    fun toggle(set: Set<String>, v: String) = if (v in set) set - v else set + v

    companion object {
        val QUALITIES = listOf("4K", "FHD", "HD", "SD")
        val HDR = listOf("DV", "HDR", "SDR")
        val CODECS = listOf("AV1", "HEVC", "H.264", "VP9", "XviD")
        val RELEASES = listOf("Remux", "BluRay", "WEB-DL", "WEBRip", "HDTV", "DVDRip")
        val MAX_SIZES = listOf(0, 5, 10, 20, 40, 80)
        val MIN_SIZES = listOf(0, 1, 2, 5)
        /** Ready-made tags people often hide; any other word can be typed. */
        val SUGGESTED_WORDS = listOf("HDTS", "TS", "Dubbed", "Dual audio", "Hardcoded subs", "3D", "Upscaled", "60fps")
        private val camWord = Regex("""(?i)(?<![a-z])(cam|hdcam|camrip|telesync|hdts|tc)(?![a-z])""")

        fun hdrKey(hdr: String): String = when {
            hdr.contains("DV") -> "DV"
            hdr.contains("HDR") -> "HDR"
            else -> "SDR"
        }

        /** Whole words, any case: "TS" doesn't hide "SUBS"; a phrase may have spaces, dots or dashes between its words. */
        fun matches(word: String, text: String, group: String = ""): Boolean {
            val w = word.trim()
            if (w.isEmpty()) return false
            if (group.isNotBlank() && group.equals(w, ignoreCase = true)) return true
            val pattern = w.split(Regex("[\\s._-]+")).filter { it.isNotEmpty() }.joinToString("[\\s._-]*") { Regex.escape(it) }
            return Regex("(?i)(?<![\\p{L}\\p{N}])$pattern(?![\\p{L}\\p{N}])").containsMatchIn(text)
        }

        /** Adds a typed word once, ignoring case. */
        fun addWord(words: List<String>, word: String): List<String> {
            val w = word.trim().take(40)
            return if (w.isEmpty() || words.any { it.equals(w, ignoreCase = true) }) words else words + w
        }
    }
}
