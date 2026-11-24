package app.ember.tv.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** #415: a health judgement, drawn as a coloured dot beside the row. NONE is information only. */
enum class StatsDot { NONE, GOOD, WARN, BAD }

/** Sections of the stream info panel, in display order. */
enum class StatsGroup(val title: String) { SOURCE("Source"), VIDEO("Video"), AUDIO("Audio"), NETWORK("Network"), SYSTEM("System") }

data class StatsRow(val group: StatsGroup, val label: String, val value: String, val dot: StatsDot = StatsDot.NONE)

/** Every threshold behind the panel's colours, in one place. */
internal object StatsThresholds {
    /** Dropped frames, underruns and load errors are red only when they rose this recently. */
    const val RECENT_MS = 30_000L
    const val BUFFER_GOOD_S = 10.0
    const val BUFFER_WARN_S = 3.0
    const val BUFFER_GRACE_MS = 10_000L
    const val SPEED_GOOD_RATIO = 2.0
    const val SPEED_WARN_RATIO = 1.0
    const val PING_GOOD_MS = 50L
    const val PING_WARN_MS = 150L
    const val CPU_GOOD = 30
    const val CPU_BAD = 60
    const val HEAP_GOOD = 0.60
    const val HEAP_BAD = 0.85
    const val TEMP_WARN_C = 75f
    const val TEMP_BAD_C = 95f
}

/**
 * Counts what the network delivers for the stream (#415): bytes for the link speed, the serving
 * host after redirects, and the file size from the first response (Content-Range, or the length of
 * a whole-file response). Lives on the stream's HTTP source; called on loader threads.
 */
internal class StreamTransferStats : TransferListener {
    val bytes = AtomicLong(0)
    @Volatile var host: String? = null
    @Volatile var port: Int = -1
    @Volatile var scheme: String? = null
    @Volatile var fileSize: Long = -1

    fun reset() {
        bytes.set(0); host = null; port = -1; scheme = null; fileSize = -1
    }

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}

    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (!isNetwork) return
        val uri: Uri? = runCatching { source.uri }.getOrNull() ?: dataSpec.uri
        uri?.host?.let { host = it; scheme = uri.scheme; port = uri.port }
        val headers = (source as? HttpDataSource)?.responseHeaders ?: return
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
        val total = header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
            ?: header("Content-Length")?.trim()?.toLongOrNull()?.takeIf { dataSpec.position == 0L }
        // Playlists and segments are small; only a media-sized answer is the file's size.
        if (total != null && total > MIN_FILE_BYTES && total > fileSize) fileSize = total
    }

    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        if (isNetwork) bytes.addAndGet(bytesTransferred.toLong())
    }

    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}

    private companion object { const val MIN_FILE_BYTES = 8L * 1024 * 1024 }
}

/** The values the panel keeps between one-second samples: counters' last rise, link speed, CPU. */
internal class StreamStatsState {
    var url: String? = null
    var dropped = -1; var droppedAt = 0L
    var underruns = 0; var underrunsSeen = -1; var underrunsAt = 0L
    var loadErrors = 0; var loadErrorsSeen = -1; var loadErrorsAt = 0L
    var lastBytes = -1L; var lastBytesAt = 0L; var speedBps: Long? = null
    @Volatile var pingMs: Long? = null
    var pingAt = 0L
    val cpu = ProcessCpu()

    fun newStream(url: String?) {
        if (url == this.url) return
        this.url = url
        dropped = -1; droppedAt = 0; underruns = 0; underrunsSeen = -1; underrunsAt = 0
        loadErrors = 0; loadErrorsSeen = -1; loadErrorsAt = 0; lastBytes = -1; lastBytesAt = 0; speedBps = null; pingMs = null; pingAt = 0
    }

    /** Green with none, red when it rose in the last 30 s, amber after that. */
    fun recency(total: Int, rose: Long, now: Long): StatsDot = when {
        total <= 0 -> StatsDot.GOOD
        now - rose <= StatsThresholds.RECENT_MS -> StatsDot.BAD
        else -> StatsDot.WARN
    }

    /** Link speed over the last tick, smoothed; only while the player is loading. */
    fun speed(bytes: Long, loading: Boolean, now: Long): Long? {
        if (loading && lastBytes in 0..bytes && lastBytesAt > 0) {
            val window = now - lastBytesAt
            if (window in 500..5_000) {
                val sample = (bytes - lastBytes) * 8_000 / window
                speedBps = speedBps?.let { (it * 0.7 + sample * 0.3).toLong() } ?: sample
            }
        }
        lastBytes = bytes
        lastBytesAt = now
        return speedBps
    }
}

/** This app's CPU use from /proc/self/stat, as a share of all cores. The hardware decoder's work isn't in it. */
internal class ProcessCpu {
    private var lastTicks = -1L
    private var lastAt = -1L
    private val hz = runCatching { android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK) }.getOrDefault(100L)
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    fun percent(): Int? {
        val ticks = runCatching {
            val f = File("/proc/self/stat").readText().substringAfterLast(')').trim().split(' ')
            f[11].toLong() + f[12].toLong()
        }.getOrNull() ?: return null
        val now = SystemClock.elapsedRealtime()
        val result = if (lastTicks >= 0 && now > lastAt) {
            val cpuMs = (ticks - lastTicks) * 1000.0 / hz
            ((cpuMs / ((now - lastAt).toDouble() * cores)) * 100).toInt().coerceIn(0, 100)
        } else null
        lastTicks = ticks
        lastAt = now
        return result
    }
}

/** Formatting and device readings for the panel; pure where it can be, so it can be tested. */
internal object StreamStatsFormat {
    fun mbps(bits: Long): String = String.format(Locale.US, "%.1f Mbit/s", bits / 1_000_000.0)

    fun bytes(n: Long): String = when {
        n >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", n / (1024.0 * 1024 * 1024))
        n >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", n / (1024.0 * 1024))
        else -> "${n / 1024} KB"
    }

    fun fps(rate: Float): String = String.format(Locale.US, "%.3f", rate).trimEnd('0').trimEnd('.')

    fun channels(count: Int): String? = when (count) {
        1 -> "mono"; 2 -> "2.0"; 6 -> "5.1"; 8 -> "7.1"
        else -> if (count > 0) "${count}ch" else null
    }

    /** "Profile 7.6 · BL + EL" from a Dolby Vision codecs string such as dvhe.07.06. */
    fun dolbyVision(codecs: String?): String? {
        val parts = codecs?.split('.') ?: return null
        if (parts.size < 3 || parts[0].lowercase() !in setOf("dvhe", "dvh1", "dav1", "dvav", "dva1")) return null
        val profile = parts[1].toIntOrNull() ?: return null
        val level = parts[2].toIntOrNull()
        val layers = when (profile) {
            4, 7 -> "BL + EL (dual layer)"
            5 -> "single layer · IPT"
            8 -> "single layer · HDR10 base"
            9 -> "single layer · SDR base"
            10 -> "single layer · AV1"
            else -> null
        }
        return listOfNotNull("Profile $profile" + (level?.let { ".$it" } ?: ""), layers).joinToString(" · ")
    }

    /**
     * HDR10 static metadata (CTA-861.3, as Media3 stores it): mastering primaries, peak and minimum
     * mastering brightness, and the content light levels. Null when the file carries none.
     */
    fun mastering(info: ByteArray?): String? {
        if (info == null || info.size < 25) return null
        val b = ByteBuffer.wrap(info).order(ByteOrder.LITTLE_ENDIAN)
        fun u16(at: Int) = b.getShort(at).toInt() and 0xFFFF
        val greenX = u16(5) * 0.00002
        val primaries = when {
            greenX == 0.0 -> null
            greenX in 0.16..0.18 -> "BT.2020"
            greenX in 0.25..0.28 -> "P3"
            greenX in 0.29..0.31 -> "BT.709"
            else -> null
        }
        val peak = u16(17)
        val min = u16(19) * 0.0001
        val cll = u16(21)
        val fall = u16(23)
        val light = if (cll > 0 || fall > 0) "CLL $cll · FALL $fall" else "CLL/FALL not set"
        val mdl = if (peak > 0) "MDL $peak nits" + (if (min > 0) String.format(Locale.US, " (min %.4f)", min) else "") else null
        return listOfNotNull(primaries, mdl, light).joinToString(" · ")
    }

    fun buffer(aheadS: Double, positionMs: Long, durationMs: Long, bufferedMs: Long): StatsDot = when {
        durationMs > 0 && durationMs - bufferedMs <= 5_000 -> StatsDot.GOOD
        positionMs < StatsThresholds.BUFFER_GRACE_MS -> StatsDot.NONE
        aheadS >= StatsThresholds.BUFFER_GOOD_S -> StatsDot.GOOD
        aheadS >= StatsThresholds.BUFFER_WARN_S -> StatsDot.WARN
        else -> StatsDot.BAD
    }

    fun speed(bps: Long, needed: Long?): StatsDot {
        if (needed == null || needed <= 0) return StatsDot.NONE
        val ratio = bps.toDouble() / needed
        return when {
            ratio >= StatsThresholds.SPEED_GOOD_RATIO -> StatsDot.GOOD
            ratio >= StatsThresholds.SPEED_WARN_RATIO -> StatsDot.WARN
            else -> StatsDot.BAD
        }
    }

    fun ping(ms: Long): StatsDot = when {
        ms <= StatsThresholds.PING_GOOD_MS -> StatsDot.GOOD
        ms <= StatsThresholds.PING_WARN_MS -> StatsDot.WARN
        else -> StatsDot.BAD
    }

    fun cpu(percent: Int): StatsDot = when {
        percent < StatsThresholds.CPU_GOOD -> StatsDot.GOOD
        percent < StatsThresholds.CPU_BAD -> StatsDot.WARN
        else -> StatsDot.BAD
    }

    fun heap(fraction: Double): StatsDot = when {
        fraction < StatsThresholds.HEAP_GOOD -> StatsDot.GOOD
        fraction < StatsThresholds.HEAP_BAD -> StatsDot.WARN
        else -> StatsDot.BAD
    }

    fun temperature(c: Float): StatsDot = when {
        c < StatsThresholds.TEMP_WARN_C -> StatsDot.GOOD
        c < StatsThresholds.TEMP_BAD_C -> StatsDot.WARN
        else -> StatsDot.BAD
    }

    /** The display runs at a whole multiple of the video's frame rate (24 fps on 24, 48, 120 Hz…). */
    fun refreshMatches(hz: Float, fps: Float): Boolean {
        if (hz <= 0 || fps <= 0) return false
        val ratio = hz / fps
        val whole = Math.round(ratio)
        return whole >= 1 && kotlin.math.abs(ratio - whole) < 0.01f * whole
    }

    /** The warmest readable thermal zone that names the SoC, in °C; null where Android hides them. */
    fun chipTemperature(): Float? = runCatching {
        File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }.orEmpty().mapNotNull { zone ->
            val type = runCatching { File(zone, "type").readText().trim().lowercase() }.getOrNull() ?: return@mapNotNull null
            if (listOf("cpu", "soc", "tsens", "mtktscpu", "a73", "a53", "little", "big", "core").none { it in type }) return@mapNotNull null
            val raw = runCatching { File(zone, "temp").readText().trim().toFloat() }.getOrNull() ?: return@mapNotNull null
            (if (raw > 1000) raw / 1000f else raw).takeIf { it in 1f..150f }
        }.maxOrNull()
    }.getOrNull()

    /** "2.40 GHz · cap 2.40 GHz": cpu0's current clock and the highest it may run now. */
    fun cpuClock(): Pair<String, Boolean>? = runCatching {
        fun read(name: String) = File("/sys/devices/system/cpu/cpu0/cpufreq/$name").readText().trim().toLong()
        val cur = read("scaling_cur_freq")
        val cap = runCatching { read("scaling_max_freq") }.getOrDefault(cur)
        val max = runCatching { read("cpuinfo_max_freq") }.getOrDefault(cap)
        String.format(Locale.US, "%.2f GHz · cap %.2f GHz", cur / 1e6, cap / 1e6) to (cap < max * 0.95)
    }.getOrNull()

    /** Where the sound goes: HDMI, HDMI eARC, Bluetooth, the TV's speakers… */
    fun route(context: Context): String? = runCatching {
        val am = context.getSystemService(AudioManager::class.java) ?: return null
        val devices = if (android.os.Build.VERSION.SDK_INT >= 33) {
            am.getAudioDevicesForAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
                .map { it.type }
        } else am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        val order = listOf(
            (if (android.os.Build.VERSION.SDK_INT >= 31) AudioDeviceInfo.TYPE_HDMI_EARC else -1) to "HDMI eARC",
            AudioDeviceInfo.TYPE_HDMI_ARC to "HDMI ARC",
            AudioDeviceInfo.TYPE_HDMI to "HDMI",
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to "Bluetooth",
            (if (android.os.Build.VERSION.SDK_INT >= 31) AudioDeviceInfo.TYPE_BLE_HEADSET else -1) to "Bluetooth LE",
            AudioDeviceInfo.TYPE_USB_DEVICE to "USB audio",
            AudioDeviceInfo.TYPE_USB_HEADSET to "USB headset",
            AudioDeviceInfo.TYPE_LINE_DIGITAL to "Optical / digital",
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES to "Headphones",
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to "TV speakers",
        )
        order.firstOrNull { it.first in devices }?.second
    }.getOrNull()

    /** TCP connect time to the serving host, in ms; null when it can't be reached. Call off the main thread. */
    fun ping(host: String, port: Int): Long? = runCatching {
        val start = SystemClock.elapsedRealtime()
        Socket().use { it.connect(InetSocketAddress(host, port), 2_000) }
        SystemClock.elapsedRealtime() - start
    }.getOrNull()

    fun hdrKind(format: Format, hdr10Plus: Boolean): String {
        val transfer = format.colorInfo?.colorTransfer
        return when {
            PlaybackFormats.dolbyVision(format.sampleMimeType, format.codecs) -> "Dolby Vision"
            hdr10Plus -> "HDR10+"
            transfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
            transfer == C.COLOR_TRANSFER_HLG -> "HLG"
            else -> "SDR"
        }
    }
}
