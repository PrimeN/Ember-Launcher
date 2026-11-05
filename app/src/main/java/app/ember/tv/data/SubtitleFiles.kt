package app.ember.tv.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Untrusted subtitle files have bounded reads and deterministic episode/language matching. */
object SubtitleFiles {
    const val MAX_DOWNLOAD = 8 * 1024 * 1024
    const val MAX_EXPANDED = 32 * 1024 * 1024
    const val MAX_ENTRIES = 128
    data class Target(val release: String? = null, val season: Int? = null, val episode: Int? = null, val language: String? = null)
    fun read(input: InputStream, limit: Int = MAX_DOWNLOAD): ByteArray {
        val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size() + 1))
            if (n < 0) return out.toByteArray()
            check(out.size().toLong() + n <= limit) { "Subtitle exceeds size limit." }
            out.write(buffer, 0, n)
        }
    }
    fun isZip(bytes: ByteArray) = bytes.size >= 4 && bytes[0] == 80.toByte() && bytes[1] == 75.toByte()
    private val episode = Regex("(?i)(?:s(\\d{1,3})[ ._-]*e(\\d{1,4})|(\\d{1,3})x(\\d{1,4}))(?!\\d)")
    private fun episodeOf(name: String): Pair<Int,Int>? = episode.find(name)?.let {
        (it.groupValues[1].toIntOrNull() ?: it.groupValues[3].toInt()) to (it.groupValues[2].toIntOrNull() ?: it.groupValues[4].toInt())
    }
    private val wordBreak = Regex("[^\\p{L}\\p{N}]+")
    private val ttmlRoot = Regex("<(?:\\w+:)?tt(?:\\s|>)")
    private val microDvdHead = Regex("^\\{\\d+}\\{\\d+}")
    private val microDvdRow = Regex("^\\{(\\d+)}\\{(\\d+)}(.*)$")
    private fun words(text: String?) = text.orEmpty().lowercase().split(wordBreak).filter { it.length > 1 }.toSet()
    private fun languages(value:String?):Set<String> {
        val base=value.orEmpty().lowercase().substringBefore('-').substringBefore('_')
        if(base.isEmpty())return emptySet()
        val locale=java.util.Locale.forLanguageTag(base)
        val extra=mapOf("fr" to "fre","de" to "ger","nl" to "dut","zh" to "chi","cs" to "cze","el" to "gre","ro" to "rum","sk" to "slo")
        return setOfNotNull(base,runCatching {locale.isO3Language}.getOrNull(),extra[base],locale.getDisplayLanguage(java.util.Locale.ENGLISH).lowercase()).filter {it.isNotEmpty()}.toSet()
    }
    fun archive(bytes: ByteArray, target: Target = Target()): Pair<String,ByteArray>? {
        require(bytes.size <= MAX_DOWNLOAD) { "Subtitle archive exceeds size limit." }
        val files = mutableListOf<Pair<String,ByteArray>>(); var total = 0; var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                check(++count <= MAX_ENTRIES) { "Too many subtitle archive entries." }
                if (!entry.isDirectory) {
                    val data = read(zip, minOf(MAX_DOWNLOAD, MAX_EXPANDED - total)); total += data.size
                    if (entry.name.substringAfterLast('.').lowercase() in setOf("srt","vtt","ass","ssa","ttml","xml","sub","smi")) files += entry.name to data
                }
                zip.closeEntry()
            }
        }
        val wanted = if (target.season != null && target.episode != null) target.season to target.episode else episodeOf(target.release.orEmpty())
        val eligible = if (wanted != null && files.any { episodeOf(it.first) != null }) files.filter { episodeOf(it.first) == wanted } else files
        val aliases=languages(target.language)
        val matching=eligible.filter {(words(it.first) intersect aliases).isNotEmpty()}
        val pool=matching.ifEmpty {eligible}
        val tokens = words(target.release)
        return pool.sortedWith(compareByDescending<Pair<String,ByteArray>> { (words(it.first) intersect tokens).size }
            .thenByDescending { (words(it.first) intersect aliases).isNotEmpty() }.thenBy { it.first.lowercase() }).firstOrNull()
    }
    fun format(name: String, bytes: ByteArray): String {
        val ext = name.substringBefore('?').substringAfterLast('.').lowercase()
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.UTF_8).trimStart('\uFEFF',' ','\n','\r')
        return when {
            head.startsWith("WEBVTT") || ext == "vtt" -> "vtt"
            head.contains("[Script Info]", true) || ext in setOf("ass","ssa") -> "ass"
            ext in setOf("ttml","dfxp") || ttmlRoot.containsMatchIn(head) -> "ttml"
            microDvdHead.containsMatchIn(head) -> "microdvd"
            bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 1.toByte() -> "bitmap"
            ext == "srt" || head.contains("-->") -> "srt"
            else -> "unknown"
        }
    }
    fun bom(bytes: ByteArray): String? = when {
        bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> String(bytes,2,bytes.size-2,Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> String(bytes,2,bytes.size-2,Charsets.UTF_16BE)
        else -> null
    }
    /** Frame-number subtitles require an explicit header or a known video frame rate. */
    fun microDvd(text: String, videoFps: Float?): String? {
        val rows = text.lineSequence().mapNotNull { microDvdRow.matchEntire(it.trim()) }.toList()
        val header = rows.firstOrNull()?.takeIf { it.groupValues[1] in setOf("0","1") && it.groupValues[2] in setOf("0","1") && it.groupValues[3].toDoubleOrNull() != null }
        val fps = (header?.groupValues?.get(3)?.toDoubleOrNull() ?: videoFps?.toDouble())?.takeIf { it.isFinite() && it in 1.0..240.0 } ?: return null
        fun time(frame: Long): String { val ms = (frame * 1000.0 / fps).toLong(); return "%02d:%02d:%02d,%03d".format(java.util.Locale.ROOT,ms/3600000,ms/60000%60,ms/1000%60,ms%1000) }
        val content=rows.filter {it !== header}
        if(content.any {it.groupValues[1].toLongOrNull()==null || it.groupValues[2].toLongOrNull()==null})return null
        return content.mapIndexed { i,m -> "${i+1}\n${time(m.groupValues[1].toLong())} --> ${time(m.groupValues[2].toLong())}\n${m.groupValues[3].replace('|','\n')}\n" }.joinToString("\n")
    }
}
