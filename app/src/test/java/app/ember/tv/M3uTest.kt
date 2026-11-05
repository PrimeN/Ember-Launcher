package app.ember.tv

import app.ember.tv.iptv.IptvSource
import app.ember.tv.iptv.M3u
import org.junit.Assert.assertEquals
import org.junit.Test

class M3uTest {
    private val src = IptvSource(id = "s1", name = "Test", kind = "m3u", url = "http://example.com/list.m3u")

    @Test fun parsesChannelsAndGuide() {
        val text = """
            #EXTM3U url-tvg="http://example.com/guide.xml.gz"
            #EXTINF:-1 tvg-id="bbc1.uk" tvg-name="BBC One" tvg-logo="http://logo/bbc.png" group-title="UK",BBC One HD
            http://example.com/bbc1.m3u8
            #EXTINF:-1 tvg-chno="7",Channel, with comma
            #EXTGRP:News
            #EXTVLCOPT:http-user-agent=MyPlayer
            http://example.com/7.ts
        """.trimIndent()
        val p = M3u.parse(text, src)
        assertEquals("http://example.com/guide.xml.gz", p.epgUrl)
        assertEquals(2, p.channels.size)
        val a = p.channels[0]
        assertEquals("BBC One HD", a.name)
        assertEquals("UK", a.group)
        assertEquals("bbc1.uk", a.epgId)
        assertEquals("http://logo/bbc.png", a.logo)
        val b = p.channels[1]
        assertEquals("Channel, with comma", b.name)
        assertEquals("News", b.group)
        assertEquals(7, b.number)
        assertEquals("MyPlayer", b.userAgent)
    }
}
