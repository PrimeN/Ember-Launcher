package app.ember.tv.light

import app.ember.tv.data.CatalogRow
import app.ember.tv.data.Meta
import app.ember.tv.data.mediaKey
import app.ember.tv.mediaserver.ServerRef

/** One card in Light's App rows (#62/#63): from an app's Android TV rows, or Ember's own server rows. */
internal data class ShelfCard(
    val id: String, val title: String, val subtitle: String, val artwork: String?, val progress: Float?,
    val media: LightMedia? = null, val meta: Meta? = null,
)

internal object AppShelf {
    /**
     * Media server apps and the server they show. Google TV only lets a launcher read rows an app
     * makes public (Plex doesn't), so for these Ember uses its own connection to the server.
     */
    val SERVER_APPS = mapOf(
        "com.plexapp.android" to "plex",
        "org.jellyfin.androidtv" to "jellyfin", "org.jellyfin.mobile" to "jellyfin",
        "tv.emby.embyatv" to "emby", "com.mb.android" to "emby",
    )

    /** The connected server's rows for [pkg]'s app, Continue watching first. */
    fun serverGroups(pkg: String, rows: List<CatalogRow>): List<Pair<String, List<ShelfCard>>> {
        val provider = SERVER_APPS[pkg] ?: return emptyList()
        return rows.asSequence()
            .filterNot { it.catalogKey?.endsWith(":watchlist") == true }
            .map { row -> row to row.items.filter { ServerRef.of(it)?.provider == provider } }
            .filter { (_, items) -> items.isNotEmpty() }
            .sortedByDescending { (row, _) -> row.inProgress }
            .map { (row, items) -> row.title to items.distinctBy { it.mediaKey() }.take(12).map(::card) }
            .toList()
    }

    fun card(meta: Meta) = ShelfCard("meta:${meta.mediaKey()}", meta.name,
        listOfNotNull(meta.season?.let { "S$it" }, meta.episode?.let { "E$it" }).joinToString(" · "),
        meta.episodeThumbnail ?: meta.background ?: meta.poster, meta.progress, meta = meta)

    fun card(media: LightMedia) = ShelfCard(media.id, media.title, media.description, media.artwork, media.progress, media = media)

    /** Server rows first, then what the app itself shares; one group per name. */
    fun merge(server: List<Pair<String, List<ShelfCard>>>, shared: List<Pair<String, List<LightMedia>>>): List<Pair<String, List<ShelfCard>>> {
        val names = server.map { it.first.lowercase() }.toSet()
        return server + shared.filterNot { it.first.lowercase() in names }.map { (name, items) -> name to items.map(::card) }
    }
}
