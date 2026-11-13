package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.ui.SettingDefinitions
import app.ember.tv.ui.SettingsTab
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class HeroCarouselFeaturesTest {
    @get:Rule val temp = TemporaryFolder()
    private fun title(id: Int) = Meta(id = "tt$id", type = "movie", name = "Title $id", background = "https://art.example/$id.jpg")
    private fun row(key: String, items: List<Meta>) = CatalogRow("Addon $key", key, items, catalogKey = key)
    private val standard = Json.parseToJsonElement("""{"images":{"backdrops":[{"file_path":"/standard.jpg","width":1920,"height":1080}],"logos":[{"file_path":"/standard.png","width":800,"height":200,"iso_639_1":"en"}]},"external_ids":{"imdb_id":"tt1"}}""")
    private val fanart = Json.parseToJsonElement("""{"moviebackground":[{"url":"https://assets.fanart.tv/fanart.jpg","lang":"00"}],"hdmovielogo":[{"url":"https://assets.fanart.tv/fanart.png","lang":"en"}]}""")

    @Test fun automaticMixFillsTenFromOneCatalogInsteadOfStoppingAtThree() {
        val chosen = HeroBanner.items(listOf(row("one", (1..20).map(::title))), null) { true }
        assertEquals((1..10).map { "tt$it" }, chosen.map { it.id })
    }

    @Test fun automaticMixBalancesCatalogsInTheirHomeOrder() {
        val selected = HeroBanner.items(listOf(row("a", (1..15).map(::title)), row("b", (21..35).map(::title))), null) { true }
        assertEquals(listOf(1,21,2,22,3,23,4,24,5,25).map { "tt$it" }, selected.map { it.id })
    }

    @Test fun chosenCatalogUsesOnlyItsOwnTitlesAndOriginalOrder() {
        val selected = HeroBanner.items(listOf(row("a", (1..20).map(::title)), row("b", (21..40).map(::title))), "b") { true }
        assertEquals((21..30).map { "tt$it" }, selected.map { it.id })
    }

    @Test fun hiddenAndMissingArtworkEntriesAreFilteredBeforeTheTenItemLimit() {
        val items = (1..20).map(::title).map { if (it.id == "tt2") it.copy(background = null) else it }
        val selected = HeroBanner.items(listOf(row("a", items)), "a") { it.id != "tt1" }
        assertEquals((3..12).map { "tt$it" }, selected.map { it.id })
        assertTrue(HeroBanner.items(listOf(row("a", items)), null) { false }.isEmpty())
    }

    @Test fun duplicateTitlesDoNotPadTheDotsOrStarveLaterCandidates() {
        val first = listOf(title(1), title(1), title(2))
        val selected = HeroBanner.items(listOf(row("a", first), row("b", (1..12).map(::title))), null) { true }
        assertEquals(10, selected.size)
        assertEquals(10, selected.map { it.mediaKey() }.distinct().size)
    }

    @Test fun smallAndEmptyCatalogsNeverRepeatTitlesOrMixInAnotherSelectedSource() {
        val rows = listOf(row("small", listOf(title(1), title(2))), row("other", (3..20).map(::title)))
        assertEquals(2, HeroBanner.items(rows, "small") { true }.size)
        assertTrue(HeroBanner.items(listOf(row("empty", emptyList())) + rows, "empty") { true }.isEmpty())
        assertTrue(HeroBanner.items(emptyList(), null) { true }.isEmpty())
    }

    @Test fun unavailableCatalogFallsBackToCurrentAllowedRowsWithoutLeakingOldTitles() {
        val selected = HeroBanner.items(listOf(row("new-profile", (11..25).map(::title))), "old-profile") { it.id != "tt11" }
        assertEquals((12..21).map { "tt$it" }, selected.map { it.id })
    }

    @Test fun rightEdgePagingWrapsAndConsumesOnlyOneAdvancePerPhysicalPress() {
        assertEquals(0, HeroBanner.next(9, 10))
        assertEquals(5, HeroBanner.next(4, 10))
        assertEquals(0, HeroBanner.next(2, 0))
        assertEquals(0, HeroBanner.next(0, 1))
        assertEquals(0, HeroBanner.index(-9, 10))
        assertEquals(3, HeroBanner.index(9, 4))
        assertTrue(HeroBanner.pageOnPress(true, 0, 10))
        assertFalse(HeroBanner.pageOnPress(false, 0, 10))
        assertFalse(HeroBanner.pageOnPress(true, 1, 10))
        assertFalse(HeroBanner.pageOnPress(true, 0, 1))
    }

    @Test fun leftEdgePagingWrapsAndIsTheInverseOfRightPaging() {
        assertEquals(9, HeroBanner.previous(0, 10))
        assertEquals(3, HeroBanner.previous(4, 10))
        assertEquals(0, HeroBanner.previous(2, 0))
        assertEquals(0, HeroBanner.previous(0, 1))
        for (count in 2..10) for (index in 0 until count) {
            assertEquals(index, HeroBanner.next(HeroBanner.previous(index, count), count))
            assertEquals(index, HeroBanner.previous(HeroBanner.next(index, count), count))
        }
    }

    @Test fun standardModeNeverRequestsFanartEvenWithASavedKey() = runBlocking {
        val visited = java.util.Collections.synchronizedList(mutableListOf<String>())
        val repo = HeroArtworkRepository(null, { url -> visited += url; if (url.toHttpUrl().host == "webservice.fanart.tv") fanart else standard })
        val result = repo.resolve(title(1).copy(tmdbId = 42), "en", "tmdb-dummy", "fanart-dummy", HeroArtworkMode.STANDARD)
        assertTrue(result.backgrounds.single().contains("standard.jpg"))
        assertEquals(1, visited.size)
        assertTrue(visited.none { it.toHttpUrl().host == "webservice.fanart.tv" })
    }

    @Test fun changingArtworkModesDoesNotReuseTheOtherModesCachedImages() = runBlocking {
        val visited = java.util.Collections.synchronizedList(mutableListOf<String>())
        val repo = HeroArtworkRepository(null, { url -> visited += url; if (url.toHttpUrl().host == "webservice.fanart.tv") fanart else standard })
        val meta = title(1).copy(tmdbId = 42)
        val a = repo.resolve(meta, "en", "tmdb-dummy", "fanart-dummy", HeroArtworkMode.FANART)
        val b = repo.resolve(meta, "en", "tmdb-dummy", "fanart-dummy", HeroArtworkMode.STANDARD)
        assertEquals("https://assets.fanart.tv/fanart.jpg", a.backgrounds.first())
        assertTrue(b.backgrounds.first().contains("standard.jpg"))
        val calls = visited.size
        assertEquals(a, repo.resolve(meta, "en", "tmdb-dummy", "fanart-dummy", HeroArtworkMode.FANART))
        assertEquals(b, repo.resolve(meta, "en", "tmdb-dummy", "fanart-dummy", HeroArtworkMode.STANDARD))
        assertEquals(calls, visited.size)
    }

    @Test fun unavailableFanartFallsBackToStandardWithoutChangingMediaIdentity() = runBlocking {
        val repo = HeroArtworkRepository(null, { url -> if (url.toHttpUrl().host == "webservice.fanart.tv") throw IOException() else standard })
        val meta = title(1).copy(tmdbId = 42, serverRef = "native", progress = .4f)
        val art = repo.resolve(meta, "en", "tmdb-dummy", "fanart-dummy", HeroArtworkMode.FANART)
        assertTrue(art.backgrounds.first().contains("standard.jpg"))
        try {
            app.ember.tv.data.AddonMetadata.first = false // #263: the old order, public artwork first
            assertEquals(meta.copy(background = art.backgrounds.first(), logo = art.logo), art.applyTo(meta))
        } finally { app.ember.tv.data.AddonMetadata.first = true }
    }

    @Test fun standardModeWithoutTmdbDoesNotSecretlyUseFanart() = runBlocking {
        var calls = 0
        val repo = HeroArtworkRepository(null, { calls++; fanart })
        assertTrue(repo.resolve(title(1), "en", "", "fanart-dummy", HeroArtworkMode.STANDARD).empty)
        assertEquals(0, calls)
        assertFalse(repo.resolve(title(1), "en", "", "fanart-dummy", HeroArtworkMode.FANART).empty)
        assertEquals(1, calls)
    }

    @Test fun artworkAndCatalogPreferencesSurviveReloadAndBackupRestore() {
        val from = PersistenceContext(temp.newFolder()); val prefs = Prefs(from)
        assertEquals(HeroArtworkMode.STANDARD, prefs.heroArtworkMode); assertNull(prefs.heroCatalog)
        prefs.heroArtworkMode = HeroArtworkMode.FANART; prefs.heroCatalog = "chosen-row"
        assertEquals("chosen-row", Prefs(from).heroCatalog)
        val to = PersistenceContext(temp.newFolder())
        BackupPersistence(to, FixtureSecrets()).restore(BackupPersistence(from, FixtureSecrets()).snapshot())
        assertEquals(HeroArtworkMode.FANART, Prefs(to).heroArtworkMode)
        assertEquals("chosen-row", Prefs(to).heroCatalog)
        HeroBanner.items(listOf(row("other", listOf(title(1)))), Prefs(to).heroCatalog) { true }
        assertEquals("chosen-row", Prefs(to).heroCatalog)
        Prefs(to).heroCatalog = ""
        assertNull(Prefs(to).heroCatalog)
    }

    @Test fun unknownOrCorruptLocalArtworkChoicesHaveSafeDefaults() {
        val ctx = PersistenceContext(temp.newFolder()); val prefs = Prefs(ctx)
        prefs.heroArtworkMode = "future-mode"
        assertEquals(HeroArtworkMode.STANDARD, prefs.heroArtworkMode)
        ctx.getSharedPreferences("ember", 0).edit().putBoolean("heroArtworkMode", true).putBoolean("heroCatalog", true).commit()
        assertEquals(HeroArtworkMode.STANDARD, Prefs(ctx).heroArtworkMode)
        assertNull(Prefs(ctx).heroCatalog)
    }

    @Test fun wrongTypedHeroBackupIsRejectedBeforeApplyingAnySettings() {
        val from = PersistenceContext(temp.newFolder())
        val valid = BackupPersistence(from, FixtureSecrets()).snapshot()
        val to = PersistenceContext(temp.newFolder()); Prefs(to).heroArtworkMode = HeroArtworkMode.FANART
        for (key in listOf("heroArtworkMode", "heroCatalog")) {
            val broken = JsonObject(valid + ("prefs.ember" to JsonObject(valid.getValue("prefs.ember").jsonObject +
                (key to buildJsonObject { put("t", "b"); put("v", true) }))))
            assertTrue(runCatching { BackupPersistence(to, FixtureSecrets()).restore(broken) }.isFailure)
            assertEquals(HeroArtworkMode.FANART, Prefs(to).heroArtworkMode)
        }
    }

    @Test fun settingsSearchFindsTheActualHeroSectionForBothArtworkAndCatalog() {
        for (query in listOf("Fanart", "hero catalog", "hero dots")) {
            assertTrue(SettingDefinitions.matching(query).any { it.id == "hero-banner" && it.tab == SettingsTab.HOME_ROWS })
        }
    }
}
