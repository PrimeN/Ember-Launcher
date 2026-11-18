package app.ember.tv

import app.ember.tv.data.Subtitles
import org.junit.Assert.*
import org.junit.Test

class TraditionalSubtitleEncodingTest {
    @Test fun traditionalTagsUseBig5AfterUtf8Validation() {
        val text="繁體中文字幕"
        val bytes=text.toByteArray(charset("Big5"))
        for(tag in listOf("zh-TW","zh-Hant","zh-Hant-TW","ZH_tw"))assertEquals(tag,text,Subtitles.decode(bytes,tag))
        assertEquals(text,Subtitles.decode(text.toByteArray(),"zh-TW"))
        assertEquals("zh-TW",Subtitles.normalise("zh-Hant"))
        assertEquals("zh-TW",Subtitles.normalise("zh_Hant_TW"))
    }
    @Test fun simplifiedChineseAndExplicitEncodingsKeepTheirOwnDecoder() {
        val simplified="简体中文字幕"
        assertEquals(simplified,Subtitles.decode(simplified.toByteArray(charset("GBK")),"zh"))
        assertEquals(simplified,Subtitles.decode(simplified.toByteArray(charset("GBK")),"zh-CN"))
        val text="繁體中文字幕"
        assertEquals(text,Subtitles.decode(byteArrayOf(0xff.toByte(),0xfe.toByte())+text.toByteArray(Charsets.UTF_16LE),"zh-TW"))
    }
}
