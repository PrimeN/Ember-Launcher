package app.ember.tv.trailer

import app.ember.tv.data.Net
import android.media.MediaCodecList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.TimeUnit

/**
 * EXPERIMENTAL (Settings > Player): plays YouTube trailers inside Ember. NewPipe Extractor finds
 * the video's stream links; the built-in player plays the best video stream with the best audio
 * stream. YouTube changes often, so this can stop working until the extractor is updated; callers
 * fall back to the YouTube app.
 */
object YouTubeTrailers {
    /** A video stream and a separate audio stream (YouTube serves high quality as two streams). */
    data class Streams(val videoUrl: String, val audioUrl: String?, val label: String)

    private val client = Net.client(15, 30)

    /** NewPipe Extractor's network access, through OkHttp. */
    private object OkDownloader : Downloader() {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

        override fun execute(request: Request): Response {
            val body = request.dataToSend()?.toRequestBody()
            val b = okhttp3.Request.Builder().url(request.url()).method(request.httpMethod(), body)
            b.header("User-Agent", UA)
            request.headers().forEach { (name, values) ->
                b.removeHeader(name)
                values.forEach { b.addHeader(name, it) }
            }
            client.newCall(b.build()).execute().use { r ->
                if (r.code == 429) throw ReCaptchaException("YouTube asked for a captcha", request.url())
                return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
            }
        }
    }

    @Volatile private var ready = false

    private fun init() {
        if (ready) return
        synchronized(this) {
            if (!ready) {
                NewPipe.init(OkDownloader)
                ready = true
            }
        }
    }

    /** Hardware decoders on this device, by MIME type, so the stream picked can actually play. */
    private val decoders: Set<String> by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder }
            .flatMap { it.supportedTypes.toList() }
            .map { it.lowercase() }
            .toSet()
    }

    private fun canDecode(codec: String?): Boolean {
        val c = codec.orEmpty().lowercase()
        return when {
            c.startsWith("av01") -> "video/av01" in decoders
            c.startsWith("vp9") || c.startsWith("vp09") -> "video/x-vnd.on2.vp9" in decoders
            c.startsWith("avc") -> true
            else -> false
        }
    }

    /** Codec preference at the same resolution: AV1 and VP9 look better than H.264 at YouTube's bitrates. */
    private fun codecRank(codec: String?): Int {
        val c = codec.orEmpty().lowercase()
        return when {
            c.startsWith("av01") -> 3
            c.startsWith("vp9") || c.startsWith("vp09") -> 2
            c.startsWith("avc") -> 1
            else -> 0
        }
    }

    /** Finds playable streams for a YouTube video id, up to [maxHeight] (2160 for 4K). Call off the main thread. */
    suspend fun streams(videoId: String, maxHeight: Int = 2160): Streams = withContext(Dispatchers.IO) {
        init()
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")

        val video = info.videoOnlyStreams
            .filter { it.isUrl && it.height in 1..maxHeight && canDecode(it.codec) }
            .maxWithOrNull(compareBy({ it.height }, { it.fps }, { codecRank(it.codec) }, { it.bitrate }))
        val audio = info.audioStreams
            .filter { it.isUrl }
            .maxWithOrNull(compareBy({ it.averageBitrate }, { it.bitrate }))

        if (video != null && audio != null) {
            return@withContext Streams(video.content, audio.content, "${video.height}p ${video.codec.orEmpty().substringBefore('.')}")
        }
        // Fallback: a stream with sound built in (YouTube keeps these at 720p or lower).
        val muxed = info.videoStreams.filter { it.isUrl }.maxByOrNull { it.height }
            ?: error("No playable streams")
        Streams(muxed.content, null, "${muxed.height}p")
    }
}
