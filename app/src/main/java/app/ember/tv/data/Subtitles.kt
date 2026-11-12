package app.ember.tv.data

import app.ember.tv.tracker.Http
import app.ember.tv.tracker.arr
import app.ember.tv.tracker.bool
import app.ember.tv.tracker.dbl
import app.ember.tv.tracker.int
import app.ember.tv.tracker.list
import app.ember.tv.tracker.long
import app.ember.tv.tracker.obj
import app.ember.tv.tracker.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** Settings > Subtitles. Saved as JSON in preferences (in backups). */
@Serializable
data class SubtitleConfig(
    /** Preferred languages, most wanted first (ISO 639-1, e.g. "en", "fr", "pt-BR"). Empty: the TV's language. */
    val languages: List<String> = emptyList(),
    /** Turn a subtitle on at start: "off", "always", "foreign" (only when the audio is not in my first language). */
    val autoSelect: String = "foreign",
    // ---- providers ----
    /** Stremio's OpenSubtitles addon: free, no key, matches by file hash. */
    val stremioOpenSubs: Boolean = true,
    /** OpenSubtitles.com REST API: needs an API key; a login raises the daily download limit. */
    val openSubsCom: Boolean = false,
    /** SubDL: needs a free API key. */
    val subdl: Boolean = false,
    /** Subtitles from your Stremio addons (as before). */
    val addonSubs: Boolean = true,
    // ---- matching ----
    /** Read 128 KB of the video to compute its OpenSubtitles hash, for subtitles made for this exact file. */
    val hashMatch: Boolean = true,
    /** Hearing-impaired subtitles: "include", "avoid", "prefer". */
    val hearingImpaired: String = "avoid",
    /** Leave out machine- and AI-translated subtitles. */
    val skipMachine: Boolean = true,
    /** Candidates kept per language. */
    val perLanguage: Int = 6,
    // ---- look ----
    /** Text size: "small", "normal", "large", "xlarge". */
    val size: String = "normal",
    /** "outline", "shadow", "box". */
    val background: String = "outline",
    /** "white", "yellow". */
    val color: String = "white",
    /** Distance from the bottom of the picture: "low", "normal", "high" (e.g. above a projector's masking). */
    val position: String = "normal",
    val authoredStyles: Boolean = true,
    val systemStyle: Boolean = false,
    /** #219: "default", "sans", "serif", "mono", "condensed", or "custom" (a font loaded from a link). */
    val font: String = "default",
    /** Where the custom font came from and its family name, for showing which one is loaded. */
    val customFontName: String = "",
)

/** One subtitle file Ember can show, with what is known about how well it fits the video. */
data class SubtitleCandidate(
    val provider: String,
    /** ISO 639-1 language code, e.g. "en". */
    val lang: String,
    /** Release name it was made for, if known. */
    val release: String?,
    /** Direct link, or null when it needs [resolve] first (OpenSubtitles.com download quota). */
    val url: String?,
    val hashMatch: Boolean = false,
    val hearingImpaired: Boolean = false,
    val machine: Boolean = false,
    val fps: Double? = null,
    val downloads: Int = 0,
    val trusted: Boolean = false,
    /** OpenSubtitles.com file id, resolved to a link on first use. */
    val fileId: Long? = null,
    /** SubDL and some providers serve zip files. */
    val zipped: Boolean = false,
    /** Filled by [Subtitles.rank]. */
    val score: Int = 0,
    val forced: Boolean = false,
) {
    val languageName: String get() = Subtitles.languageName(lang)
}

object Subtitles {
    // ---- languages ----

    /** ISO 639-1 → (ISO 639-2 for OpenSubtitles v3, English name). */
    private val LANGS = linkedMapOf(
        "en" to ("eng" to "English"), "fr" to ("fre" to "French"), "es" to ("spa" to "Spanish"),
        "de" to ("ger" to "German"), "it" to ("ita" to "Italian"), "pt" to ("por" to "Portuguese"),
        "pt-BR" to ("pob" to "Portuguese (Brazil)"), "nl" to ("dut" to "Dutch"), "pl" to ("pol" to "Polish"),
        "ru" to ("rus" to "Russian"), "uk" to ("ukr" to "Ukrainian"), "tr" to ("tur" to "Turkish"),
        "ar" to ("ara" to "Arabic"), "fa" to ("per" to "Persian"), "he" to ("heb" to "Hebrew"),
        "hi" to ("hin" to "Hindi"), "ja" to ("jpn" to "Japanese"), "ko" to ("kor" to "Korean"),
        "zh" to ("chi" to "Chinese"), "zh-TW" to ("zht" to "Chinese (traditional)"), "vi" to ("vie" to "Vietnamese"),
        "th" to ("tha" to "Thai"), "id" to ("ind" to "Indonesian"), "ms" to ("may" to "Malay"),
        "sv" to ("swe" to "Swedish"), "no" to ("nor" to "Norwegian"), "da" to ("dan" to "Danish"),
        "fi" to ("fin" to "Finnish"), "el" to ("ell" to "Greek"), "hu" to ("hun" to "Hungarian"),
        "cs" to ("cze" to "Czech"), "sk" to ("slo" to "Slovak"), "ro" to ("rum" to "Romanian"),
        "bg" to ("bul" to "Bulgarian"), "hr" to ("hrv" to "Croatian"), "sr" to ("scc" to "Serbian"),
        "sl" to ("slv" to "Slovenian"), "et" to ("est" to "Estonian"), "lv" to ("lav" to "Latvian"),
        "lt" to ("lit" to "Lithuanian"),
    )
    val LANGUAGE_CHOICES: List<Pair<String, String>> = LANGS.map { (k, v) -> k to v.second }

    private val alias = mapOf(
        "fra" to "fr", "deu" to "de", "nld" to "nl", "ces" to "cs", "slk" to "sk", "ron" to "ro", "fas" to "fa",
        "zho" to "zh", "msa" to "ms", "gre" to "el", "srp" to "sr", "pb" to "pt-BR", "ze" to "zh", "pt-pt" to "pt",
    )

    /** Normalises any language code or name an addon or service gives to ISO 639-1. */
    fun normalise(code: String?): String {
        val c = code.orEmpty().trim()
        if (c.isEmpty()) return "und"
        LANGS.keys.firstOrNull { it.equals(c, true) }?.let { return it }
        LANGS.entries.firstOrNull { it.value.first.equals(c, true) || it.value.second.equals(c, true) }?.let { return it.key }
        alias[c.lowercase()]?.let { return it }
        val two = c.lowercase().take(2)
        return LANGS.keys.firstOrNull { it == two } ?: c.lowercase()
    }

    fun languageName(code: String): String = LANGS[code]?.second ?: code.uppercase()

    /** The languages to look for: the user's list, or the TV's language (plus English when that is not English). */
    fun wanted(c: SubtitleConfig): List<String> {
        if (c.languages.isNotEmpty()) return c.languages
        val tv = normalise(java.util.Locale.getDefault().language)
        return if (tv == "en") listOf("en") else listOf(tv, "en")
    }

    // ---- OpenSubtitles file hash ----

    private val http = Net.client(10, 15).newBuilder().cache(null).build()

    /**
     * OpenSubtitles' movie hash: file size plus the 64-bit little-endian sums of the first and last
     * 64 KB. Read with two range requests, so a streamed file works too. Null if the server does not
     * allow ranges. Returns the hash and the file size.
     */
    suspend fun movieHash(url: String): Pair<String, Long>? = withContext(Dispatchers.IO) {
        runCatching {
            val chunk = 65536L
            fun range(from: Long, to: Long) = http.newCall(
                Request.Builder().url(url).header("Range", "bytes=$from-$to").header("User-Agent", "EmberTV/0.9").build(),
            ).execute()
            val (head, size) = range(0, chunk - 1).use { r ->
                if (r.code != 206) return@runCatching null
                val total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: return@runCatching null
                (r.body?.bytes() ?: return@runCatching null) to total
            }
            if (size < chunk * 2 || head.size < chunk) return@runCatching null
            val tail = range(size - chunk, size - 1).use { r -> if (r.code != 206) null else r.body?.bytes() } ?: return@runCatching null
            var hash = size
            for (b in listOf(head, tail)) {
                val buf = ByteBuffer.wrap(b, 0, chunk.toInt()).order(ByteOrder.LITTLE_ENDIAN)
                while (buf.remaining() >= 8) hash += buf.long
            }
            String.format("%016x", hash) to size
        }.getOrNull()
    }

    // ---- search ----

    /** What the subtitle should match. [id] is the Stremio id: tt123 for films, tt123:1:2 for episodes. */
    data class Query(
        val imdb: String?,
        val movie: Boolean,
        val season: Int?,
        val episode: Int?,
        val release: String?,
        val hash: String?,
        val size: Long?,
    ) {
        val stremioId: String? get() = imdb?.let { if (!movie && season != null && episode != null) "$it:$season:$episode" else it }
    }

    /** Asks every provider that is on, in parallel. Each one fails quietly. */
    suspend fun search(q: Query, c: SubtitleConfig, onResult: (List<SubtitleCandidate>) -> Unit = {}): List<SubtitleCandidate> = coroutineScope {
        val langs=wanted(c)
        suspend fun provider(fetch:suspend()->List<SubtitleCandidate>):List<SubtitleCandidate> {
            val result=try { fetch() } catch(e:kotlinx.coroutines.CancellationException) { throw e } catch(_:Exception) { emptyList() }
            kotlinx.coroutines.currentCoroutineContext().ensureActive();onResult(result);return result
        }
        listOfNotNull(
            if(c.stremioOpenSubs)async { provider { stremioOpenSubs(q,langs) } } else null,
            if(c.openSubsCom && ApiKeys.get("OPENSUBTITLES_API_KEY").isNotBlank())async { provider { openSubsCom(q,langs) } } else null,
            if(c.subdl && ApiKeys.get("SUBDL_API_KEY").isNotBlank())async { provider { subdl(q,langs) } } else null,
        ).awaitAll().flatten()
    }

    /** Stremio's OpenSubtitles v3 addon. With the file hash it lists subtitles made for this exact file first. */
    private suspend fun stremioOpenSubs(q: Query, langs: List<String>): List<SubtitleCandidate> {
        val id = q.stremioId ?: return emptyList()
        val type = if (q.movie) "movie" else "series"
        val extra = if (q.hash != null && q.size != null) "/videoHash=${q.hash}&videoSize=${q.size}" else ""
        val j = Http.get("https://opensubtitles-v3.strem.io/subtitles/$type/$id$extra.json")
        val all = j.arr("subtitles").list().mapIndexedNotNull { i, s ->
            val url = s.str("url") ?: return@mapIndexedNotNull null
            val lang = normalise(s.str("lang"))
            // The addon puts hash matches first and marks them; position is a fair hint either way.
            val hashHit = s.str("m") == "h" || s.bool("hashMatch") == true
            SubtitleCandidate("OpenSubtitles", lang, null, url, hashMatch = hashHit, downloads = 1000 - i)
        }
        return all.filter { it.lang in langs }
    }

    // ---- OpenSubtitles.com ----

    private const val OS = "https://api.opensubtitles.com/api/v1"
    @Volatile private var osToken: String? = null
    @Volatile private var osTokenUser: String? = null

    private fun osHeaders(): Map<String, String> = buildMap {
        put("Api-Key", ApiKeys.get("OPENSUBTITLES_API_KEY"))
        put("User-Agent", "EmberTV v0.9")
        put("Accept", "application/json")
        osToken?.let { put("Authorization", "Bearer $it") }
    }

    /** Logs in when a username and password are set; a login raises the daily download limit. */
    private suspend fun osLogin() {
        val user = ApiKeys.get("OPENSUBTITLES_USERNAME")
        val pass = ApiKeys.get("OPENSUBTITLES_PASSWORD")
        if (user.isBlank() || pass.isBlank()) return
        if (osToken != null && osTokenUser == user) return
        val body = buildJsonObject {
            put("username", user)
            put("password", pass)
        }
        Http.post("$OS/login", body, osHeaders()).str("token")?.let {
            osToken = it
            osTokenUser = user
        }
    }

    private suspend fun openSubsCom(q: Query, langs: List<String>): List<SubtitleCandidate> {
        val imdb = q.imdb?.removePrefix("tt")?.trimStart('0') ?: return emptyList()
        val params = buildList {
            add("languages=" + langs.map { it.lowercase() }.sorted().joinToString(","))
            if (q.hash != null) add("moviehash=${q.hash}")
            if (!q.movie && q.season != null && q.episode != null) {
                add("parent_imdb_id=$imdb")
                add("season_number=${q.season}")
                add("episode_number=${q.episode}")
            } else {
                add("imdb_id=$imdb")
            }
        }.sorted()
        val j = Http.get("$OS/subtitles?" + params.joinToString("&"), osHeaders())
        return j.arr("data").list().mapNotNull { d ->
            val a = d.obj("attributes")
            val file = a.arr("files").list().firstOrNull() ?: return@mapNotNull null
            SubtitleCandidate(
                provider = "OpenSubtitles.com",
                lang = normalise(a.str("language")),
                release = a.str("release"),
                url = null,
                hashMatch = a.bool("moviehash_match") == true,
                hearingImpaired = a.bool("hearing_impaired") == true,
                machine = a.bool("machine_translated") == true || a.bool("ai_translated") == true,
                fps = a.dbl("fps")?.takeIf { it > 0 },
                downloads = a.int("download_count") ?: 0,
                trusted = a.bool("from_trusted") == true,
                fileId = file.long("file_id"),
            )
        }
    }

    // ---- SubDL ----

    private suspend fun subdl(q: Query, langs: List<String>): List<SubtitleCandidate> {
        val imdb = q.imdb ?: return emptyList()
        val key = URLEncoder.encode(ApiKeys.get("SUBDL_API_KEY"), "UTF-8")
        val codes = langs.joinToString(",") { if (it == "pt-BR") "BR_PT" else it.substringBefore('-').uppercase() }
        val ep = if (!q.movie && q.season != null && q.episode != null) "&season_number=${q.season}&episode_number=${q.episode}" else ""
        val j = Http.get(
            "https://api.subdl.com/api/v1/subtitles?api_key=$key&imdb_id=$imdb&type=${if (q.movie) "movie" else "tv"}" +
                "&languages=$codes&subs_per_page=30$ep",
        )
        return j.arr("subtitles").list().mapNotNull { s ->
            val path = s.str("url") ?: return@mapNotNull null
            val lang = s.str("lang")?.let { if (it.equals("BR_PT", true)) "pt-BR" else normalise(it) } ?: return@mapNotNull null
            SubtitleCandidate(
                provider = "SubDL",
                lang = lang,
                release = s.str("release_name") ?: s.str("name"),
                url = "https://dl.subdl.com$path",
                hearingImpaired = s.bool("hi") == true,
                zipped = path.endsWith(".zip", true),
            )
        }
    }

    // ---- ranking ----

    /** Words that identify a release: group, source, resolution, edition, streaming service. */
    private fun tokens(name: String?): Set<String> = name.orEmpty().lowercase()
        .replace(Regex("""\.(mkv|mp4|avi|srt|sub)$"""), "")
        .split(Regex("""[\s._\-\[\]()]+"""))
        .filter { it.length >= 2 && it.toIntOrNull()?.let { n -> n in 1900..2099 } != true }
        .toSet()

    private val sourceWords = setOf("bluray", "bdrip", "brrip", "remux", "web", "webdl", "webrip", "hdtv", "dvdrip", "amzn", "nf", "dsnp", "hmax", "atvp", "hulu")

    /**
     * Scores and sorts subtitles: language order first, then hash match, release-name similarity
     * (same group and source mean the same cut and timing), frame rate, trust and popularity.
     */
    fun rank(list: List<SubtitleCandidate>, c: SubtitleConfig, release: String?, videoFps: Float?): List<SubtitleCandidate> {
        val langs = wanted(c)
        val rel = tokens(release)
        val group = release?.let { Regex("""-([A-Za-z0-9]+)(\.[a-z0-9]{2,4})?$""").find(it.trim())?.groupValues?.get(1)?.lowercase() }
        return list
            .filter { it.lang in langs }
            .filter { !(c.skipMachine && it.machine) }
            .filter { !(c.hearingImpaired == "exclude" && it.hearingImpaired) }
            .map { s ->
                var score = 0
                if (s.hashMatch) score += 1000
                val t = tokens(s.release)
                if (rel.isNotEmpty() && t.isNotEmpty()) {
                    score += (300 * (rel intersect t).size / rel.size.coerceAtLeast(1)).coerceAtMost(300)
                    if (group != null && group in t) score += 150
                    if ((rel intersect sourceWords).isNotEmpty() && (rel intersect sourceWords) == (t intersect sourceWords)) score += 80
                }
                if (videoFps != null && s.fps != null && kotlin.math.abs(s.fps - videoFps) < 0.05) score += 60
                if (videoFps != null && s.fps != null && kotlin.math.abs(s.fps - videoFps) > 0.5) score -= 120
                if (s.trusted) score += 25
                when (c.hearingImpaired) {
                    "avoid" -> if (s.hearingImpaired) score -= 40
                    "prefer" -> if (s.hearingImpaired) score += 40
                }
                score += (kotlin.math.ln(1.0 + s.downloads) * 8).toInt()
                s.copy(score = score)
            }
            .groupBy { it.lang }
            .flatMap { (_, l) -> l.sortedByDescending { it.score }.distinctBy { it.url ?: it.fileId.toString() }.take(c.perLanguage) }
            .sortedWith(compareBy<SubtitleCandidate> { langs.indexOf(it.lang) }.thenByDescending { it.score })
    }

    // ---- download ----

    /** Downloads a subtitle as UTF-8 text, unzipping and fixing the encoding. Call off the main thread. */
    suspend fun download(s: SubtitleCandidate, target: Query? = null): Pair<String,String>? = kotlinx.coroutines.coroutineScope {
        try {
            val url = s.url ?: s.fileId?.let { resolveOpenSubsCom(it) } ?: return@coroutineScope null
            val call = http.newCall(Request.Builder().url(url).header("User-Agent", "EmberTV/0.9").build())
            val bytes = withResponse(call) { response ->
                if (!response.isSuccessful) return@coroutineScope null
                withContext(Dispatchers.IO) { response.body.byteStream().use { SubtitleFiles.read(it) } }
            }
            val (name, raw) = if (s.zipped || SubtitleFiles.isZip(bytes)) SubtitleFiles.archive(bytes, SubtitleFiles.Target(target?.release ?: s.release,target?.season,target?.episode,s.lang)) ?: return@coroutineScope null else url.substringAfterLast('/') to bytes
            val text = decode(raw,s.lang)
            text to SubtitleFiles.format(name,text.toByteArray(Charsets.UTF_8))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { null }
    }

    private suspend fun resolveOpenSubsCom(fileId: Long): String? {
        osLogin()
        val body = buildJsonObject {
            put("file_id", fileId)
            put("sub_format", "srt")
        }
        return Http.post("$OS/download", body, osHeaders()).str("link")
    }

    /** UTF-8 when the file is valid UTF-8; otherwise the usual Windows code page for the language. */
    fun decode(raw: ByteArray, lang: String): String {
        SubtitleFiles.bom(raw)?.let { return it }
        runCatching {
            val d = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return d.decode(ByteBuffer.wrap(raw)).toString().removePrefix("\uFEFF")
        }.exceptionOrNull()?.let { if (it !is CharacterCodingException) throw it }
        val cp = when (lang.substringBefore('-')) {
            "ru", "uk", "bg", "sr" -> "windows-1251"
            "ar", "fa" -> "windows-1256"
            "he" -> "windows-1255"
            "el" -> "windows-1253"
            "tr" -> "windows-1254"
            "pl", "cs", "sk", "hu", "ro", "hr", "sl" -> "windows-1250"
            "et", "lv", "lt" -> "windows-1257"
            "vi" -> "windows-1258"
            "th" -> "windows-874"
            "zh" -> "GBK"
            "ja" -> "Shift_JIS"
            "ko" -> "EUC-KR"
            else -> "windows-1252"
        }
        return String(raw, runCatching { Charset.forName(cp) }.getOrDefault(Charsets.ISO_8859_1))
    }

    /** Keep provenance/forced/SDH facts for sidecar files beside embedded and online tracks. */
    fun fromOptions(options: List<SubtitleOption>): List<SubtitleCandidate> = options.mapIndexed { i, option ->
        SubtitleCandidate(option.addonName.ifBlank { "Addon" }, normalise(option.lang), null, option.url,
            downloads = 500 - i, hearingImpaired = option.hearingImpaired, forced = option.forced)
    }

    /** Addon subtitle links, as candidates ranked like the rest. */
    fun fromAddons(urls: List<String>, langs: List<String>): List<SubtitleCandidate> =
        urls.mapIndexed { i, u -> SubtitleCandidate("Addon", normalise(langs.getOrNull(i)), null, u, downloads = 500 - i) }

    @Suppress("unused")
    private fun JsonElement?.asText(): String? = (this as? JsonPrimitive)?.content
}
