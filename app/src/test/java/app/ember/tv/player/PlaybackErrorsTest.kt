package app.ember.tv.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import org.junit.Assert.*
import org.junit.Test

class PlaybackErrorsTest {
    @Test fun connectionFailuresAreRetriedButHttpStatusOnlyForLive() {
        assertTrue(PlaybackErrors.connection(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertFalse(PlaybackErrors.connection(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertTrue(PlaybackErrors.liveNetwork(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertTrue(PlaybackErrors.liveNetwork(PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        assertFalse(PlaybackErrors.liveNetwork(PlaybackException.ERROR_CODE_DECODING_FAILED))
    }

    @Test fun decoderAndOutputFailuresAreSeparate() {
        assertTrue(PlaybackErrors.decoding(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES))
        assertFalse(PlaybackErrors.decoding(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED))
        assertTrue(PlaybackErrors.audioOutput(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED))
        assertTrue(PlaybackErrors.malformedContainer(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
    }

    @Test fun linksWithoutAnExtensionStillNameTheirType() {
        assertEquals(MimeTypes.APPLICATION_M3U8, PlaybackErrors.mimeHint("https://live.test/ch/INDEX.M3U8?token=1"))
        assertEquals(MimeTypes.APPLICATION_M3U8, PlaybackErrors.mimeHint("http://xtream.test/live/u/p/17?output=m3u8"))
        assertEquals(MimeTypes.APPLICATION_M3U8, PlaybackErrors.mimeHint("https://cdn.test/play?type=hls&id=4"))
        assertEquals(MimeTypes.APPLICATION_MPD, PlaybackErrors.mimeHint("https://cdn.test/manifest.mpd"))
        assertNull(PlaybackErrors.mimeHint("http://xtream.test/live/u/p/17.ts"))
        assertNull(PlaybackErrors.mimeHint("https://cdn.test/movie.mkv"))
        // A playlist of channels is not a stream.
        assertNull(PlaybackErrors.mimeHint("http://xtream.test/get.php?username=u&type=m3u"))
        assertNull(PlaybackErrors.mimeHint("rtsp://cam.test/stream"))
    }

    @Test fun anUnreadableLinkThatNamedNoTypeIsRetriedAsHls() {
        val unsupported = PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
        assertTrue(PlaybackErrors.mayBeHls(unsupported, "https://live.test/c/abc/play/42"))
        assertFalse(PlaybackErrors.mayBeHls(unsupported, "https://live.test/42.m3u8"))
        assertFalse(PlaybackErrors.mayBeHls(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, "https://live.test/play/42"))
        assertFalse(PlaybackErrors.mayBeHls(unsupported, "rtmp://live.test/42"))
        assertFalse(PlaybackErrors.mayBeHls(unsupported, null))
    }

    @Test fun liveLinksSwapBetweenTsAndHls() {
        assertEquals("http://iptv.test/1.m3u8", PlaybackErrors.alternateLiveUrl("http://iptv.test/1.ts"))
        assertEquals("http://iptv.test/1.ts", PlaybackErrors.alternateLiveUrl("http://iptv.test/1.m3u8"))
        assertNull(PlaybackErrors.alternateLiveUrl("http://iptv.test/1.mpd"))
    }

    @Test fun liveAlternatePreservesQueryCredentialsFragmentsAndEncodedPath() {
        assertEquals("https://iptv.test/live/a%2Fb/1.m3u8?token=a%2Bb&expires=123#stream",
            PlaybackErrors.alternateLiveUrl("https://iptv.test/live/a%2Fb/1.ts?token=a%2Bb&expires=123#stream"))
        assertEquals("https://iptv.test/1.ts?auth=fixture",
            PlaybackErrors.alternateLiveUrl("https://iptv.test/1.M3U8?auth=fixture"))
    }

    @Test fun liveAlternateDoesNotRewriteQuerySuffixesOrUnsupportedSchemes() {
        assertNull(PlaybackErrors.alternateLiveUrl("https://iptv.test/play?file=1.ts"))
        assertNull(PlaybackErrors.alternateLiveUrl("file:///channel.ts"))
        assertNull(PlaybackErrors.alternateLiveUrl("not a stream"))
    }

    @Test fun formatsAreDescribedForErrorMessages() {
        assertEquals("", PlaybackErrors.describe(null))
        val video = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("dvhe.08.06").setWidth(3840).setHeight(2160).build()
        assertEquals("video HEVC dvhe.08.06 3840×2160", PlaybackErrors.describe(video))
        val sound = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3_JOC).setChannelCount(8).build()
        assertEquals("sound EAC3-JOC 8 ch", PlaybackErrors.describe(sound))
    }

    @Test fun clockShowsHoursOnlyWhenNeeded() {
        assertEquals("2:03", PlaybackErrors.clock(123_000))
        assertEquals("1:02:03", PlaybackErrors.clock(3_723_000))
    }
}
