package app.ember.tv.player

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Clock
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.text.TextOutput
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegTextRenderer

internal fun authoredAssEnabled(authoredStyles: Boolean, systemStyle: Boolean): Boolean =
    authoredStyles && !systemStyle

@OptIn(UnstableApi::class)
internal fun nativeAssAvailable(): Boolean = runCatching { FfmpegLibrary.isAvailable() }.getOrDefault(false)

/** Renders an external script from the video clock without replacing the video source. */
@OptIn(UnstableApi::class)
internal class AssOverlay(context: Context, script: String, output: TextOutput, private val onFailure: () -> Unit) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("ember-ass").apply { start() }
    private val worker = Handler(thread.looper)
    private val frameToken = Any()
    @Volatile private var closed = false
    private var renderer: FfmpegTextRenderer? = null
    private var scriptStream: AssScriptStream? = null
    private var previousUs = Long.MIN_VALUE
    private var initializationPolls = 0

    init {
        val app = context.applicationContext
        worker.post {
            if (closed) return@post
            useRenderer {
                val format = Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).setSubsampleOffsetUs(0).build()
                val stream = AssScriptStream(format, script.toByteArray(Charsets.UTF_8))
                scriptStream = stream
                val text = FfmpegTextRenderer(app, object : TextOutput {
                    override fun onCues(cueGroup: CueGroup) {
                        if (!closed) output.onCues(cueGroup)
                    }
                }, main.looper)
                renderer = text
                text.init(0, PlayerId.UNSET, Clock.DEFAULT)
                text.enable(RendererConfiguration.DEFAULT, arrayOf(format), stream, 0, false, true,
                    0, 0, MediaSource.MediaPeriodId(frameToken))
                text.setCurrentStreamFinal()
                text.start()
            }
        }
    }

    fun draw(timeUs: Long, width: Int, height: Int) {
        if (closed) return
        worker.removeCallbacksAndMessages(frameToken)
        worker.postAtTime({ render(timeUs, Size(width, height)) }, frameToken, SystemClock.uptimeMillis())
    }

    private fun render(timeUs: Long, size: Size) {
        if (closed) return
        val text = renderer ?: return
        useRenderer {
            if (timeUs < previousUs) {
                scriptStream?.rewind()
                text.resetPosition(timeUs, false)
                initializationPolls = 0
            }
            previousUs = timeUs
            text.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT_RESOLUTION, size)
            text.render(timeUs, SystemClock.elapsedRealtime() * 1000L)
            // Allow asynchronous decoder initialization to finish while playback is paused.
            if (initializationPolls++ < 20 && !text.isEnded) {
                worker.postAtTime({ render(timeUs, size) }, frameToken, SystemClock.uptimeMillis() + 50)
            }
        }
    }

    private fun useRenderer(action: () -> Unit) {
        try {
            action()
        } catch (_: Exception) {
            main.post { if (!closed) onFailure() }
        } catch (_: LinkageError) {
            main.post { if (!closed) onFailure() }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        worker.removeCallbacksAndMessages(frameToken)
        worker.post {
            try {
                renderer?.let {
                    if (it.state == Renderer.STATE_STARTED) it.stop()
                    if (it.state == Renderer.STATE_ENABLED) it.disable()
                    it.release()
                }
            } finally {
                renderer = null
                thread.quitSafely()
            }
        }
    }
}

/** One complete ASS script at time zero. libass reads event timestamps from the script itself. */
@OptIn(UnstableApi::class)
internal class AssScriptStream(private val format: Format, private val script: ByteArray) : SampleStream {
    private var sentFormat = false
    private var sentScript = false

    fun rewind() { sentScript = false }

    override fun isReady() = true
    override fun maybeThrowError() = Unit
    override fun skipData(positionUs: Long) = 0

    override fun readData(holder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
        if (!sentFormat || readFlags and SampleStream.FLAG_REQUIRE_FORMAT != 0) {
            holder.format = format
            sentFormat = true
            return C.RESULT_FORMAT_READ
        }
        if (sentScript) {
            buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
        } else {
            buffer.timeUs = 0
            buffer.addFlag(C.BUFFER_FLAG_KEY_FRAME)
            if (readFlags and SampleStream.FLAG_OMIT_SAMPLE_DATA == 0) {
                buffer.ensureSpaceForWrite(script.size)
                buffer.data?.put(script)
            }
            if (readFlags and SampleStream.FLAG_PEEK == 0) sentScript = true
        }
        return C.RESULT_BUFFER_READ
    }
}
