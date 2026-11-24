package app.ember.tv.player

import android.content.Context

/**
 * #431: player defaults set from this box and TV on first start: the buffer from its memory, frame
 * rate matching from the refresh rates the screen offers, and a note of what the sound link carries.
 */
object DeviceTuning {
    data class Facts(val ramMb: Long, val refreshRates: List<Float>, val surround: List<String>, val wired: Boolean)
    data class Plan(val config: PlaybackConfig, val lines: List<String>)

    private fun film(rate: Float) = listOf(23.976f, 24f, 47.952f, 48f).any { kotlin.math.abs(it - rate) < 0.05f }

    fun plan(f: Facts, c: PlaybackConfig): Plan {
        val gb = String.format(java.util.Locale.US, "%.1f GB", f.ramMb / 1024f)
        val buffer = when {
            f.ramMb in 1 until 1800 -> "normal"
            f.ramMb >= 3800 && f.wired -> "huge"
            else -> "large"
        }
        val rates = f.refreshRates.map { Math.round(it * 100) / 100f }.distinct().sorted()
        val frameRate = when {
            rates.size <= 1 -> "off"
            rates.any(::film) -> "always"
            else -> "seamless"
        }
        val rateText = rates.joinToString(", ") { r -> if (r % 1f == 0f) r.toInt().toString() else r.toString() }
        val lines = listOf(
            "Buffer: " + mapOf("normal" to "Normal", "large" to "Large", "huge" to "Huge")[buffer] + " ($gb memory" + (if (f.wired) ", wired network)" else ")"),
            "Frame rate: " + when (frameRate) {
                "off" -> "Off (the screen has one refresh rate: ${rateText.ifBlank { "unknown" }} Hz)"
                "always" -> "Match each video (the screen offers $rateText Hz)"
                else -> "Only seamless switches (no film rates among $rateText Hz)"
            },
            "Sound: " + if (f.surround.isEmpty()) "the TV reports no surround formats over HDMI, so sound is decoded here" else "sent untouched for ${f.surround.joinToString(", ")}",
        )
        return Plan(c.copy(buffer = buffer, frameRate = frameRate), lines)
    }

    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    fun facts(context: Context): Facts {
        val am = context.getSystemService(android.app.ActivityManager::class.java)
        val mem = android.app.ActivityManager.MemoryInfo().also { runCatching { am?.getMemoryInfo(it) } }
        val display = context.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val current = display?.mode
        val rates = display?.supportedModes.orEmpty()
            .filter { current == null || (it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight) }
            .map { it.refreshRate }
        val caps = runCatching { androidx.media3.exoplayer.audio.AudioCapabilities.getCapabilities(context, androidx.media3.common.AudioAttributes.DEFAULT, null) }.getOrNull()
        val surround = listOf(
            androidx.media3.common.C.ENCODING_AC3 to "Dolby Digital", androidx.media3.common.C.ENCODING_E_AC3 to "Dolby Digital+",
            androidx.media3.common.C.ENCODING_E_AC3_JOC to "Atmos", androidx.media3.common.C.ENCODING_DTS to "DTS",
            androidx.media3.common.C.ENCODING_DTS_HD to "DTS-HD", androidx.media3.common.C.ENCODING_DOLBY_TRUEHD to "TrueHD",
        ).filter { (e, _) -> caps?.supportsEncoding(e) == true }.map { it.second }
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val wired = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true }.getOrDefault(false)
        return Facts(mem.totalMem / (1024 * 1024), rates, surround, wired)
    }
}

/**
 * #431: a file whose length doesn't fit the title (a sample, trailer, fake or wrong episode).
 * Films: under 85% or over 140% of the running time (extended cuts stay well inside that).
 * Episodes: only clear mismatches, under 40% or over 250%, because shows' running times are rough.
 */
object FileLength {
    fun suspicious(durationMs: Long, runtimeMin: Int?, episode: Boolean): Boolean {
        if (durationMs <= 0 || runtimeMin == null || runtimeMin < 5) return false
        val ratio = durationMs / 60_000.0 / runtimeMin
        return if (episode) ratio < 0.4 || ratio > 2.5 else ratio < 0.85 || ratio > 1.4
    }

    fun minutes(ms: Long): String { val m = (ms / 60_000).toInt(); return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m" }
}
