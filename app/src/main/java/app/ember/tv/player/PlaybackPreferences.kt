package app.ember.tv.player

import kotlinx.serialization.Serializable
import java.util.Locale

/** Preferences are hints; normal decoder recovery remains available. */
@Serializable
data class DecoderRules(val local: String = "inherit", val network: String = "inherit",
    val codecs: Map<String, String> = emptyMap(), val extensions: Map<String, String> = emptyMap()) {
    fun preference(url: String, mime: String? = null, filename: String? = null): String {
        val codec = codecs[mime]?.takeIf { it in modes && it != "inherit" }
        if (codec != null) return codec
        val uri = runCatching { java.net.URI(url) }.getOrNull()
        val nameExtension = filename.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)
        val extension = nameExtension.takeIf {it in extensions} ?: uri?.path.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)
        val scheme=uri?.scheme?.lowercase(Locale.ROOT) ?: url.substringBefore(':', "").lowercase(Locale.ROOT).takeIf {it.isNotBlank()}
        return extensions[extension]?.takeIf { it in modes && it != "inherit" }
            ?: (if (scheme in setOf(null, "file", "content", "fd")) local else network).takeIf { it in modes }
            ?: "inherit"
    }
    companion object {
        val modes = setOf("inherit", "auto", "hardware", "software")
        val choices = listOf("inherit" to "Use default", "auto" to "Automatic", "hardware" to "Prefer hardware", "software" to "Prefer software")
        val codecChoices = listOf("video/avc" to "H.264", "video/hevc" to "HEVC", "video/av01" to "AV1", "video/x-vnd.on2.vp9" to "VP9", "video/mpeg2" to "MPEG-2")
        val extensionChoices = listOf("mkv", "mp4", "avi", "ts", "webm", "wmv", "vob")
    }
}

@Serializable
data class AudioEffects(val preset: String = "off", val bass: Int = 0, val channels: String = "normal") {
    val gains: List<Int> get() = when (preset) {
        "dialogue" -> listOf(-3, -2, 2, 4, 1)
        "warm" -> listOf(3, 2, 0, -1, -2)
        "bright" -> listOf(-2, -1, 0, 2, 3)
        else -> listOf(0, 0, 0, 0, 0)
    }
    val active get() = preset == "dialogue" || preset == "warm" || preset == "bright" || bass.coerceIn(0, 6) > 0 || channels == "mono" || channels == "swap"
    /** A fixed vocabulary only; no user-entered filter text reaches the native engine. */
    fun nativeFilters(channelCount: Int = 2): String {
        val filters = mutableListOf<String>()
        gains.mapIndexed { i, g -> g + if (i == 0) bass.coerceIn(0, 6) else 0 }.zip(listOf(80, 250, 1000, 4000, 10000)).filter { it.first != 0 }.forEach { (gain, frequency) ->
            filters += "equalizer=f=$frequency:t=o:w=1:g=$gain"
        }
        when (channels) {
            "mono" -> filters += "aformat=channel_layouts=mono"
            "swap" -> if (channelCount in 2..8) filters += "pan=${channelCount}c|" + (0 until channelCount).joinToString("|") { "c$it=c${if (it == 0) 1 else if (it == 1) 0 else it}" }
        }
        // Leave headroom for boosts instead of clipping peaks.
        val headroom = gains.maxOrNull()!!.coerceAtLeast(0) + bass.coerceIn(0, 6)
        if (headroom > 0) filters.add(0, "volume=-${headroom}dB")
        return if (filters.isEmpty()) "" else "lavfi=[${filters.joinToString(",")}]"
    }
    companion object {
        val presets = listOf("off" to "Off / flat", "dialogue" to "Clear dialogue", "warm" to "Warm", "bright" to "Bright")
        val channelChoices = listOf("normal" to "Original channels", "mono" to "Mono", "swap" to "Swap left and right")
    }
}

object PlayerShortcuts {
    val defaults = listOf("audio", "subtitles", "speed", "zoom")
    val choices = listOf("audio" to "Audio track", "subtitles" to "Subtitles", "speed" to "Playback speed", "zoom" to "Picture fit", "delay" to "Lip sync", "decoder" to "Video decoder", "effects" to "Audio effects", "chapters" to "Chapters", "queue" to "Queue")
    fun normalized(values: List<String>) = values.filter { value -> choices.any { it.first == value } }.distinct().take(8)
}

internal object SeekPreference {
    fun fast(value: String) = value == "fast"
    fun flags(value: String, precise: Boolean) = if (!precise && fast(value)) "absolute+keyframes" else "absolute+exact"
}

/** Defer optional processing until a known decoded track is available. */
internal object AudioProcessingPolicy {
    private val encoded=setOf("audio/ac3","audio/eac3","audio/eac3-joc","audio/vnd.dts","audio/vnd.dts.hd","audio/true-hd")
    fun allowed(mime:String?,passthroughRequested:Boolean,bitstreamActive:Boolean,pcmFallback:Boolean) =
        mime!=null && !bitstreamActive && (!passthroughRequested || pcmFallback || mime !in encoded)
}
