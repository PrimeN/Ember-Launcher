package app.ember.tv.data

import android.content.Context
import android.graphics.Typeface
import java.io.File

/**
 * Subtitle fonts (#219): a few of Android's own, and one custom font loaded from a link to a .ttf or
 * .otf file. The custom font is kept in Ember's own files; only one is kept at a time.
 */
object SubtitleFonts {
    const val DEFAULT = "default"; const val CUSTOM = "custom"
    val options = listOf(DEFAULT to "Default", "sans" to "Sans", "serif" to "Serif", "mono" to "Monospace", "condensed" to "Condensed", CUSTOM to "Custom")
    /** Larger than any reasonable subtitle font; also stops a wrong link downloading a film. */
    const val MAX_BYTES = 12L * 1024 * 1024

    fun normalize(font: String?): String = font?.takeIf { f -> options.any { it.first == f } } ?: DEFAULT
    fun file(context: Context): File = File(File(context.filesDir, "fonts"), "subtitle-custom.ttf")
    fun hasCustom(context: Context): Boolean = file(context).let { it.isFile && it.length() > 0 }

    /** The Android family behind each choice; the default stays Ember's bold sans. */
    fun systemFamily(font: String): String? = when (normalize(font)) { "sans" -> "sans-serif"; "serif" -> "serif"; "mono" -> "monospace"; "condensed" -> "sans-serif-condensed"; else -> null }

    fun typeface(context: Context, font: String): Typeface = when (normalize(font)) {
        DEFAULT -> Typeface.DEFAULT_BOLD
        CUSTOM -> runCatching { Typeface.createFromFile(file(context)) }.getOrNull() ?: Typeface.DEFAULT_BOLD
        else -> Typeface.create(systemFamily(font), Typeface.NORMAL)
    }

    /** A font file starts with one of these: TrueType, OpenType (CFF), Apple TrueType, or a collection. */
    fun looksLikeFont(head: ByteArray): Boolean {
        if (head.size < 4) return false
        val tag = head.copyOfRange(0, 4)
        return tag.contentEquals(byteArrayOf(0, 1, 0, 0)) || String(tag, Charsets.ISO_8859_1) in setOf("OTTO", "true", "ttcf")
    }

    /**
     * The font's family name from its "name" table (name 1), preferring the English one. Reads TTF, OTF
     * and the first face of a TTC collection (the one Android draws); every offset is checked first.
     */
    fun familyName(bytes:ByteArray):String?=runCatching {
        require(bytes.size in 12..MAX_BYTES.toInt())
        fun u16(at:Int):Int {require(at>=0 && at<=bytes.size-2);return ((bytes[at].toInt() and 255) shl 8) or (bytes[at+1].toInt() and 255)}
        fun u32(at:Int):Long=(u16(at).toLong() shl 16) or u16(at+2).toLong()
        fun offset(at:Int):Int=u32(at).also {require(it<=bytes.size)}.toInt()
        val face=if(u32(0)==0x74746366L) {
            require(u32(4) in setOf(0x00010000L,0x00020000L))
            val faces=u32(8);require(faces in 1..256 && 12+faces*4<=bytes.size)
            offset(12)
        } else 0
        require(face<=bytes.size-12 && u32(face) in setOf(0x00010000L,0x4F54544FL,0x74727565L))
        val tables=u16(face+4);require(tables in 1..4096 && face.toLong()+12+tables*16<=bytes.size)
        val table=(0 until tables).map {face+12+it*16}.firstOrNull {u32(it)==0x6E616D65L} ?: return null
        val name=offset(table+8);val length=u32(table+12)
        require(length>=6 && name.toLong()+length<=bytes.size)
        val end=(name+length).toInt();val format=u16(name);require(format in 0..1)
        val count=u16(name+2);val strings=name+u16(name+4)
        require(name.toLong()+6+count*12<=end && strings in (name+6+count*12)..end)
        fun text(at:Int,length:Int,unicode:Boolean):String? {
            if(at<strings || length<=0 || at.toLong()+length>end || unicode&&length%2!=0)return null
            return String(bytes,at,length,if(unicode)Charsets.UTF_16BE else runCatching {java.nio.charset.Charset.forName("x-MacRoman")}.getOrDefault(Charsets.ISO_8859_1))
                .trim().filterNot {it.isISOControl()}.take(100).takeIf {it.isNotBlank()}
        }
        fun taggedEnglish(language:Int):Boolean {
            if(format!=1 || language<0x8000)return false
            val tags=name+6+count*12
            if(tags>end-2)return false
            val index=language-0x8000;val n=u16(tags)
            if(index>=n || tags.toLong()+2+n*4>end)return false
            val record=tags+2+index*4
            val tag=text(strings+u16(record+2),u16(record),true) ?: return false
            return tag.equals("en",true) || tag.startsWith("en-",true)
        }
        (0 until count).mapNotNull {i->
            val record=name+6+i*12
            if(u16(record+6)!=1)return@mapNotNull null
            val platform=u16(record);val encoding=u16(record+2);val language=u16(record+4)
            val unicode=platform==0 || platform==3&&encoding in setOf(0,1,10)
            if(!unicode && !(platform==1&&encoding==0))return@mapNotNull null
            val value=text(strings+u16(record+10),u16(record+8),unicode) ?: return@mapNotNull null
            val priority=when {
                platform==3 && language<0x8000 && language and 0x3ff==9 || taggedEnglish(language)->0
                platform==1 && language==0->1
                platform==0->2
                platform==3->3
                else->4
            }
            priority to value
        }.minByOrNull {it.first}?.second
    }.getOrNull()

    /** Downloads the font at [url] and keeps it; the family name on success, or a message saying what went wrong. */
    fun save(context: Context, url: String, client: okhttp3.OkHttpClient): Result<String> = runCatching {
        val link = url.trim()
        require(link.startsWith("https://") || link.startsWith("http://")) { "Enter a link that starts with https://." }
        val request = okhttp3.Request.Builder().url(link).build()
        val bytes = client.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "The link didn't return a file (${resp.code})." }
            val body = resp.body ?: error("The link returned nothing.")
            check(body.contentLength() <= MAX_BYTES) { "That file is too large to be a font." }
            val out = java.io.ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    out.write(buffer, 0, n)
                    check(out.size() <= MAX_BYTES) { "That file is too large to be a font." }
                }
            }
            out.toByteArray()
        }
        check(looksLikeFont(bytes)) { "That link isn't a .ttf, .otf or .ttc font file." }
        val target = file(context)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "subtitle-custom.tmp")
        temp.writeBytes(bytes)
        // Android must be able to read it, or the player would fall back silently.
        runCatching { Typeface.createFromFile(temp) }.getOrNull() ?: run { temp.delete(); error("Android can't read that font.") }
        check(temp.renameTo(target) || run { target.delete(); temp.renameTo(target) }) { "Could not keep the font." }
        familyName(bytes) ?: "Custom font"
    }
}
