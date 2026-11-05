package app.ember.tv

import app.ember.tv.data.PlayPicker
import app.ember.tv.data.PlayRules
import app.ember.tv.data.StreamOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Play button's choice (Settings > Play button). */
class PlayPickerTest {
    private fun s(
        name: String,
        quality: String,
        hdr: String = "",
        audio: String = "",
        codec: String = "HEVC",
        size: Double? = 10.0,
        cached: Boolean = true,
        url: String? = "https://x/$name",
        server: String? = null,
    ) = StreamOption(
        addonName = if (server != null) "Plex" else "Addon",
        name = name, title = name, url = url, infoHash = null, fileIdx = null,
        quality = quality, hdr = hdr, codec = codec, audio = audio, source = "", releaseGroup = "",
        sizeGb = size, seeders = null, cached = cached, debrid = true, serverRef = server,
    )

    @Test fun highestQualityWithinTheCap() {
        val list = listOf(s("a", "FHD"), s("b", "4K"), s("c", "HD"))
        assertEquals("b", PlayPicker.pick(list, emptyList(), PlayRules())?.name)
        assertEquals("a", PlayPicker.pick(list, emptyList(), PlayRules(maxQuality = "FHD"))?.name)
    }

    @Test fun hdrPreference() {
        val list = listOf(s("dv", "4K", hdr = "DV + HDR10"), s("hdr", "4K", hdr = "HDR10"))
        assertEquals("dv", PlayPicker.pick(list, emptyList(), PlayRules(hdr = "dv"))?.name)
        assertEquals("hdr", PlayPicker.pick(list, emptyList(), PlayRules(hdr = "hdr"))?.name)
    }

    @Test fun bestSoundBreaksTies() {
        val list = listOf(s("aac", "4K", audio = "AAC"), s("atmos", "4K", audio = "Atmos"))
        assertEquals("atmos", PlayPicker.pick(list, emptyList(), PlayRules())?.name)
    }

    @Test fun rulesSkipSources() {
        val list = listOf(
            s("av1", "4K", codec = "AV1"),
            s("huge", "4K", size = 90.0),
            s("uncached", "4K", cached = false),
            s("ok", "FHD"),
        )
        // Un-cached debrid links count as not ready.
        assertEquals("ok", PlayPicker.pick(list, emptyList(), PlayRules(avoidCodecs = setOf("AV1"), maxSizeGb = 40))?.name)
        assertNull(PlayPicker.pick(listOf(s("t", "4K", url = null)), emptyList(), PlayRules()))
    }

    @Test fun serverFirst() {
        val addon = listOf(s("addon", "4K", hdr = "DV"))
        val server = listOf(s("plex", "4K", server = "plex|1|2"))
        assertEquals("plex", PlayPicker.pick(addon, server, PlayRules())?.name)
        assertEquals("addon", PlayPicker.pick(addon, server, PlayRules(serverFirst = false))?.name)
    }
}
