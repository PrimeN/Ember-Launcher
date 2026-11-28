package app.ember.tv.player

import android.content.Context

/**
 * #431: player defaults set from this box and TV on first start: the buffer from its memory, frame
 * rate matching from the refresh rates the screen offers, and a note of what the sound link carries.
 */
object DeviceTuning {
    data class Facts(val ramMb: Long, val refreshRates: List<Float>, val surround: List<String>, val wired: Boolean,
        val device: String = "", val android: String = "", val hdr: List<String> = emptyList(), val uhd: Boolean = false)
    /** #474: one line of the setup card; [set] is false for what's decided as each video plays. */
    data class Row(val name: String, val value: String, val set: Boolean = true)
    data class Plan(val config: PlaybackConfig, val lines: List<String>, val device: String = "", val summary: String = "", val rows: List<Row> = emptyList())

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
        val bufferName = mapOf("normal" to "Normal", "large" to "Large", "huge" to "Huge")[buffer]
        val screen = listOfNotNull(if (f.uhd) "4K" else null, if (f.hdr.isNotEmpty()) "HDR" else null).joinToString(" ").ifEmpty { "HD" } + " TV" +
            (if ("Dolby Vision" in f.hdr) " with Dolby Vision" else "")
        val summary = listOfNotNull(
            if (f.ramMb > 0) "$gb memory" else null,
            f.android.takeIf { it.isNotBlank() }?.let { "Android $it" },
            screen,
            if (f.surround.isNotEmpty()) "sound system with ${f.surround.takeLast(2).joinToString(" and ")}" else null,
            if (f.wired) "wired network" else null,
        ).joinToString(" · ")
        val rows = listOf(
            Row("Buffer", "$bufferName · " + when (buffer) { "normal" -> "kept small for $gb of memory"; "huge" -> "plenty of memory and a wired network"; else -> "room for high-bitrate films" }),
            Row("Frame rate matching", when (frameRate) {
                "off" -> "Off · the screen has one refresh rate"
                "always" -> "Matches each video · $rateText Hz"
                else -> "Only seamless switches · no film rates"
            }),
            Row("Resolution matching", if (c.matchResolution) "On" else "Off · the box scales the picture"),
            Row("Surround sound", if (f.surround.isEmpty()) "Decoded on this box · no surround over HDMI" else "Sent untouched · ${f.surround.joinToString(", ")}"),
            Row(if ("Dolby Vision" in f.hdr) "Dolby Vision and HDR" else "HDR", "Decided for each video as it plays", set = false),
        )
        return Plan(c.copy(buffer = buffer, frameRate = frameRate), lines, f.device, summary, rows)
    }

    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    @Suppress("DEPRECATION")
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
        // #474: what the setup card says about this box and screen.
        val hdrTypes = runCatching { display?.hdrCapabilities?.supportedHdrTypes?.toList() }.getOrNull().orEmpty()
        val hdr = listOfNotNull(
            if (android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in hdrTypes) "Dolby Vision" else null,
            if (android.view.Display.HdrCapabilities.HDR_TYPE_HDR10 in hdrTypes) "HDR10" else null,
            if (android.view.Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in hdrTypes) "HDR10+" else null,
        )
        val uhd = display?.supportedModes.orEmpty().any { it.physicalWidth >= 3840 }
        val maker = android.os.Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = android.os.Build.MODEL.orEmpty()
        val device = if (model.startsWith(maker, ignoreCase = true) || maker.isBlank()) model else "$maker $model"
        return Facts(mem.totalMem / (1024 * 1024), rates, surround, wired, device, android.os.Build.VERSION.RELEASE.orEmpty(), hdr, uhd)
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
