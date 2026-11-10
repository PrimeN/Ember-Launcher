package app.ember.tv.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A Home widget (#161): a row on the Ember interface's Home that you define: what it shows
 * ([source]) and how ([style], [shape]). The built-in sections (hero, apps, catalogs, collections,
 * lists) are not stored here; they keep their own settings. Its place on Home is its section in
 * the Home layout, like every other section.
 */
@Serializable
data class HomeWidget(
    val id: String = "",
    val title: String = "",
    /** [HomeWidgets.ROW], [HomeWidgets.NUMBERED], [HomeWidgets.CAROUSEL] or [HomeWidgets.HERO]. */
    val style: String = HomeWidgets.ROW,
    /** "default" (follow Home's card setting), "poster" or "wide". The carousel is always wide. */
    val shape: String = "default",
    val source: CollectionSource = CollectionSource(),
    val limit: Int = HomeWidgets.DEFAULT_LIMIT,
)

object HomeWidgets {
    const val ROW = "row"
    const val NUMBERED = "numbered"
    const val CAROUSEL = "carousel"
    /** A banner of its own: artwork, title, description and buttons, one title at a time. */
    const val HERO = "hero"
    val styles = listOf(ROW to "Row", NUMBERED to "Numbered row", CAROUSEL to "Cinematic carousel", HERO to "Hero banner")
    val shapes = listOf("default" to "Follow Home", "poster" to "Poster", "wide" to "Wide")

    /** A source that is one of your own lists; [CollectionSource.catalogId] says which. Needs no request. */
    const val LIST = "home-list"
    val lists = listOf("watchlist" to "Your watchlist", "continue" to "In progress", "recent" to "Recently watched")
    /** A tracker row (Up next, recommendations, your tracker lists…) by its Home key, e.g. "smart:upnext". */
    fun isTrackerList(id: String?) = id != null && id.startsWith("smart:") && id.length in 7..200

    /** MDBList (widgets only): [CollectionSource.catalogId] is the list's number or "user/list-name". */
    const val MDBLIST = "mdblist"
    private val mdbPath = Regex("[0-9]{1,12}|[A-Za-z0-9][A-Za-z0-9_.-]{0,63}/[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
    /** The list in a pasted MDBList link, "user/list" text or number; null when it is none of those. */
    fun mdblistPath(text: String): String? {
        val t = text.trim().substringBefore('?').substringBefore('#').trimEnd('/')
        val path = if (t.contains("mdblist.com", true)) t.substringAfter("mdblist.com", "").substringAfter("/lists/", "") else t
        return path.trim('/').takeIf { mdbPath.matches(it) }
    }

    const val DEFAULT_LIMIT = 20
    val LIMITS = 5..50
    const val MAX_WIDGETS = 40

    private const val PREFIX = "widget:"
    fun section(id: String) = PREFIX + id
    fun isSection(section: String) = section.startsWith(PREFIX)

    fun styleName(style: String) = styles.firstOrNull { it.first == style }?.second ?: "Row"

    /** Unknown styles and shapes (an older or newer build's) fall back, so a restored widget still shows. */
    fun normalize(w: HomeWidget) = w.copy(
        title = w.title.trim().take(100),
        style = w.style.takeIf { s -> styles.any { it.first == s } } ?: ROW,
        shape = w.shape.takeIf { s -> shapes.any { it.first == s } } ?: "default",
        limit = w.limit.coerceIn(LIMITS.first, LIMITS.last),
    )

    fun error(w: HomeWidget): String? = when {
        w.id.isBlank() -> "The widget has no id."
        w.title.isBlank() -> "Enter a widget title."
        w.source.provider !in setOf("addon", "ember", "trakt", "tmdb", LIST, MDBLIST) -> "Choose a source."
        w.source.provider == LIST && lists.none { it.first == w.source.catalogId } && !isTrackerList(w.source.catalogId) -> "Choose one of your lists."
        w.source.provider == MDBLIST && w.source.catalogId?.let { mdbPath.matches(it) } != true -> "Enter an MDBList list link, user/list-name or number."
        w.source.provider == "addon" && w.source.catalogId.isNullOrBlank() -> "Choose a catalog."
        w.source.provider == "trakt" && w.source.traktListId == null -> "Enter a Trakt list link or number."
        w.source.provider == "tmdb" && w.source.tmdbId == null && !w.source.tmdbSourceType.equals("DISCOVER", true) -> "Enter a TMDB list link or number."
        w.source.provider == "ember" && w.source.exploreQuery == null -> "Choose filters."
        w.source.provider == "ember" -> w.source.exploreQuery?.error()
        // Private keys belong in Collections, which store them encrypted; a widget is plain settings.
        w.source.extraArgs.any { (name, value) -> value.isNotBlank() && CollectionSecrets.sensitive(name) } ->
            "This catalog needs a private key. Put it in a collection instead."
        else -> null
    }

    /** What the source is, for the widget lists on the TV and the phone. */
    fun sourceLabel(source: CollectionSource, defs: List<CatalogDef>): String = when (source.provider) {
        LIST -> lists.firstOrNull { it.first == source.catalogId }?.second ?: source.title?.takeIf { it.isNotBlank() } ?: "Your list"
        MDBLIST -> "MDBList ${source.catalogId.orEmpty()}".trim()
        "ember" -> "Filters" + (source.exploreQuery?.let { q -> ": " + summary(q) } ?: "")
        "trakt" -> "Trakt list ${source.traktListId ?: ""}".trim()
        "tmdb" -> "TMDB ${(source.tmdbSourceType ?: "list").lowercase()} ${source.tmdbId ?: ""}".trim()
        else -> Collections.label(source, defs)
    }

    /** The filters in a few words, e.g. "Movies · Comedy · 2020–2026 · 7+ · Highest rated". */
    fun summary(q: app.ember.tv.discover.ExploreQuery): String = listOfNotNull(
        q.kind.label,
        q.genres.mapNotNull { key -> app.ember.tv.discover.ExploreGenres.all.firstOrNull { it.key == key }?.label }.takeIf { it.isNotEmpty() }?.joinToString(", "),
        when {
            q.fromYear != null && q.toYear != null -> if (q.fromYear == q.toYear) "${q.fromYear}" else "${q.fromYear}–${q.toYear}"
            q.fromYear != null -> "from ${q.fromYear}"
            q.toYear != null -> "to ${q.toYear}"
            else -> null
        },
        q.minRating?.let { r -> (if (r % 1.0 == 0.0) r.toInt().toString() else r.toString()) + "+" },
        q.sort.label,
    ).joinToString(" · ")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
    fun decode(text: String?): List<HomeWidget> = runCatching { json.decodeFromString<List<HomeWidget>>(text ?: "[]") }.getOrDefault(emptyList())
        .map(::normalize).filter { error(it) == null }.distinctBy { it.id }.take(MAX_WIDGETS)
    // ---- export and import (#163) ----
    const val PACK_FORMAT = "ember-widgets"
    @Serializable private data class Pack(val format: String = "", val version: Int = 0, val widgets: List<HomeWidget> = emptyList())
    /** A file of widgets for another TV or profile. Widgets hold no private keys, so the pack is plain JSON. */
    fun pack(list: List<HomeWidget>): String = json.encodeToString(Pack.serializer(), Pack(format = PACK_FORMAT, version = 1, widgets = list))
    /**
     * The widgets in an exported pack (or a bare list). Filter widgets belong to a profile, so they
     * become [profile]'s. Widgets that cannot work are left out; none at all is an error.
     */
    fun unpack(text: String, profile: String): Result<List<HomeWidget>> = runCatching {
        require(text.length <= 2_000_000) { "The widget pack is too large." }
        val raw = runCatching { json.decodeFromString(Pack.serializer(), text) }.getOrNull()?.takeIf { it.format == PACK_FORMAT }?.widgets
            ?: runCatching { json.decodeFromString<List<HomeWidget>>(text) }.getOrNull()
            ?: error("This is not an Ember widget pack.")
        val usable = raw.map { w -> normalize(if (w.source.provider == "ember") w.copy(source = w.source.copy(ownerProfile = profile)) else w) }
            .filter { error(it) == null }.distinctBy { it.id }.take(MAX_WIDGETS)
        require(usable.isNotEmpty()) { "The pack has no widgets that can be used here." }
        usable
    }

    fun encode(list: List<HomeWidget>): String = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(HomeWidget.serializer()), list)
}
