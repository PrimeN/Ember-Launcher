package app.ember.tv.player

import android.graphics.Color
import android.graphics.Typeface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.ssa.SsaParser
import androidx.media3.extractor.text.subrip.SubripParser
import androidx.media3.extractor.text.ttml.TtmlParser
import app.ember.tv.data.SubtitleFiles
import androidx.media3.extractor.text.webvtt.WebvttParser
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import app.ember.tv.data.SubtitleConfig

/**
 * Shows an external subtitle file on top of the video with Ember's own timing, so it can be
 * shifted earlier or later while playing (Media3's own subtitle tracks cannot be shifted).
 * Files are parsed with Media3's SRT, WebVTT and SSA/ASS parsers.
 */
@OptIn(UnstableApi::class)
class SubtitleEngine(private val view: SubtitleView) {
    private var cues: List<CuesWithTiming> = emptyList()
    private var lastShown: List<Cue>? = null
    private var index = CueIndex<CuesWithTiming>(emptyList(), { it.startTimeUs }, { it.startTimeUs })
    var speed: Float = 1f
        set(value){field=SubtitleTiming.rate(value);lastShown=null}

    /** Positive shows subtitles later, negative earlier, in milliseconds. */
    var offsetMs: Long = 0
        set(v) {
            field = v
            lastShown = null
        }

    val active: Boolean get() = cues.isNotEmpty()

    /** Loads a subtitle; false if nothing could be read from it. */
    suspend fun load(text: String, format: String, fps: Float? = null): Boolean {
        val parsed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { parse(text,format,fps) } ?: return false
        if(parsed.first.isEmpty())return false
        cues=parsed.first;index=parsed.second;lastShown=null
        return cues.isNotEmpty()
    }

    private fun parse(text:String,format:String,fps:Float?):Pair<List<CuesWithTiming>,CueIndex<CuesWithTiming>>? {
        val source = if (format == "microdvd") SubtitleFiles.microDvd(text,fps) ?: return null else text
        val bytes = source.toByteArray(Charsets.UTF_8)
        val parser: SubtitleParser = when (format) {
            "vtt" -> WebvttParser()
            "ass" -> SsaParser()
            "ttml" -> TtmlParser()
            "srt", "microdvd" -> SubripParser()
            else -> return null
        }
        val out = ArrayList<CuesWithTiming>()
        runCatching {
            parser.parse(bytes, 0, bytes.size, SubtitleParser.OutputOptions.allCues()) { out += it }
        }
        val parsed = out.filter { it.cues.isNotEmpty() }.sortedBy { it.startTimeUs }
        val indexed = CueIndex(parsed, { it.startTimeUs }, { it.startTimeUs + if (it.durationUs == C.TIME_UNSET) 5_000_000 else it.durationUs })
        return parsed to indexed
    }

    fun clear() {
        cues = emptyList()
        index = CueIndex(emptyList(), { it.startTimeUs }, { it.startTimeUs })
        lastShown = null
        view.setCues(emptyList())
    }

    /** Shows the cues for [positionMs] of the video. Call every ~100 ms while playing. */
    fun render(positionMs: Long) {
        if (cues.isEmpty()) return
        val t = ((positionMs * speed - offsetMs) * 1000).toLong()
        val now = index.at(t).flatMap { it.cues }
        if (now != lastShown) {
            lastShown = now
            view.setCues(now)
        }
    }

    companion object {
        /** Applies Settings > Subtitles > Look to the player's subtitle view (Ember's and the video's own). */
        fun style(view: SubtitleView, c: SubtitleConfig) {
            val fg = if (c.color == "yellow") Color.rgb(255, 230, 80) else Color.WHITE
            // #219: the chosen font; the default stays bold sans.
            val face: Typeface = app.ember.tv.data.SubtitleFonts.typeface(view.context, c.font)
            val style = when (c.background) {
                "box" -> CaptionStyleCompat(fg, Color.argb(190, 0, 0, 0), Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_NONE, Color.BLACK, face)
                "shadow" -> CaptionStyleCompat(fg, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.BLACK, face)
                else -> CaptionStyleCompat(fg, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, face)
            }
            val system=view.context.getSystemService(android.content.Context.CAPTIONING_SERVICE) as? android.view.accessibility.CaptioningManager
            view.setStyle(if(c.systemStyle && system!=null)CaptionStyleCompat.createFromCaptionStyle(system.userStyle) else style)
            view.setApplyEmbeddedStyles(c.authoredStyles && !c.systemStyle)
            view.setFractionalTextSize(
                SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * (if(c.systemStyle)system?.fontScale?:1f else 1f) * when (c.size) {
                    "small" -> 0.8f
                    "large" -> 1.25f
                    "xlarge" -> 1.5f
                    else -> 1f
                },
            )
            view.setBottomPaddingFraction(
                when (c.position) {
                    "low" -> 0.03f
                    "high" -> 0.2f
                    else -> SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION
                },
            )
        }
    }
}
