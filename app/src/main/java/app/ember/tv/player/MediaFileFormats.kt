package app.ember.tv.player

import java.net.URI
import java.util.Locale

/** File discovery and initial routing share one registry. A suffix is a hint, not proof of decoding. */
internal object MediaFileFormats {
    private val video = setOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "wmv", "ts", "mts", "m2ts",
        "mpg", "mpeg", "mpe", "m2v", "m1v", "vob", "flv", "f4v", "3gp", "3g2", "ogv", "asf",
        "rm", "rmvb", "divx", "mxf", "mjpeg", "mjpg", "y4m", "hevc", "h265", "265", "h264", "264",
        "avc", "vvc", "h266", "266", "apv", "ivf", "ogm", "wtv", "dvr-ms")
    private val audio = setOf("mp3", "mp2", "mp1", "m4a", "m4b", "aac", "flac", "alac", "wav", "wave",
        "ogg", "oga", "opus", "ac3", "eac3", "ec3", "dts", "dtshd", "truehd", "thd", "mlp", "mka",
        "aiff", "aif", "aifc", "ape", "wma", "wv", "tta", "tak", "amr", "au", "snd", "caf",
        "dsf", "dff", "spx", "mhm", "mha", "iamf")
    private val native = setOf("wmv", "asf", "rm", "rmvb", "vob", "divx", "ogv", "ogm", "mxf",
        "mjpeg", "mjpg", "y4m", "hevc", "h265", "265", "h264", "264", "avc", "vvc", "h266", "266",
        "apv", "ivf", "wtv", "dvr-ms", "aif", "aiff", "aifc", "caf", "dsf", "dff", "tak", "wv")
    private val rawStreams = setOf("hevc", "h265", "265", "h264", "264", "avc", "vvc", "h266", "266")
    fun extension(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')
        .substringAfterLast('.', "").lowercase(Locale.ROOT)
    fun media(name: String, mime: String): Boolean = mime.substringBefore(';').trim().lowercase(Locale.ROOT).let {
        it.startsWith("video/") || it.startsWith("audio/")
    } || extension(name) in video || extension(name) in audio
    fun nativeFirst(uri: String, filename: String? = null): Boolean {
        val parsed = runCatching { URI(uri) }.getOrNull()
        if (parsed?.scheme?.lowercase(Locale.ROOT) in setOf("rtmps", "srtp", "rtmp", "rtp", "udp", "tcp")) return true
        // A release title such as "Movie.2020.WEB.H.264" is not a raw stream file: only containers count from names.
        val known = filename?.let(::extension)?.takeIf { (it in video || it in audio) && it !in rawStreams }
        return (known ?: parsed?.path?.let(::extension)) in native
    }
}
