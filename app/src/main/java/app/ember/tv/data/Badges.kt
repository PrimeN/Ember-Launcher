package app.ember.tv.data

import androidx.compose.runtime.mutableIntStateOf
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import okhttp3.Request
import java.io.File

/**
 * Source badges (Settings > Stremio > Badges): a JSON list of filters, each a regular
 * expression matched against the source's name and description, with an image (or colours for a
 * text tag) shown when it matches.
 *
 * Marker-based packs match invisible markers (U+2063, then seven bits written as
 * U+200B for 0 and U+200D for 1, then U+2063) that its AIOStreams formatter adds to each source.
 * When a source has no markers (another formatter, or another addon), Ember writes the same
 * markers itself from what it read from the source (resolution, quality, HDR, audio, channels,
 * languages), so the badges still show. Only the Best / Good / OK ranking needs AIOStreams' own
 * score, so those badges appear only when the source formatter supplies that ranking.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class BadgeFilter(
    val id: String = "",
    val name: String = "",
    val pattern: String = "",
    val isEnabled: Boolean = true,
    @JsonNames("imageUrl", "image") val imageURL: String? = null,
    val borderColor: String? = null,
    @JsonNames("backgroundColor", "background") val tagColor: String? = null,
    @JsonNames("color", "foregroundColor") val textColor: String? = null,
    val tagStyle: String? = null,
    val groupId: String? = null,
    @JsonNames("borderSize", "borderThickness")
    @Serializable(with = BadgeDimensionSerializer::class) val borderWidth: Float? = null,
    @JsonNames("cornerRadius")
    @Serializable(with = BadgeDimensionSerializer::class) val borderRadius: Float? = null,
    /** Optional render-time tint; the underlying artwork is kept unchanged. */
    val imageTint: String? = null,
)

@Serializable
data class BadgeGroup(
    val id: String = "",
    val name: String = "",
    val color: String? = null,
    val borderColor: String? = null,
)

@Serializable
data class BadgePack(
    val filters: List<BadgeFilter> = emptyList(),
    val groups: List<BadgeGroup> = emptyList(),
    /** Raw-text presets can opt into technical metadata; existing custom packs keep their input. */
    val matchParsedDetails: Boolean = false,
)

object Badges {
    /** "modern" (Detailed images), "legacy" (default text tags) or "custom" (your own JSON). */
    const val MODERN = "modern"
    const val LEGACY = "legacy"
    const val CUSTOM = "custom"

    /** The built-in Detailed preset, with every image bundled for offline use. */
    private const val MODERN_ASSET = "badges/modern.json"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private class Compiled(val filter: BadgeFilter, val regex: Regex, val parsedDetails: Boolean = false)

    /** The pack in use, compiled; empty for legacy or while loading. Read in composition, so screens redraw when it loads. */
    private var compiled by mutableStateOf<List<Compiled>>(emptyList())
    var message by mutableStateOf<String?>(null); private set

    /** Bumped whenever the pack changes. */
    var rev by mutableIntStateOf(0); private set

    private fun file(context: Context) = File(context.filesDir, "badges-custom.json")

    /** Loads the pack for [mode]: the built-in one, your custom one, or none (legacy). */
    suspend fun load(context: Context, mode: String) {
        val text = withContext(Dispatchers.IO) {
            when (mode) {
                MODERN -> runCatching { context.assets.open(MODERN_ASSET).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
                CUSTOM -> file(context).takeIf { it.exists() }?.readText()
                else -> null
            }
        }
        val ok = text != null && use(context, text)
        if (!ok) {
            compiled = emptyList()
            rev++
        }
        message = when {
            ok || mode == LEGACY -> null
            mode == CUSTOM && text == null -> "No custom badges yet. Add a pattern, copy the Detailed badges, load a pack from a link, or send one from the phone page."
            mode == CUSTOM && parse(text!!)?.filters?.none { it.isEnabled && it.pattern.isNotBlank() } == true -> "No custom badge is switched on yet. Add a pattern or switch one on."
            else -> "The badge pack could not be read; text tags are shown instead."
        }
    }

    /** Downloads a custom pack from [url] and keeps it. Returns an error message, or null when it worked. */
    suspend fun importUrl(context: Context, url: String): String? {
        val text = runCatching { fetch(url.trim()) }.getOrElse { return "Could not download it: ${it.message ?: "network error"}." }
        return importText(context, text)
    }

    /** Checks and keeps a custom pack (from a link or the phone page). Returns an error message, or null. */
    suspend fun importText(context: Context, text: String): String? {
        val pack = parse(text) ?: return "That is not a badge pack (a JSON file with \"filters\")."
        if (pack.filters.isEmpty()) return "That pack has no filters."
        withContext(Dispatchers.IO) { AtomicFile.write(file(context), text) }.onFailure { return "Could not save the pack on this TV." }
        return null
    }

    fun hasCustom(context: Context) = file(context).exists()

    /** #236/#237: your custom pack as saved, with an id on every filter, or null when there is none. */
    suspend fun customPack(context: Context): BadgePack? = withContext(Dispatchers.IO) {
        file(context).takeIf { it.exists() }?.readText()?.let(::parse)?.let(BadgeEditor::withIds)
    }

    /** Keeps an edited custom pack. Returns an error message, or null when it worked. */
    suspend fun saveCustom(context: Context, pack: BadgePack): String? {
        val text = BadgeEditor.encode(pack)
        withContext(Dispatchers.IO) { AtomicFile.write(file(context), text) }.onFailure { return "Could not save the badges on this TV." }
        return null
    }

    /** Starts the custom pack from the Detailed badges, so they can be edited. */
    suspend fun copyDetailed(context: Context): String? {
        val text = withContext(Dispatchers.IO) { runCatching { context.assets.open(MODERN_ASSET).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() }
            ?: return "The Detailed badges could not be read."
        val pack = parse(text) ?: return "The Detailed badges could not be read."
        return saveCustom(context, BadgeEditor.withIds(pack))
    }

    /** A picture sent from the phone for a badge, kept on the TV. Returns its file link. */
    suspend fun saveIcon(context: Context, bytes: ByteArray, type: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, ICON_DIR).apply { mkdirs() }
            val name = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).take(10).joinToString("") { "%02x".format(it) } + "." + type
            val out = File(dir, name)
            if (!out.exists()) AtomicFile.write(out, bytes).getOrThrow()
            out.toURI().toString()
        }
    }

    /** The bytes of a badge picture kept on the TV (uploaded or bundled), for the phone page's preview. */
    fun iconBytes(context: Context, url: String?): Pair<ByteArray, String>? {
        val shown = local(context, url) ?: return null
        val bytes = runCatching {
            when {
                shown.startsWith("file:///android_asset/") -> context.assets.open(shown.removePrefix("file:///android_asset/")).use { it.readBytes() }
                shown.startsWith("file:") -> File(java.net.URI(shown)).takeIf { it.canonicalPath.startsWith(File(context.filesDir, ICON_DIR).canonicalPath + File.separator) }?.readBytes()
                else -> null
            }
        }.getOrNull() ?: return null
        val type = when (BadgeEditor.imageType(bytes)) { "png" -> "image/png"; "jpg" -> "image/jpeg"; "webp" -> "image/webp"; "gif" -> "image/gif"; else -> if (shown.endsWith(".svg")) "image/svg+xml" else return null }
        return bytes to type
    }

    /** Where a badge picture is shown from: bundled pictures from the APK, others as linked. */
    internal fun shownUrl(context: Context, url: String?): String? = local(context, url)

    private const val ICON_DIR = "badge-icons"

    /** Read a settings preview without replacing or recompiling the selected playback pack. */
    suspend fun preview(context: Context, mode: String): List<BadgeFilter> = withContext(Dispatchers.IO) {
        if (mode == LEGACY) return@withContext listOf("4K", "HDR10", "DV", "HEVC", "Atmos", "5.1", "English")
            .map { BadgeFilter(name = it) }
        val text = when (mode) {
            MODERN -> context.assets.open(MODERN_ASSET).bufferedReader().use { it.readText() }
            CUSTOM -> file(context).takeIf { it.exists() }?.readText()
            else -> null
        }
        text?.let(::parse)?.filters.orEmpty().filter { it.name.isNotBlank() }
            .map { it.copy(imageURL = local(context, it.imageURL)) }
    }

    /** Cancellable (leaving the screen stops the download) and bounded in size. */
    private suspend fun fetch(url: String): String = kotlinx.coroutines.coroutineScope {
        withResponse(Net.client(10, 30).newCall(Request.Builder().url(url).build())) { r ->
            withContext(Dispatchers.IO) {
                if (!r.isSuccessful) error("HTTP ${r.code}")
                r.body.boundedText(ResponseLimits.IMPORT)
            }
        }
    }

    internal fun parse(text: String): BadgePack? = runCatching { json.decodeFromString(BadgePack.serializer(), text) }.getOrNull()

    /** Compiles a pack; patterns Java cannot read are skipped. True when the pack was usable. */
    private suspend fun use(context: Context, text: String): Boolean {
        val pack = parse(text) ?: return false
        // Off the main thread: every pattern is compiled and tried once before it is used.
        val list = withContext(Dispatchers.Default) { pack.filters.filter { it.isEnabled && it.pattern.isNotBlank() }.mapNotNull { f ->
            runCatching { Regex(f.pattern) }.getOrNull()?.takeIf(BadgePatterns::safe)?.let { Compiled(f.copy(imageURL = local(context, f.imageURL)), it, pack.matchParsedDetails) }
        } }
        if (list.isEmpty()) return false
        compiled = list
        message = null
        rev++
        return true
    }

    /** Bundled images are read from the APK instead of the web, including custom packs using the same URLs. */
    private fun local(context: Context, url: String?): String? {
        if (url == null) return null
        val path = BundledBadgeImages.path(url) ?: return url
        val there = runCatching { context.assets.open(path).close(); true }.getOrDefault(false)
        return if (there) "file:///android_asset/$path" else url
    }

    /** Results per source for the pack in use, so scrolling a long source list does not run every pattern again. */
    private val cache = java.util.Collections.synchronizedMap(java.util.WeakHashMap<StreamOption, Pair<Int, List<BadgeFilter>>>())

    /** The badges for a source, in the pack's order. Empty in legacy mode or when nothing matches. */
    fun forSource(s: StreamOption): List<BadgeFilter> {
        val list = compiled
        if (list.isEmpty()) return emptyList()
        val r = rev
        cache[s]?.takeIf { it.first == r }?.let { return it.second }
        return match(s, list).also { cache[s] = r to it }
    }

    private fun match(s: StreamOption, list: List<Compiled>): List<BadgeFilter> {
        val info = s.info
        val raw = (info?.let { it.name + "\n" + it.description } ?: (s.name + "\n" + s.title))
        val marked = if (MARK in raw || info == null) raw else raw + "\n" + markers(info)
        val text = if (list.firstOrNull()?.parsedDetails == true) marked + "\n" + technicalDetails(s) else marked
        return list.filter { it.regex.containsMatchIn(text) }.map { it.filter }
    }

    /** Add-ons may expose formats only as structured fields; servers use the StreamOption fields. */
    private fun technicalDetails(s: StreamOption): String = s.info?.let { i ->
        (listOfNotNull(i.quality, i.resolution, i.encode, i.network) + i.visualTags + i.audioTags + i.audioChannels + i.languages).joinToString(" ")
    } ?: listOf(when (s.quality) { "FHD" -> "1080p"; "HD" -> "720p"; else -> s.quality },
        s.hdr, s.codec, s.audio, s.source).joinToString(" ")

    /** For tests: the names of the badges [packJson] gives [s], without any Android parts. */
    internal fun namesFor(packJson: String, s: StreamOption): List<String> {
        val pack = parse(packJson) ?: return emptyList()
        val list = pack.filters.filter { it.isEnabled && it.pattern.isNotBlank() }.mapNotNull { f ->
            runCatching { Regex(f.pattern) }.getOrNull()?.takeIf(BadgePatterns::safe)?.let { Compiled(f, it, pack.matchParsedDetails) }
        }
        return match(s, list).map { it.name }
    }

    /** Text shown on screen without the invisible markers. */
    fun clean(text: String): String = if (MARK in text) text.replace(MARKERS, "") else text

    /** Only whole markers, so zero-width joiners inside emoji are left alone. */
    private val MARKERS = Regex("\\u2063[\\u200b\\u200d]*\\u2063")

    // ---- compatible formatter marker encoding ----

    private const val MARK = '⁣'

    private fun code(bits: String) = buildString {
        append(MARK)
        bits.forEach { append(if (it == '0') '​' else '‍') }
        append(MARK)
    }

    private fun markers(i: StreamInfo): String = buildString {
        fun has(list: List<String>, x: String) = list.any { it.contains(x, ignoreCase = true) }
        when (i.quality) {
            "BluRay REMUX" -> append(code("0000000"))
            "BluRay" -> append(code("0000001"))
            "WEB-DL" -> append(code("0000010"))
        }
        when (i.resolution) {
            "2160p" -> append(code("0001100"))
            "1080p" -> append(code("0001101"))
            "720p" -> append(code("0001110"))
        }
        val v = i.visualTags
        val a = i.audioTags
        if (has(v, "SDR")) append(code("0001111"))
        if (has(a, "DTS:X")) append(code("0011010"))
        if (has(a, "DTS-HD MA")) append(code("0011011"))
        if (has(a, "DTS-HD")) append(code("0011100"))
        if (has(a, "DTS")) append(code("0011101"))
        if (has(v, "HDR10+")) append(code("0010010"))
        if (has(v, "HDR10")) append(code("0010001"))
        if (has(v, "HDR")) append(code("0010000"))
        if (has(v, "IMAX")) append(code("0010100"))
        val file = i.filename.orEmpty().lowercase()
        if (Regex("imax[ ._-]?enhanced").containsMatchIn(file)) append(code("0010101"))
        if (has(v, "DV")) append(code("0010011"))
        if (has(a, "Atmos")) append(code("0010110"))
        if (has(a, "TrueHD")) append(code("0010111"))
        if (has(a, "DD+")) append(code("0011000"))
        if (has(a, "DD")) append(code("0011001"))
        val ch = i.audioChannels
        if (has(ch, "7.1")) append(code("0100000"))
        if (has(ch, "6.1")) append(code("0011111"))
        if (has(ch, "5.1")) append(code("0011110"))
        LANGUAGES.forEachIndexed { n, lang ->
            if (i.languages.any { it.equals(lang, ignoreCase = true) }) append(code(Integer.toBinaryString(0b0110001 + n).padStart(7, '0')))
        }
        if (i.languages.any { it == "Multi" || it == "Dual Audio" }) append(code("1001101"))
    }

    /** Compatible language marker order: English is 0110001, the next ones count up from there. */
    private val LANGUAGES = listOf(
        "English", "Spanish", "French", "German", "Italian", "Portuguese", "Portuguese (Brazil)", "Russian", "Chinese",
        "Japanese", "Korean", "Dutch", "Swedish", "Norwegian", "Danish", "Finnish", "Polish", "Arabic", "Hindi", "Turkish",
        "Greek", "Hungarian", "Czech", "Ukrainian", "Romanian", "Bulgarian", "Vietnamese", "Thai",
    )

    /** Eight-digit colours use Android ARGB; also accept short hex and transparency. */
    fun color(hex: String?): Int? {
        val raw = hex?.trim() ?: return null
        if (raw.equals("transparent", ignoreCase = true)) return 0
        val short = raw.removePrefix("#")
        val h = if (short.length in 3..4) short.flatMap { listOf(it, it) }.joinToString("") else short
        if (h.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
        val v = h.toLongOrNull(16) ?: return null
        return when (h.length) {
            6 -> (0xFF000000 or v).toInt()
            8 -> v.toInt()
            else -> null
        }
    }
}


/**
 * Badge patterns come from downloaded packs and run against every source name. Android's regex
 * engine (ICU) cannot be interrupted, so instead of timing a pattern Ember refuses the shapes that
 * backtrack catastrophically: a repeated group that itself contains a repetition, such as (a+)+,
 * (\w+\s?)+ or (.*a){12}. Very long patterns are refused too. That tag then falls back to text.
 */
internal object BadgePatterns {
    const val MAX_LENGTH = 2_000

    fun safe(regex: Regex): Boolean = regex.pattern.length <= MAX_LENGTH && !nestedRepetition(regex.pattern)

    internal fun nestedRepetition(pattern: String): Boolean {
        val groups = ArrayDeque<Boolean>() // per open group: does it contain a repetition?
        var inClass = false
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> { i += 2; continue }
                inClass -> if (c == ']') inClass = false
                c == '[' -> inClass = true
                c == '(' -> groups.addLast(false)
                c == ')' -> {
                    val repeatsInside = groups.removeLastOrNull() ?: false
                    if (repeatsInside && pattern.getOrNull(i + 1) in setOf('+', '*', '{')) return true
                    if (repeatsInside && groups.isNotEmpty()) groups[groups.lastIndex] = true
                }
                c == '+' || c == '*' || c == '{' -> if (groups.isNotEmpty()) groups[groups.lastIndex] = true
            }
            i++
        }
        return false
    }
}
