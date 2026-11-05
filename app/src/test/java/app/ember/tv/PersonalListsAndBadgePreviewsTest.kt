package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.state.HomeSectionsState
import app.ember.tv.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

class PersonalListsAndBadgePreviewsTest {
    @get:Rule val temp = TemporaryFolder()
    private val server = Meta(id = "plex:s:10", type = "movie", name = "Server title", serverRef = "plex|s|10")
    @Before fun reset() = LocalLibrary.resetForTest()
    @After fun cleanup() = LocalLibrary.resetForTest()

    @Test fun serverIdentitySurvivesEnrichmentWithoutJoiningDifferentLibrariesOrKinds() {
        val enriched = server.copy(id = "tt10", name = "Translated title", releaseInfo = "2020")
        assertTrue(server.sameTitle(enriched))
        assertTrue(enriched in TitleIndex(listOf(server)))
        assertFalse(enriched.copy(type = "series") in TitleIndex(listOf(server)))
        assertFalse(server.copy(id = "other", name = "Different", serverRef = "plex|another-server|10").sameTitle(enriched))
        assertFalse(Meta(id = "a", type = "movie", name = "A", serverRef = "").sameTitle(Meta(id = "b", type = "movie", name = "B", serverRef = "")))
    }

    @Test fun episodeAndShowReferencesMatchEvenWhenTheirNamesAndIdsDiffer() {
        val show = Meta(id = "show", type = "series", name = "Show", serverRef = "jellyfin|account|series")
        val episode = show.copy(id = "episode", name = "Episode title", serverRef = "jellyfin|account|ep1", serverSeriesRef = show.serverRef)
        assertTrue(show.sameTitle(episode))
        assertTrue(episode.sameTitle(show))
        assertTrue(episode in TitleIndex(listOf(show)))
        assertTrue(show in TitleIndex(listOf(episode)))
    }

    @Test fun removalHidesAllCopiesButKeepsHistoryAndDoesNotMarkWatched() {
        LocalLibrary.started("resume", server); LocalLibrary.progress("resume", .4f)
        LocalLibrary.hideList("continue", server)
        assertTrue(LocalLibrary.inProgress.isEmpty())
        assertTrue(LocalLibrary.isListHidden("continue", server.copy(id = "enriched", name = "Other name")))
        assertEquals(1, LocalLibrary.recent.size)
        assertFalse(LocalLibrary.isFinished("resume"))
        LocalLibrary.restoreList("continue")
        assertEquals(.4f, LocalLibrary.inProgress.single().progress!!, 0f)
    }

    @Test fun intentionalPlaybackRevealsProgressWithoutReAddingWatchlist() {
        LocalLibrary.hideList("continue", server); LocalLibrary.hideList("watchlist", server)
        LocalLibrary.started("resume", server)
        assertFalse(LocalLibrary.isListHidden("continue", server))
        assertTrue(LocalLibrary.isListHidden("watchlist", server))
        LocalLibrary.revealList("watchlist", server)
        assertFalse(LocalLibrary.isListHidden("watchlist", server))
    }

    @Test fun hiddenServerItemsSurviveRestartAndStayInsideTheirLocalProfile() = runBlocking {
        val f = temp.newFile("library.json").apply { writeText("{}") }
        LocalLibrary.initFile(f); LocalLibrary.awaitReady()
        LocalLibrary.selectScope("local:a")
        LocalLibrary.hideList("continue", server); LocalLibrary.hideList("watchlist", server)
        LocalLibrary.snapshotFiles { Unit }
        LocalLibrary.selectScope("local:b"); assertFalse(LocalLibrary.isListHidden("continue", server))
        LocalLibrary.resetForTest(); LocalLibrary.initFile(f); LocalLibrary.awaitReady()
        LocalLibrary.selectScope("local:a")
        assertTrue(LocalLibrary.isListHidden("continue", server))
        assertTrue(LocalLibrary.isListHidden("watchlist", server))
        LocalLibrary.selectScope(LocalActivity.SHARED); assertFalse(LocalLibrary.isListHidden("continue", server))
    }

    @Test fun sourceSelectionsAreIndependentPerListAndProfileAndRoundTripThroughBackup() {
        val from = PersistenceContext(temp.newFolder())
        val prefs = Prefs(from)
        val sections = HomeSectionsState(prefs, {}, {})
        sections.toggleSource("adult", "watchlist", "plex")
        assertFalse(sections.sourceEnabled("adult", "watchlist", "plex"))
        assertTrue(sections.sourceEnabled("adult", "continue", "plex"))
        assertTrue(sections.sourceEnabled("other", "watchlist", "plex"))
        val to = PersistenceContext(temp.newFolder())
        BackupPersistence(to, FixtureSecrets()).restore(BackupPersistence(from, FixtureSecrets()).snapshot())
        assertEquals(prefs.personalListSourcesOff, Prefs(to).personalListSourcesOff)
        assertEquals("set", PreferenceKeys.expectedType("ember", "personalListSourcesOff"))
    }

    @Test fun originClassificationIncludesServerTitlesSavedLocallyAndNamedTrackers() {
        assertEquals("plex", PersonalListSources.source(server, "ember"))
        assertEquals("jellyfin", PersonalListSources.source(server.copy(serverRef = "jellyfin|a|i"), "ember"))
        assertEquals("simkl", PersonalListSources.source(server.copy(id = "tt1", serverRef = null, progressSource = "Simkl"), "other"))
        assertEquals("mal", PersonalListSources.source(server.copy(id = "tt1", serverRef = null, progressSource = "MyAnimeList"), "other"))
        assertEquals("ember", PersonalListSources.source(server.copy(id = "tt1", serverRef = null, progressSource = "Trakt"), "ember"))
        assertEquals("plex", PersonalListSources.source(server.copy(id = "plexwatchlist:10", serverRef = null), "other"))
    }

    @Test fun customAndBasicPreviewsDoNotChangeTheActivePackAndRetainAllStyles() = runBlocking {
        val context = PersistenceContext(temp.newFolder())
        val revision = Badges.rev
        val basic = Badges.preview(context, Badges.LEGACY)
        assertTrue(basic.any { it.name == "4K" }); assertTrue(basic.all { it.imageURL == null })
        assertTrue(Badges.preview(context, Badges.CUSTOM).isEmpty())
        assertNull(Badges.importText(context, """{"filters":[{"name":"Styled","imageURL":"https://example.com/badge.png","tagColor":"#FF123456","textColor":"#ABCDEF","borderColor":"#456789","borderSize":3}]}"""))
        val badge = Badges.preview(context, Badges.CUSTOM).single()
        assertEquals(3f, badge.appearance().borderWidth, 0f)
        assertEquals(0xFF123456.toInt(), badge.appearance().background)
        assertEquals("https://example.com/badge.png", badge.imageURL)
        assertEquals(revision, Badges.rev)
    }

    @Test fun shadowBackgroundMatchesCinemaWithoutChangingItsNeutralFocusColour() {
        assertEquals(EmberXColors.Background, ShadowEmberColors.Background)
        assertEquals(ShadowEmberColors.Background, emberColorScheme(ColorThemes.SHADOW_EMBER, false, false).background)
        assertEquals(ShadowEmberColors.Focus, emberColorScheme(ColorThemes.SHADOW_EMBER, false, false).primary)
    }
}
