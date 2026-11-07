package app.ember.tv.data

import app.ember.tv.tracker.arr
import app.ember.tv.tracker.bool
import app.ember.tv.tracker.obj
import app.ember.tv.tracker.list
import app.ember.tv.tracker.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One entry in a manifest's "resources". Addons write it either as "stream" or as an object. */
data class ResourceDef(val name: String, val types: List<String>?, val idPrefixes: List<String>?,
    val inheritIdPrefixes: Boolean = true)

/** What an addon offers, read from its manifest.json. */
data class AddonInfo(
    val url: String,
    val base: String,
    val name: String,
    val types: List<String>,
    val idPrefixes: List<String>?,
    val resources: List<ResourceDef>,
    val catalogs: List<JsonElement>,
    /** The name in the addon's manifest, when the user renamed it (Settings > Addons). */
    val originalName: String? = null,
    /** The manifest's "id" (e.g. org.kris.ultra.max.v5); Collections name addons by it. */
    val manifestId: String? = null,
    /** What the manifest says about itself, shown in Settings > Addons. */
    val version: String? = null,
    val description: String? = null,
    val logo: String? = null,
    /** The addon has its own settings page (behaviorHints.configurable). */
    val configurable: Boolean = false,
    /** Manifest behaviorHints (#71): adult content, peer-to-peer sources, setup still needed, a real TV guide. */
    val adult: Boolean = false,
    val p2p: Boolean = false,
    val configurationRequired: Boolean = false,
    val epgProvider: Boolean = false,
    val contactEmail: String? = null,
    val background: String? = null,
) {
    /** Where the addon's own settings page is (Stremio's convention: /configure next to the manifest). */
    val configureUrl: String? get() = if (configurable || configurationRequired) base.trimEnd('/') + "/configure" else null

    /** Short on purpose (#50): the default repeats every catalog's raw JSON, and catalogs repeat it again. */
    override fun toString() = "AddonInfo($name, $url, ${catalogs.size} catalogs)"

    /** Resource names this addon serves, e.g. catalog, meta, stream, subtitles. */
    val provides: List<String> get() = (resources.map { it.name } + if (catalogs.isNotEmpty()) listOf("catalog") else emptyList()).distinct()

    /** True when the addon serves [resource] for this content type and id. */
    fun supports(resource: String, type: String, id: String): Boolean {
        return resources.any { r ->
            if (r.name != resource) return@any false
            val types = r.types ?: this.types
            if (types.isNotEmpty() && type !in types) return@any false
            val prefixes = r.idPrefixes ?: idPrefixes.takeIf { r.inheritIdPrefixes }
            prefixes.isNullOrEmpty() || prefixes.any { id.startsWith(it) }
        }
    }

    companion object {
        fun parse(url: String, base: String, j: JsonElement): AddonInfo = AddonInfo(
            url = url,
            base = base,
            name = j.str("name")?.takeIf { it.isNotBlank() } ?: "Add-on",
            types = j.arr("types").strings(),
            idPrefixes = j.arr("idPrefixes")?.strings(),
            resources = j.arr("resources").list().mapNotNull { r ->
                when (r) {
                    is JsonPrimitive -> r.contentOrNull?.let { ResourceDef(it, null, null) }
                    is JsonObject -> r.str("name")?.let { ResourceDef(it, r.arr("types")?.strings(), r.arr("idPrefixes")?.strings(), inheritIdPrefixes = false) }
                    else -> null
                }
            },
            catalogs = j.arr("catalogs").list(),
            manifestId = j.str("id"),
            version = j.str("version")?.trim()?.takeIf { it.isNotEmpty() }?.take(24),
            description = j.str("description")?.trim()?.takeIf { it.isNotEmpty() }?.take(600),
            logo = (j.str("logo") ?: j.str("icon"))?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
            configurable = j.obj("behaviorHints").bool("configurable") == true,
            adult = j.obj("behaviorHints").bool("adult") == true,
            p2p = j.obj("behaviorHints").bool("p2p") == true,
            configurationRequired = j.obj("behaviorHints").bool("configurationRequired") == true,
            epgProvider = j.obj("behaviorHints").bool("epgProvider") == true,
            contactEmail = j.str("contactEmail")?.trim()?.takeIf { it.contains('@') && it.length <= 120 },
            background = j.str("background")?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
        )

        private fun JsonArray?.strings(): List<String> =
            this?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
    }
}

/**
 * How a source plays, shown under its quality on the title screen.
 * SERVICE: a streaming service's own app. DIRECT: cached on a debrid service, or a file on your
 * media server. WEB: a plain web link. UNCACHED: a torrent, or a debrid link that has to download first.
 */
enum class SourceKind(val label: String) {
    SERVICE("Services"),
    DIRECT("Direct"),
    WEB("Web"),
    UNCACHED("Un-cached"),
}

/** One playable source from a stream addon or a media server, with labels read from its text. */
data class StreamOption(
    val addonName: String,
    val name: String,
    val title: String,
    val url: String?,
    val infoHash: String?,
    val fileIdx: Int?,
    /** 4K, FHD, HD, SD, or blank when the source does not say. */
    val quality: String,
    val hdr: String,
    val codec: String,
    val audio: String,
    val source: String,
    val releaseGroup: String,
    val sizeGb: Double?,
    val seeders: Int?,
    val cached: Boolean,
    /** Goes through a debrid service (Real-Debrid, AllDebrid, TorBox...), cached or not. */
    val debrid: Boolean = false,
    /** Set for files on your Plex, Jellyfin or Emby server, so the player reports progress back. */
    val serverRef: String? = null,
    /** What the stream format templates read (Settings > Stremio). Addon sources only. */
    val info: StreamInfo? = null,
    val serverPlayback: app.ember.tv.mediaserver.ServerPlayback? = null,
    /** Request headers the addon asks players to send (behaviorHints.proxyHeaders.request). */
    val headers: Map<String, String> = emptyMap(),
    /** behaviorHints.bingeGroup: "the same kind of source" across episodes (#74). */
    val bingeGroup: String? = null,
    /** behaviorHints.videoHash / videoSize: the OpenSubtitles hash of the file, when the addon knows it. */
    val videoHash: String? = null,
    val videoSize: Long? = null,
    /** Subtitle tracks that come with this source. */
    val subtitles: List<SubtitleOption> = emptyList(),
) {
    /** A web link that a video player can open right away. */
    val isDirect: Boolean get() = !url.isNullOrBlank() && !url.trimStart().startsWith("magnet:", ignoreCase = true)

    val kind: SourceKind
        get() = when {
            serverRef != null -> SourceKind.DIRECT
            !isDirect -> SourceKind.UNCACHED
            cached -> SourceKind.DIRECT
            debrid -> SourceKind.UNCACHED
            else -> SourceKind.WEB
        }

    /** Quality as shown: sources that do not say are counted as SD. */
    val qualityLabel: String get() = quality.ifBlank { "SD" }

    val qualityRank: Int get() = Quality.rank(quality)
}

/** The four quality labels: 4K, FHD (1080p), HD (720p) and SD (anything lower). */
object Quality {
    fun rank(q: String?): Int = when (q) {
        "4K" -> 4
        "FHD" -> 3
        "HD" -> 2
        "SD" -> 1
        else -> 0
    }

    /** From a video height in pixels, as media servers report it. */
    fun fromHeight(h: Int?): String = when {
        h == null || h <= 0 -> ""
        h >= 1600 -> "4K"
        h >= 900 -> "FHD"
        h >= 650 -> "HD"
        else -> "SD"
    }

    /** From a video width, for widescreen files whose height is small (e.g. 1920x800). */
    fun fromSize(w: Int?, h: Int?): String = when {
        w != null && w >= 3200 -> "4K"
        w != null && w >= 1700 -> "FHD"
        w != null && w >= 1200 -> "HD"
        else -> fromHeight(h)
    }
}

data class SubtitleOption(val addonName: String, val lang: String, val url: String, val forced: Boolean = false, val hearingImpaired: Boolean = false, val server: Boolean = false)

/**
 * One catalog from an addon manifest, with the options it accepts. Stremio catalogs take
 * "extra" options in the URL, e.g. /catalog/movie/top/genre=Action&skip=100.json.
 */
data class CatalogExtraDef(val name: String, val required: Boolean = false, val options: List<String> = emptyList(), val default: String? = null,
    /** How many options may be chosen at once (optionsLimit, #72). */
    val limit: Int = 1)

/** Several chosen values for one extra travel in one map entry, separated by this (#72). */
const val EXTRA_VALUES_SEPARATOR = '\u001F'
fun extraValues(value: String): List<String> = value.split(EXTRA_VALUES_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

data class CatalogDef(
    val addon: AddonInfo,
    val type: String,
    val id: String,
    val name: String,
    val genres: List<String>,
    val genreRequired: Boolean,
    /** Accepts a search term. */
    val searchable: Boolean,
    /** Only works with a search term, so it is not browsable. */
    val searchOnly: Boolean,
    /** Needs some other input this launcher does not know how to give. */
    val otherRequired: Boolean,
    val extras: List<CatalogExtraDef> = emptyList(),
    val supportsSkip: Boolean = true,
) {
    val defaultExtras: Map<String, String> get() = extras.mapNotNull { e -> e.default?.let { e.name to it } }.toMap()
    /**
     * What a request sends when nothing was chosen: the catalog's defaults and, for a required extra
     * without one, its first option, as Stremio does. AIOMetadata marks genre required and lists
     * "None" (all genres) first, so collection folders on those catalogs load. Home rows still
     * use [defaultExtras] only.
     */
    val requestExtras: Map<String, String> get() = extras.mapNotNull { e ->
        (e.default ?: e.options.firstOrNull()?.takeIf { e.required })?.let { e.name to it }
    }.toMap()
    fun extraError(values: Map<String, String>): String? {
        if (searchOnly && values["search"].isNullOrBlank()) return "Enter a search term for this catalog."
        extras.forEach { e ->
            val value = values[e.name].orEmpty()
            if (e.required && value.isBlank()) return "Choose ${e.name} to open this catalog."
            val chosen = extraValues(value)
            if (chosen.isNotEmpty() && e.options.isNotEmpty() && chosen.any { it !in e.options }) return "Choose a supported ${e.name}."
            if (chosen.size > e.limit.coerceAtLeast(1)) return "Choose up to ${e.limit} for ${e.name}."
        }
        return null
    }
    /** For a request: only a missing search term or required extra stops it. Values outside the
     * listed options are the addon's to judge; imported collections may use them. */
    fun missingRequired(values: Map<String, String>): String? {
        if (searchOnly && values["search"].isNullOrBlank()) return "Enter a search term for this catalog."
        return extras.firstOrNull { it.required && values[it.name].isNullOrBlank() }?.let { "Choose ${it.name} to open this catalog." }
    }
    val originalLabel: String get() = "$name · ${type.replaceFirstChar { it.uppercase() }}"
    val label: String get() = CatalogNames.label(key, originalLabel)

    /** Stable id for Settings choices: addon link, type and catalog id. */
    val key: String get() = CatalogIdentity.key(addon.url, type, id)

    /** Can be a Home row: needs no search term, genre or other input. */
    val homeable: Boolean get() = !searchOnly && extraError(defaultExtras) == null && (!genreRequired || !defaultExtras["genre"].isNullOrBlank()) && (!otherRequired || extras.isNotEmpty())
    /**
     * Can feed the hero or Light's carousel (#101): loads without typing anything, using its
     * defaults and the first option of a required extra (as Discover and collections do), whether
     * or not it is on Home or in Discover.
     */
    val featurable: Boolean get() = !searchOnly && (!otherRequired || extras.isNotEmpty()) && extraError(requestExtras) == null
    val browsable: Boolean get() = !searchOnly && (!otherRequired || extras.isNotEmpty()) && (!genreRequired || genres.isNotEmpty() || extras.any { it.name == "genre" })
}

/** One episode from an addon's meta "videos" list. [id] is what stream addons expect, e.g. tt0944947:1:1. */
/** Someone in a title's cast or crew. [role] is "Director", "Creator" or the character played. */
data class Person(val name: String, val role: String?, val photo: String? = null, val isCrew: Boolean = false, val tmdbId: Long? = null)

/** Cast, directors and a trailer for a title screen. [trailer] is a YouTube video id. */
data class Credits(val people: List<Person>, val trailer: String?)

data class Episode(
    val id: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val released: String?,
    val thumbnail: String?,
    /** Plot, from the addon or TVDB. */
    val overview: String? = null,
    val serverRef: String? = null,
    val serverSeriesRef: String? = null,
    val progress: Float? = null,
    val watched: Boolean? = null,
)
