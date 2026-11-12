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

    /** The font's family name from its "name" table (name 1), or null. Reads Windows (UTF-16) and Mac (Latin) records. */
    fun familyName(bytes: ByteArray): String? = runCatching {
        fun u16(at: Int) = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
        fun u32(at: Int) = (u16(at).toLong() shl 16) or u16(at + 2).toLong()
        val tables = u16(4)
        var name = -1
        for (i in 0 until tables) {
            val rec = 12 + i * 16
            if (String(bytes, rec, 4, Charsets.ISO_8859_1) == "name") { name = u32(rec + 8).toInt(); break }
        }
        if (name < 0) return null
        val count = u16(name + 2); val strings = name + u16(name + 4)
        var mac: String? = null
        for (i in 0 until count) {
            val r = name + 6 + i * 12
            if (u16(r + 6) != 1) continue
            val length = u16(r + 8); val offset = strings + u16(r + 10)
            when (u16(r)) {
                3, 0 -> return String(bytes, offset, length, Charsets.UTF_16BE).trim().takeIf { it.isNotEmpty() }
                1 -> if (mac == null) mac = String(bytes, offset, length, Charsets.ISO_8859_1).trim()
            }
        }
        mac?.takeIf { it.isNotEmpty() }
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
        check(looksLikeFont(bytes)) { "That link isn't a .ttf or .otf font file." }
        val target = file(context)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "subtitle-custom.tmp")
        temp.writeBytes(bytes)
        // Android must be able to read it, or the player would fall back silently.
        runCatching { Typeface.createFromFile(temp) }.getOrNull() ?: run { temp.delete(); error("Android can't read that font.") }
        check(temp.renameTo(target) || run { target.delete(); temp.renameTo(target) }) { "Couldn't keep the font." }
        familyName(bytes) ?: "Custom font"
    }
}
