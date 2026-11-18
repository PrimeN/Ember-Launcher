package app.ember.tv

import app.ember.tv.data.SubtitleFonts
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SubtitleFontNamesTest {
    private data class Name(val platform:Int=3,val language:Int=0x409,val value:String)
    private fun font(names:List<Name>,collection:Boolean=false,languageTag:String?=null):ByteArray {
        val face=if(collection)16 else 0
        val table=face+28
        val encoded=names.map {it.value.toByteArray(if(it.platform==1)charset("x-MacRoman") else Charsets.UTF_16BE)}
        val tag=languageTag?.toByteArray(Charsets.UTF_16BE) ?: byteArrayOf()
        val stringOffset=6+names.size*12+if(languageTag!=null)6 else 0
        val bytes=ByteArray(table+stringOffset+encoded.sumOf {it.size}+tag.size)
        val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if(collection){buffer.putInt(0x74746366);buffer.putInt(0x00010000);buffer.putInt(1);buffer.putInt(face)}
        buffer.position(face);buffer.putInt(0x00010000);buffer.putShort(1);buffer.putShort(0);buffer.putShort(0);buffer.putShort(0)
        buffer.putInt(0x6e616d65);buffer.putInt(0);buffer.putInt(table);buffer.putInt(bytes.size-table)
        buffer.position(table);buffer.putShort(if(languageTag!=null)1 else 0);buffer.putShort(names.size.toShort());buffer.putShort(stringOffset.toShort())
        var offset=0
        names.forEachIndexed {i,name->
            buffer.putShort(name.platform.toShort());buffer.putShort(if(name.platform==1)0 else 1);buffer.putShort(name.language.toShort());buffer.putShort(1)
            buffer.putShort(encoded[i].size.toShort());buffer.putShort(offset.toShort());offset+=encoded[i].size
        }
        if(languageTag!=null){buffer.putShort(1);buffer.putShort(tag.size.toShort());buffer.putShort(offset.toShort())}
        encoded.forEach(buffer::put);buffer.put(tag)
        return bytes
    }
    @Test fun englishFamilyWinsEvenWhenALocalizedNameAppearsFirst() {
        assertEquals("Example Family",SubtitleFonts.familyName(font(listOf(Name(language=0x404,value="範例字型"),Name(value="Example Family")))))
        assertEquals("British Family",SubtitleFonts.familyName(font(listOf(Name(language=0x411,value="日本語"),Name(language=0x809,value="British Family")))))
    }
    @Test fun aCollectionUsesAbsoluteTableOffsetsForItsFirstFace() {
        val bytes=font(listOf(Name(language=0x404,value="範例字型"),Name(value="Collection Family")),true)
        assertTrue(SubtitleFonts.looksLikeFont(bytes))
        assertEquals("Collection Family",SubtitleFonts.familyName(bytes))
    }
    @Test fun englishMacNamesBeatNonEnglishWindowsNamesAndFallbackIsStable() {
        assertEquals("Mac Family",SubtitleFonts.familyName(font(listOf(Name(language=0x404,value="範例字型"),Name(1,0,"Mac Family")))))
        assertEquals("範例字型",SubtitleFonts.familyName(font(listOf(Name(language=0x404,value="範例字型"),Name(language=0x411,value="日本語")))))
    }
    @Test fun languageTaggedUnicodeEnglishNamesAreRecognized() {
        assertEquals("Tagged Family",SubtitleFonts.familyName(font(listOf(Name(language=0x404,value="範例字型"),Name(0,0x8000,"Tagged Family")),languageTag="en-GB")))
        assertEquals("English Family",SubtitleFonts.familyName(font(listOf(Name(0,0,"Neutral Family"),Name(value="English Family")))))
    }
    @Test fun truncatedDirectoriesAndInvalidOffsetsAreRejectedWithoutReadingOutsideTheFont() {
        val valid=font(listOf(Name(value="Family")),true)
        for(size in listOf(0,4,12,16,28,valid.size-1))assertNull(SubtitleFonts.familyName(valid.copyOf(size)))
        val bad=valid.copyOf();ByteBuffer.wrap(bad).order(ByteOrder.BIG_ENDIAN).putInt(12,Int.MAX_VALUE)
        assertNull(SubtitleFonts.familyName(bad))
        val badTable=valid.copyOf();ByteBuffer.wrap(badTable).order(ByteOrder.BIG_ENDIAN).putInt(36,Int.MAX_VALUE)
        assertNull(SubtitleFonts.familyName(badTable))
    }
}
