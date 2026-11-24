package app.ember.tv.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks

/**
 * #410 (from Ember-Player): ASS subtitles move a title to Compatibility only when they really use
 * styling (positions, karaoke, fonts, animation, custom styles) and the move loses nothing else:
 * the video is not HDR or Dolby Vision, nothing is protected, and the audio plays the same there.
 * Plain ASS stays in Standard, where Ember draws it.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object SubtitleStyleProbe {
    private val tags = Regex("\\\\(?:pos|move|k[fo]?|t|i?clip|fade?|fr[xyz]?|fn|an|p)(?=[(0-9+\\-])", RegexOption.IGNORE_CASE)
    private val fontTag = Regex("""\\fn[^\\{}\r\n]+""", RegexOption.IGNORE_CASE)

    fun styled(text: String): Boolean {
        val sample = text.take(512 * 1024)
        if (tags.containsMatchIn(sample) || fontTag.containsMatchIn(sample)) return true
        return sample.lineSequence().filter { it.trimStart().startsWith("Style:", true) }.any { line ->
            val fields = line.substringAfter(':').split(',', limit = 4)
            fields.size >= 2 && (fields[0].trim().lowercase() != "default" || fields[1].trim().lowercase() !in setOf("arial", "sans-serif", "roboto"))
        }
    }

    /** A selected embedded ASS track whose header uses styling. */
    fun styledTrack(tracks: Tracks): Boolean = tracks.groups.any { group ->
        group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any { index ->
            val format = group.getTrackFormat(index)
            group.isTrackSelected(index) && format.sampleMimeType == MimeTypes.TEXT_SSA &&
                format.initializationData.any { styled(it.toString(Charsets.UTF_8)) }
        }
    }

    fun selected(tracks: Tracks, type: Int): Format? = tracks.groups.firstNotNullOfOrNull { group ->
        if (group.type != type) null else (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat)
    }

    /** SDR video, judged by what the file says: no PQ or HLG transfer and not Dolby Vision. */
    fun sdr(video: Format): Boolean =
        video.colorInfo?.colorTransfer !in setOf(C.COLOR_TRANSFER_ST2084, C.COLOR_TRANSFER_HLG) &&
            video.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION &&
            video.codecs?.lowercase()?.let { it.startsWith("dvh") || it.startsWith("dva") || it.startsWith("dav1") } != true

    fun routeToMpv(automatic: Boolean, authoredStyles: Boolean, systemStyle: Boolean, styled: Boolean, video: Format?, audio: Format?): Boolean =
        automatic && authoredStyles && !systemStyle && styled && video != null && sdr(video) && video.drmInitData == null &&
            PlaybackEnginePolicy.preservesAudio(audio?.sampleMimeType, audio?.drmInitData != null)
}
