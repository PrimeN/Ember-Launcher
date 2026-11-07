package app.ember.tv

import app.ember.tv.data.CatalogRow
import app.ember.tv.data.Meta
import app.ember.tv.light.AppShelf
import app.ember.tv.light.LightMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #63: Plex/Jellyfin/Emby App rows come from Ember's own server connection. */
class AppShelfTest {
    private fun item(id: String, provider: String) = Meta(id = id, type = "movie", name = id, poster = "https://img/$id", serverRef = "$provider|acct|$id")
    private val rows = listOf(
        CatalogRow("Plex", "Recently added", listOf(item("a", "plex"), item("j", "jellyfin")), catalogKey = "server:plex|Recently added"),
        CatalogRow("Plex", "Continue watching", listOf(item("b", "plex")), inProgress = true, catalogKey = "server:plex|Continue"),
        CatalogRow("Plex", "Watchlist", listOf(item("c", "plex")), catalogKey = "server:plex:watchlist"),
    )

    @Test fun plexShowsItsServerRowsContinueWatchingFirst() {
        val groups = AppShelf.serverGroups("com.plexapp.android", rows)
        assertEquals(listOf("Continue watching", "Recently added"), groups.map { it.first })
        assertEquals(listOf("b"), groups[0].second.map { it.meta!!.id })
        assertEquals(listOf("a"), groups[1].second.map { it.meta!!.id })
        assertEquals(listOf("j"), AppShelf.serverGroups("org.jellyfin.androidtv", rows).single().second.map { it.meta!!.id })
        assertTrue(AppShelf.serverGroups("com.google.android.youtube.tv", rows).isEmpty())
    }

    @Test fun sharedRowsJoinWithoutRepeatingAName() {
        val media = LightMedia("x:preview:1", "app", "Clip", "", null, "app://1", null, 0, false, channel = "Trending")
        val merged = AppShelf.merge(AppShelf.serverGroups("com.plexapp.android", rows),
            listOf("Recently added" to listOf(media), "Trending" to listOf(media)))
        assertEquals(listOf("Continue watching", "Recently added", "Trending"), merged.map { it.first })
        assertEquals("Clip", merged.last().second.single().title)
    }
}
