package app.ember.tv.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** A debrid or cloud service a source plays through. */
data class StreamService(val id: String, val name: String, val shortName: String)

/**
 * Everything the stream format templates can show about one addon source. Values the addon
 * does not give are null (or empty lists), so `::exists` checks work as expected.
 */
data class StreamInfo(
    val addonName: String,
    val name: String,
    val description: String,
    val filename: String?,
    val title: String?,
    val year: String?,
    val seasonEpisode: List<String>,
    val resolution: String?,
    val quality: String?,
    val encode: String?,
    val visualTags: List<String>,
    val audioTags: List<String>,
    val audioChannels: List<String>,
    val languages: List<String>,
    val sizeBytes: Long?,
    val seeders: Int?,
    val indexer: String?,
    val releaseGroup: String?,
    val edition: String?,
    val network: String?,
    val upscaled: Boolean,
    val remastered: Boolean,
    val uncensored: Boolean,
    val repack: Boolean,
    val unrated: Boolean,
    val proxied: Boolean,
    val message: String?,
    val service: StreamService?,
    /** True cached, false must download, null when the source is not a debrid link. */
    val cached: Boolean?,
    val type: String,
)

/**
 * Reads a Stremio stream object into [StreamInfo]. In order of trust:
 * 1. `streamData` that AIOStreams adds when "provide stream data" is on (exact service and cache state),
 * 2. `behaviorHints.service` / `behaviorHints.cached`, which some addons send,
 * 3. the stream's name and description text, the way each addon labels its results.
 */
object StreamInfoParser {
    private class Svc(val id: String, val name: String, val shortName: String, val names: List<String>)

    private val SERVICES = listOf(
        Svc("realdebrid", "Real-Debrid", "RD", listOf("RD", "Real-Debrid", "RealDebrid", "Real Debrid")),
        Svc("alldebrid", "AllDebrid", "AD", listOf("AD", "AllDebrid", "All-Debrid", "All Debrid")),
        Svc("premiumize", "Premiumize", "PM", listOf("PM", "Premiumize")),
        Svc("debridlink", "Debrid-Link", "DL", listOf("DL", "Debrid-Link", "DebridLink", "Debrid Link")),
        Svc("torbox", "TorBox", "TB", listOf("TB", "TorBox", "TRB")),
        Svc("easydebrid", "EasyDebrid", "ED", listOf("ED", "EasyDebrid")),
        Svc("offcloud", "Offcloud", "OC", listOf("OC", "Offcloud")),
        Svc("putio", "put.io", "P.IO", listOf("put.io", "putio")),
        Svc("pikpak", "PikPak", "PKP", listOf("PKP", "PikPak")),
        Svc("seedr", "Seedr", "SDR", listOf("SDR", "Seedr")),
        Svc("debrider", "Debrider", "DR", listOf("Debrider", "DBD")),
        Svc("easynews", "Easynews", "EN", listOf("Easynews")),
    )
    private val byId = SERVICES.associateBy { it.id }

    /** A service name or code standing on its own in the stream's name, e.g. "[RD+]", "TB⚡", "RD download". */
    private val serviceRegexes = SERVICES.map { s ->
        s to Regex(
            "(?<![A-Za-z0-9])(" + s.names.joinToString("|") { Regex.escape(it) } + ")(?![A-Za-z0-9])",
            RegexOption.IGNORE_CASE,
        )
    }

    private val cachedSymbols = listOf("⚡", "🚀", "🌩", "📫", "▶", "✅")
    private val uncachedSymbols = listOf("⏳", "☁", "⬇", "▷")
    private val uncachedWords = Regex("""(?<![A-Za-z])(un-?cached|not\s+cached|download)(?![A-Za-z])""", RegexOption.IGNORE_CASE)
    private val cachedWord = Regex("""(?<![A-Za-z])cached(?![A-Za-z])""", RegexOption.IGNORE_CASE)
    private val uncachedAnywhere = Regex("""(?<![A-Za-z])(un-?cached|not\s+cached)(?![A-Za-z])""", RegexOption.IGNORE_CASE)

    /** Links that resolve through a debrid service when played. */
    private val debridUrlMarks = listOf(
        "real-debrid.com", "rdeb.io", "alldebrid.com", "premiumize.me", "debrid-link", "torbox.app", "offcloud.com",
        "/resolve/", "/playback/", "realdebrid", "alldebrid", "premiumize", "debridlink", "torbox", "offcloud",
        "easydebrid", "debridio", "stremthru",
    )

    fun parse(addonName: String, j: JsonObject): StreamInfo {
        val name = j.s("name").orEmpty()
        val description = (j.s("description") ?: j.s("title")).orEmpty()
        val url = j.s("url")?.trim()?.takeIf(String::isNotEmpty)
        val hints = j["behaviorHints"] as? JsonObject
        val data = j["streamData"] as? JsonObject
        val parsed = data?.get("parsedFile") as? JsonObject
        val torrent = data?.get("torrent") as? JsonObject

        val filename = data?.s("filename") ?: hints?.s("filename")
        val text = listOfNotNull(filename, description, name).joinToString("\n")
        val isTorrent = url == null || url.startsWith("magnet:", ignoreCase = true)

        // ---- service and cache state ----
        var service: StreamService? = null
        var cached: Boolean? = null
        (data?.get("service") as? JsonObject)?.let { s ->
            val id = s.s("id")
            service = id?.let { byId[it] }?.let { StreamService(it.id, it.name, it.shortName) }
                ?: id?.let { StreamService(it, it, it.take(3).uppercase()) }
            cached = s.b("cached")
        }
        if (cached == null) {
            val id = hints?.s("service")
            if (id != null) {
                service = byId[id]?.let { StreamService(it.id, it.name, it.shortName) } ?: StreamService(id, id, id.take(3).uppercase())
                cached = hints!!.b("cached") ?: true
            }
        }
        if (cached == null && !isTorrent) {
            val clean = name.replace(Regex("""web-?dl""", RegexOption.IGNORE_CASE), "")
            var found: Svc? = null
            var plus = false
            search@ for ((s, re) in serviceRegexes) {
                for (m in re.findAll(clean)) {
                    // Short codes must be in capitals; full names may be in any case.
                    if (m.value.length <= 3 && m.value != m.value.uppercase()) continue
                    found = s
                    plus = clean.getOrNull(m.range.last + 1) == '+'
                    break@search
                }
            }
            val uncachedMark = uncachedSymbols.any { name.contains(it) } || uncachedWords.containsMatchIn(name) ||
                uncachedAnywhere.containsMatchIn(description)
            val cachedMark = plus || cachedSymbols.any { name.contains(it) } || cachedWord.containsMatchIn(name)
            val debridLink = found != null || cachedMark || uncachedMark || debridUrlMarks.any { url!!.contains(it, ignoreCase = true) }
            if (found != null) service = StreamService(found.id, found.name, found.shortName)
            // Addons list cached results unless told otherwise and label the ones still downloading,
            // so a debrid link with no label counts as cached.
            if (debridLink) cached = !uncachedMark
        }

        // ---- release details ----
        val pf = parsed
        val resolution = pf?.s("resolution")?.takeIf { it != "Unknown" } ?: resolution(text)
        val quality = pf?.s("quality")?.takeIf { it != "Unknown" } ?: quality(text)
        val encode = pf?.s("encode")?.takeIf { it != "Unknown" } ?: encode(text)
        val visual = pf?.list("visualTags") ?: visualTags(text)
        val audio = pf?.list("audioTags") ?: audioTags(text)
        val channels = pf?.list("audioChannels") ?: audioChannels(text)
        val languages = pf?.list("languages") ?: languages(text)

        val base = filename ?: description.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val se = pf?.let { seasonEpisodeFrom(it) } ?: seasonEpisode(base)
        val year = pf?.s("year") ?: yearRe.find(base)?.value
        val title = pf?.s("title") ?: titleFrom(base)

        val size = data?.l("size") ?: hints?.l("videoSize") ?: sizeFromText(text)

        return StreamInfo(
            addonName = addonName,
            name = name,
            description = description,
            filename = filename,
            title = title,
            year = year,
            seasonEpisode = se,
            resolution = resolution,
            quality = quality,
            encode = encode,
            visualTags = visual,
            audioTags = audio,
            audioChannels = channels,
            languages = languages,
            sizeBytes = size,
            seeders = torrent?.l("seeders")?.toInt() ?: seedersRe.find(text)?.groupValues?.get(1)?.toIntOrNull(),
            indexer = data?.s("indexer") ?: indexerRe.find(text)?.groupValues?.get(1)?.trim(),
            releaseGroup = pf?.s("releaseGroup") ?: groupRe.find(base.substringBeforeLast('.').ifBlank { base })?.groupValues?.get(1),
            edition = pf?.list("editions")?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: edition(text),
            network = pf?.s("network") ?: network(text),
            upscaled = pf?.b("upscaled") ?: flag(text, "upscaled", "upscale", "ai upscale", "uprez"),
            remastered = flag(text, "remastered", "remaster"),
            uncensored = pf?.b("uncensored") ?: flag(text, "uncensored"),
            repack = pf?.b("repack") ?: flag(text, "repack", "proper"),
            unrated = pf?.b("unrated") ?: flag(text, "unrated"),
            proxied = data?.b("proxied") ?: false,
            message = data?.s("message"),
            service = service,
            cached = cached,
            type = data?.s("type") ?: when {
                isTorrent -> "p2p"
                cached != null -> "debrid"
                else -> "http"
            },
        )
    }

    // ---- release name patterns ----

    /** Word edges for release names, where dots, dashes, underscores and brackets separate words. */
    private fun tag(pattern: String) =
        Regex("""(?<![A-Za-z0-9])(?:$pattern)(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE)

    private val resolutions = listOf(
        "2160p" to tag("""2160[pi]?|4k|uhd|3840x\d{4}"""),
        "1440p" to tag("""1440[pi]?|2k|qhd"""),
        "1080p" to tag("""1080[pi]?|fhd|full[ .\-_]?hd|1920x\d{3,4}"""),
        "720p" to tag("""720[pi]?|1280x\d{3,4}"""),
        "576p" to tag("""576[pi]?"""),
        "480p" to tag("""480[pi]?|sd"""),
        "360p" to tag("""360[pi]?"""),
    )
    private val hdOnly = tag("""hd(?![ .\-_]?(rip|tv|r|cam|ts|tc))""")

    fun resolution(t: String): String? =
        resolutions.firstOrNull { it.second.containsMatchIn(t) }?.first ?: if (hdOnly.containsMatchIn(t)) "720p" else null

    private val qualities = listOf(
        "BluRay REMUX" to tag("""(bd|br|uhd|bluray)?[ .\-_]?remux"""),
        "BluRay" to tag("""blu[ .\-_]?ray|bd[ .\-_]?rip|br[ .\-_]?rip|bdrip|brrip"""),
        "WEB-DL" to tag("""web[ .\-_]?dl|web"""),
        "WEBRip" to tag("""web[ .\-_]?rip"""),
        "HDRip" to tag("""hd[ .\-_]?rip"""),
        "HDTV" to tag("""hdtv|pdtv|tv[ .\-_]?rip"""),
        "DVDRip" to tag("""dvd[ .\-_]?(rip|r|5|9)?"""),
        "CAM" to tag("""(hd)?cam([ .\-_]?rip)?"""),
        "TS" to tag("""telesync|(hd)?ts"""),
        "TC" to tag("""telecine|(hd)?tc"""),
        "SCR" to tag("""(dvd|bd|web)?[ .\-_]?scr(eener)?"""),
    )

    fun quality(t: String): String? {
        // WEBRip contains "WEB", so check it before WEB-DL.
        if (qualities[3].second.containsMatchIn(t)) return "WEBRip"
        return qualities.firstOrNull { it.second.containsMatchIn(t) }?.first
    }

    private val encodes = listOf(
        "VVC" to tag("""vvc|h[ .\-_]?266"""),
        "HEVC" to tag("""hevc|[xh][ .\-_]?265"""),
        "AVC" to tag("""avc|[xh][ .\-_]?264"""),
        "AV1" to tag("""av1"""),
        "VC-1" to tag("""vc[ .\-_]?1"""),
        "XviD" to tag("""xvid"""),
        "DivX" to tag("""divx"""),
        "MPEG-4" to tag("""mpeg[ .\-_]?4"""),
    )

    fun encode(t: String): String? = encodes.firstOrNull { it.second.containsMatchIn(t) }?.first

    private val visual = listOf(
        "HDR Vivid" to tag("""hdr[ .\-_]?vivid|cuva"""),
        "HDR10+" to tag("""hdr[ .\-_]?10[ .\-_]?(\+|plus|p)|hdr10\+"""),
        "HDR10" to tag("""hdr[ .\-_]?10(?![ .\-_]?(\+|plus|p|bit))"""),
        "HDR" to tag("""hdr(?![ .\-_]?10)"""),
        "DV" to tag("""dv|dovi|dolby[ .\-_]?vision"""),
        "HLG" to tag("""hlg"""),
        "10bit" to tag("""10[ .\-_]?bit|hi10p?"""),
        "IMAX" to tag("""imax"""),
        "3D" to tag("""3d"""),
        "SDR" to tag("""sdr"""),
    )

    fun visualTags(t: String): List<String> {
        val tags = visual.filter { it.second.containsMatchIn(t) }.map { it.first }.toMutableList()
        // "HDR10+" text also matches the plain HDR pattern on some spellings; keep the most specific.
        if ("HDR10+" in tags) tags.remove("HDR10")
        if ("HDR10+" in tags || "HDR10" in tags) tags.remove("HDR")
        if ("HDR Vivid" in tags) tags.remove("HDR")
        return tags
    }

    private val audio = listOf(
        "AC-4" to tag("""ac[ .\-_]?4"""),
        "MPEG-H" to tag("""mpeg[ .\-_]?h"""),
        "IAMF" to tag("""iamf"""),
        "Atmos" to tag("""atmos|ddpa\d?"""),
        "TrueHD" to tag("""true[ .\-_]?hd"""),
        "DTS:X" to tag("""dts[ .\-:_]?x"""),
        "DTS-HD MA" to tag("""dts[ .\-_]?hd[ .\-_]?ma"""),
        "DTS-HD" to tag("""dts[ .\-_]?hd(?![ .\-_]?ma)"""),
        "DTS-ES" to tag("""dts[ .\-_]?es"""),
        "DTS" to tag("""dts(?![ .\-:_]?(x|hd|es))"""),
        "DD+" to Regex("""(?<![A-Za-z0-9])(dd\+|ddp|dd[ .\-_]?plus|e[ .\-_]?ac[ .\-_]?3)""", RegexOption.IGNORE_CASE),
        "DD" to tag("""dd(?!\+|p)|ac[ .\-_]?3|dolby[ .\-_]?digital"""),
        "AAC" to tag("""q?aac(2)?"""),
        "FLAC" to tag("""flac"""),
        "OPUS" to tag("""opus"""),
        "PCM" to tag("""l?pcm"""),
    )

    fun audioTags(t: String): List<String> {
        val tags = audio.filter { it.second.containsMatchIn(t) }.map { it.first }.toMutableList()
        if ("DD+" in tags) tags.remove("DD")
        return tags
    }

    private val channels = listOf(
        "7.1" to Regex("""(?<![0-9.])7[ .]1(?![0-9])"""),
        "6.1" to Regex("""(?<![0-9.])6[ .]1(?![0-9])"""),
        "5.1" to Regex("""(?<![0-9.])5[ .]1(?![0-9])"""),
        "2.0" to Regex("""(?<![0-9.])2[ .]0(?![0-9])"""),
    )

    fun audioChannels(t: String): List<String> = channels.filter { it.second.containsMatchIn(t) }.map { it.first }

    /** Flag emojis, as Torrentio and others show them. */
    private val flags = mapOf(
        "🇬🇧" to "English", "🇺🇸" to "English", "🇯🇵" to "Japanese", "🇰🇷" to "Korean", "🇨🇳" to "Chinese",
        "🇹🇼" to "Chinese", "🇷🇺" to "Russian", "🇸🇦" to "Arabic", "🇵🇹" to "Portuguese", "🇧🇷" to "Portuguese",
        "🇪🇸" to "Spanish", "🇲🇽" to "Latino", "🇫🇷" to "French", "🇩🇪" to "German", "🇮🇹" to "Italian",
        "🇮🇳" to "Hindi", "🇹🇭" to "Thai", "🇻🇳" to "Vietnamese", "🇮🇩" to "Indonesian", "🇹🇷" to "Turkish",
        "🇮🇱" to "Hebrew", "🇮🇷" to "Persian", "🇺🇦" to "Ukrainian", "🇬🇷" to "Greek", "🇵🇱" to "Polish",
        "🇨🇿" to "Czech", "🇸🇰" to "Slovak", "🇭🇺" to "Hungarian", "🇷🇴" to "Romanian", "🇧🇬" to "Bulgarian",
        "🇷🇸" to "Serbian", "🇭🇷" to "Croatian", "🇸🇮" to "Slovenian", "🇳🇱" to "Dutch", "🇩🇰" to "Danish",
        "🇫🇮" to "Finnish", "🇸🇪" to "Swedish", "🇳🇴" to "Norwegian", "🇲🇾" to "Malay", "🇱🇹" to "Lithuanian",
        "🇱🇻" to "Latvian", "🇪🇪" to "Estonian", "🇧🇩" to "Bengali",
    )

    /** Language words; short codes only in capitals, so ordinary words are not mistaken for them. */
    private val languageWords = listOf(
        "Multi" to tag("""multi([ .\-_]?audio)?"""),
        "Dual Audio" to tag("""dual[ .\-_]?(audio|lang)"""),
        "Dubbed" to tag("""dub(bed)?"""),
        "English" to tag("""english"""), "Japanese" to tag("""japanese"""), "Chinese" to tag("""chinese|mandarin|cantonese"""),
        "Russian" to tag("""russian"""), "Arabic" to tag("""arabic"""), "Portuguese" to tag("""portuguese"""),
        "Spanish" to tag("""spanish|castellano"""), "Latino" to tag("""latino"""), "French" to tag("""french|truefrench|vff"""),
        "German" to tag("""german|deutsch"""), "Italian" to tag("""italian"""), "Korean" to tag("""korean"""),
        "Hindi" to tag("""hindi"""), "Tamil" to tag("""tamil"""), "Telugu" to tag("""telugu"""), "Malayalam" to tag("""malayalam"""),
        "Kannada" to tag("""kannada"""), "Bengali" to tag("""bengali"""), "Punjabi" to tag("""punjabi"""),
        "Marathi" to tag("""marathi"""), "Gujarati" to tag("""gujarati"""), "Thai" to tag("""thai"""),
        "Vietnamese" to tag("""vietnamese"""), "Indonesian" to tag("""indonesian"""), "Turkish" to tag("""turkish"""),
        "Hebrew" to tag("""hebrew"""), "Persian" to tag("""persian|farsi"""), "Ukrainian" to tag("""ukrainian"""),
        "Greek" to tag("""greek"""), "Polish" to tag("""polish"""), "Czech" to tag("""czech"""), "Slovak" to tag("""slovak"""),
        "Hungarian" to tag("""hungarian"""), "Romanian" to tag("""romanian"""), "Bulgarian" to tag("""bulgarian"""),
        "Serbian" to tag("""serbian"""), "Croatian" to tag("""croatian"""), "Slovenian" to tag("""slovenian"""),
        "Dutch" to tag("""dutch"""), "Danish" to tag("""danish"""), "Finnish" to tag("""finnish"""),
        "Swedish" to tag("""swedish"""), "Norwegian" to tag("""norwegian"""), "Malay" to tag("""malay"""),
        "Lithuanian" to tag("""lithuanian"""), "Latvian" to tag("""latvian"""), "Estonian" to tag("""estonian"""),
    )
    private val languageCodes = listOf(
        "English" to "ENG", "Japanese" to "JPN", "Italian" to "ITA", "German" to "GER", "Spanish" to "SPA",
        "Russian" to "RUS", "Korean" to "KOR", "Hindi" to "HIN", "French" to "FRE",
    ).map { (lang, code) -> lang to Regex("""(?<![A-Za-z0-9])$code(?![A-Za-z0-9])""") }

    fun languages(t: String): List<String> {
        val out = LinkedHashSet<String>()
        languageWords.forEach { (lang, re) -> if (re.containsMatchIn(t)) out += lang }
        languageCodes.forEach { (lang, re) -> if (re.containsMatchIn(t)) out += lang }
        flags.forEach { (flag, lang) -> if (t.contains(flag)) out += lang }
        return out.toList()
    }

    private val editions = listOf(
        "Anniversary Edition" to tag("""anniversary([ .\-_]?edition)?"""),
        "Ultimate Edition" to tag("""ultimate[ .\-_]?(edition|cut)"""),
        "Director's Cut" to tag("""director'?s[ .\-_]?cut|dc"""),
        "Extended Edition" to tag("""extended([ .\-_]?(edition|cut))?"""),
        "Collector's Edition" to tag("""collector'?s([ .\-_]?edition)?"""),
        "Theatrical" to tag("""theatrical([ .\-_]?cut)?"""),
        "Uncut" to tag("""uncut"""),
        "IMAX" to tag("""imax([ .\-_]?edition)?"""),
        "Diamond Edition" to tag("""diamond([ .\-_]?edition)?"""),
        "Remastered" to tag("""remaster(ed)?"""),
    )

    fun edition(t: String): String? = editions.firstOrNull { it.second.containsMatchIn(t) }?.first

    private val networks = listOf(
        "Netflix" to tag("""nf|netflix"""), "Amazon" to tag("""amzn|amazon"""), "Disney" to tag("""dsnp|disney\+?"""),
        "Apple TV" to tag("""atvp|aptv|apple[ .\-_]?tv\+?"""), "HBO" to tag("""hmax|hbo([ .\-_]?max)?"""),
        "Hulu" to tag("""hulu"""), "Peacock" to tag("""pcok|peacock"""), "Paramount" to tag("""pmtp|paramount\+?"""),
        "Crunchyroll" to tag("""crunchyroll|cr"""), "CBS" to tag("""cbs"""), "NBC" to tag("""nbc"""), "AMC" to tag("""amc"""),
        "PBS" to tag("""pbs"""), "Hallmark" to tag("""hallmark"""), "Nickelodeon" to tag("""nick(elodeon)?"""),
        "Adult Swim" to tag("""as|adult[ .\-_]?swim"""), "Sony" to tag("""sony"""), "VICE" to tag("""vice"""),
    )

    /** Only the release name is checked, so "AS" or "CR" in other text is not taken for a network. */
    fun network(t: String): String? {
        val first = t.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        return networks.firstOrNull { it.second.containsMatchIn(first) }?.first
    }

    private fun flag(t: String, vararg words: String) = words.any { tag(Regex.escape(it).replace(" ", """[ .\-_]?""")).containsMatchIn(t) }

    private val seRe = Regex("""(?<![A-Za-z0-9])S(\d{1,2})[ .\-_]?E(\d{1,3})(?:[ .\-_]?-?E?(\d{1,3}))?""", RegexOption.IGNORE_CASE)
    private val seasonOnly = Regex("""(?<![A-Za-z0-9])S(\d{1,2})(?![0-9E])""", RegexOption.IGNORE_CASE)
    private val yearRe = Regex("""(?<![0-9])(19[2-9]\d|20[0-4]\d)(?![0-9])""")
    private val sizeRe = Regex("""(\d+(?:[.,]\d+)?)\s?(TB|GB|MB|KB)(?![A-Za-z])""", RegexOption.IGNORE_CASE)
    private val seedersRe = Regex("""[👤👥]\s*(\d+)""")
    private val indexerRe = Regex("""⚙️?\s*([^\n👤💾📦⚙]+)""")
    private val groupRe = Regex("""-([A-Za-z0-9]+)\s*$""")

    private fun pad(n: String) = n.toInt().toString().padStart(2, '0')

    fun seasonEpisode(t: String): List<String> {
        seRe.find(t)?.let { m ->
            val s = "S" + pad(m.groupValues[1])
            val e = "E" + pad(m.groupValues[2]) + (m.groupValues[3].takeIf { it.isNotEmpty() }?.let { "-E" + pad(it) } ?: "")
            return listOf(s, e)
        }
        return seasonOnly.find(t)?.let { listOf("S" + pad(it.groupValues[1])) } ?: emptyList()
    }

    private fun seasonEpisodeFrom(pf: JsonObject): List<String>? {
        val seasons = (pf["seasons"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull } ?: return null
        val episodes = (pf["episodes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull } ?: emptyList()
        fun range(prefix: String, l: List<Long>) = when {
            l.isEmpty() -> null
            l.size == 1 -> prefix + l[0].toString().padStart(2, '0')
            else -> prefix + l.first().toString().padStart(2, '0') + "-" + l.last().toString().padStart(2, '0')
        }
        return listOfNotNull(range("S", seasons), range("E", episodes))
    }

    /** The title part of a release name: everything before the year or season, with dots as spaces. */
    fun titleFrom(base: String): String? {
        if (base.isBlank()) return null
        val cut = listOfNotNull(yearRe.find(base)?.range?.first, seRe.find(base)?.range?.first, seasonOnly.find(base)?.range?.first,
            resolutions.mapNotNull { it.second.find(base)?.range?.first }.minOrNull())
            .filter { it > 0 }.minOrNull() ?: return null
        return base.substring(0, cut).replace(Regex("""[._]"""), " ").trim(' ', '-', '(', '[').trim().takeIf { it.length > 1 }
    }

    private fun sizeFromText(t: String): Long? {
        val m = sizeRe.find(t) ?: return null
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        // Decimal units, so {stream.size::bytes} shows the same number the addon printed.
        val mult = when (m.groupValues[2].uppercase()) {
            "TB" -> 1e12
            "GB" -> 1e9
            "MB" -> 1e6
            else -> 1e3
        }
        return (v * mult).toLong()
    }

    // ---- small JSON helpers ----
    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    private fun JsonObject.b(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.l(k: String): Long? = (this[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
    private fun JsonObject.list(k: String): List<String>? =
        (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.filter { it != "Unknown" }

    @Suppress("unused")
    private fun JsonElement.obj(): JsonObject? = this as? JsonObject
}
