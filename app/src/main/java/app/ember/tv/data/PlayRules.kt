package app.ember.tv.data

import kotlinx.serialization.Serializable

/**
 * One-press play (Settings > Play button): which source the Play button on a title screen picks.
 * Sources that break a rule are skipped; of the rest, the best by these preferences plays.
 */
@Serializable
data class PlayRules(
    /** Show the Play button on title screens. */
    val enabled: Boolean = true,
    /** Highest picture to pick: "4K", "FHD" or "HD". Sources above it are skipped. */
    val maxQuality: String = "4K",
    /** "dv" Dolby Vision first, "hdr" HDR10/HDR10+ first and Dolby Vision last, "sdr" SDR first, "any" no preference. */
    val hdr: String = "dv",
    /** Codecs to skip, e.g. AV1 on players that cannot decode it. */
    val avoidCodecs: Set<String> = emptySet(),
    /** Prefer Atmos, DTS:X and other lossless sound. */
    val bestAudio: Boolean = true,
    /** Only sources that start at once: your servers, cached debrid and direct links (no torrents, no un-cached). */
    val readyOnly: Boolean = true,
    /** Largest file in GB, 0 for no limit (large files need a fast connection). */
    val maxSizeGb: Int = 0,
    /** A copy on your own Plex, Jellyfin or Emby server wins when it meets the rules. */
    val serverFirst: Boolean = true,
) {
    companion object {
        val CODECS = listOf("AV1", "HEVC", "VP9", "H.264")
        val SIZES = listOf(0, 10, 20, 40, 80)
    }
}

object PlayPicker {
    /** The source the Play button plays, or null when none meets the rules. */
    fun pick(addonSources: List<StreamOption>, serverSources: List<StreamOption>, r: PlayRules): StreamOption? {
        val maxRank = Quality.rank(r.maxQuality).takeIf { it > 0 } ?: 4
        fun ok(s: StreamOption): Boolean {
            if(s.url.isNullOrBlank() && s.infoHash.isNullOrBlank() && s.serverRef.isNullOrBlank() && s.serverPlayback==null)return false
            if (s.qualityRank > maxRank) return false
            if (s.codec.isNotBlank() && s.codec in r.avoidCodecs) return false
            if (r.maxSizeGb > 0 && (s.sizeGb ?: 0.0) > r.maxSizeGb) return false
            if (r.readyOnly && s.kind == SourceKind.UNCACHED) return false
            return true
        }
        fun hdrScore(s: StreamOption): Int {
            val dv = s.hdr.contains("DV")
            val hdr = s.hdr.contains("HDR")
            return when (r.hdr) {
                "dv" -> when { dv -> 3; hdr -> 2; else -> 1 }
                "hdr" -> when { hdr && !dv -> 3; hdr -> 2; dv -> 0; else -> 1 }
                "sdr" -> if (s.hdr.isBlank()) 2 else 1
                else -> 0
            }
        }
        fun audioScore(s: StreamOption): Int = if (!r.bestAudio) 0 else when (s.audio) {
            "Atmos", "DTS:X" -> 5
            "TrueHD", "DTS-HD MA" -> 4
            "DTS-HD" -> 3
            "DD+", "DTS" -> 2
            "DD 5.1" -> 1
            else -> 0
        }
        val order = compareByDescending<StreamOption> { if (r.serverFirst && it.serverRef != null) 1 else 0 }
            .thenByDescending { it.qualityRank }
            .thenByDescending { hdrScore(it) }
            .thenByDescending { audioScore(it) }
            .thenBy { when (it.kind) { SourceKind.DIRECT -> 0; SourceKind.WEB -> 1; else -> 2 } }
            .thenByDescending { it.seeders ?: 0 }
            .thenByDescending { it.sizeGb ?: 0.0 }
        return (serverSources.asSequence()+addonSources.asSequence()).filter(::ok).minWithOrNull(order)
    }

    /** "4K · DV · Atmos · Plex" for the button. */
    fun describe(s: StreamOption): String =
        listOf(s.qualityLabel, s.hdr, s.audio, if (s.serverRef != null) s.addonName else "").filter { it.isNotBlank() }.joinToString(" · ")
}
