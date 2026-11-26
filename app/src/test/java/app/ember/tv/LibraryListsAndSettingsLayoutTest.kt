package app.ember.tv

import android.app.Application
import app.ember.tv.data.*
import app.ember.tv.ui.SettingsReset
import app.ember.tv.ui.SettingsTab
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #461: Ember's own lists in collections; #463: the regrouped Settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LibraryListsAndSettingsLayoutTest {
    @Test fun emberListsAreCollectionSourcesThatSurviveImportAndExport() {
        val watchlist = LibraryLists.source(LibraryLists.all.first { it.id == LibraryLists.WATCHLIST })
        val upNext = LibraryLists.source(LibraryLists.all.first { it.id == "smart:upnext" })
        assertTrue(LibraryLists.isLibrary(watchlist))
        assertEquals("Watchlist", Collections.label(watchlist, emptyList()))
        assertEquals("smart:upnext".removePrefix("smart:"), LibraryLists.smartId(upNext.catalogId!!))
        assertNull(LibraryLists.smartId(LibraryLists.CONTINUE))
        val pack = listOf(MediaCollection(id = "mine", title = "Mine", folders = listOf(
            CollectionFolder(id = "f", title = "My stuff", sources = listOf(watchlist, upNext)))))
        val back = Collections.inspect(Collections.encode(pack))
        assertNull(back.error); assertEquals(listOf(watchlist, upNext), back.collections.single().folders.single().allSources)
        // An unknown list is refused, like any malformed source.
        val bad = pack.map { c -> c.copy(folders = c.folders.map { it.copy(sources = listOf(CollectionSource(provider = "library", catalogId = "nope"))) }) }
        assertNotNull(Collections.inspect(Collections.encode(bad)).error)
        val page = LibraryLists.page(listOf(Meta("tt1", "movie", "A"), Meta("tt2", "movie", "B")))
        assertTrue(page.end); assertEquals(2, page.items.size)
    }

    @Test fun mergedPagesResetWithThePageThatShowsThem() {
        assertEquals(setOf(SettingsTab.SIZE, SettingsTab.PERFORMANCE), SettingsReset.parts(SettingsTab.SIZE).toSet())
        assertTrue(SettingsReset.available(SettingsTab.PLAY_BUTTON))
        assertTrue("Watched & spoilers resets with Player", SettingsTab.WATCH_STATUS in SettingsReset.parts(SettingsTab.PLAYER))
        assertTrue(PreferenceKeys.TILE_LABELS_OFF in SettingsReset.keys(SettingsTab.POSTERS))
        assertFalse(PreferenceKeys.TILE_LABELS_OFF in SettingsReset.keys(SettingsTab.APPEARANCE))
        assertTrue(PreferenceKeys.META_CONFIG in SettingsReset.keys(SettingsTab.METADATA))
    }

    @Test fun lookSectionsMovedToPostersAndCards() {
        val style = InterfaceStyles.options.first().first
        assertEquals(listOf("media-page-layout", "tile-labels", "row-addon-labels"), app.ember.tv.ui.lookKeys(SettingsTab.POSTERS, style))
        assertFalse(app.ember.tv.ui.lookKeys(SettingsTab.APPEARANCE, style).any { it == "tile-labels" || it == "media-page-layout" })
        assertTrue(app.ember.tv.ui.SettingsOptionSeed.entries.any { it.tab == SettingsTab.POSTERS && it.section == "Labels under tiles" })
        assertTrue(app.ember.tv.ui.SettingsOptionSeed.entries.none { it.tab == SettingsTab.POSTERS && it.section == "TVDB (TV shows)" })
    }
}
