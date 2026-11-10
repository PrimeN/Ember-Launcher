package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.ui.MenuSizing
import app.ember.tv.ui.SETTINGS_INDEX
import app.ember.tv.ui.SettingsTab
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CollectionMotionAndProfileMenuTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun motionIsOffUnlessEveryForegroundFocusAndPreferenceConditionAllowsIt() {
        assertTrue(CollectionMotion.allowed(true, true, true, false))
        assertFalse(CollectionMotion.allowed(false, true, true, false))
        assertFalse(CollectionMotion.allowed(true, false, true, false))
        assertFalse(CollectionMotion.allowed(true, true, false, false))
        assertFalse(CollectionMotion.allowed(true, true, true, true))
        assertFalse(CollectionMotion.allowed(true, true, true, false, kids = true))
    }

    @Test fun directVideoAndGifLinksAreClassifiedWithoutBeingConfusedByQueryStrings() {
        assertEquals(CollectionMediaKind.VIDEO, CollectionMotion.kind("https://example.test/video.MP4?file=.gif", false))
        assertEquals(CollectionMediaKind.IMAGE, CollectionMotion.kind("https://example.test/image.GIF?file=.mp4", true))
        for (type in listOf("webm", "m3u8", "mpd", "mov"))
            assertEquals(CollectionMediaKind.VIDEO, CollectionMotion.kind("https://example.test/clip.$type", false))
        assertEquals(CollectionMediaKind.IMAGE, CollectionMotion.kind("https://example.test/cover.webp", true))
    }

    @Test fun extensionlessMediaUsesItsSlotHintAndGetsAtMostOneOtherDecoderAttempt() {
        val url = "https://example.test/asset?id=123"
        assertEquals(CollectionMediaKind.IMAGE, CollectionMotion.kind(url, false))
        assertEquals(CollectionMediaKind.VIDEO, CollectionMotion.kind(url, true))
        assertEquals(1, CollectionMotion.nextAttempt(url, 0))
        assertEquals(2, CollectionMotion.nextAttempt(url, 1))
        assertEquals(2, CollectionMotion.nextAttempt(url, 2))
    }

    @Test fun knownInvalidMediaDoesNotStartADecoderFallbackLoop() {
        assertEquals(2, CollectionMotion.nextAttempt("https://example.test/missing.gif", 0))
        assertEquals(2, CollectionMotion.nextAttempt("https://example.test/missing.mp4", 0))
        assertFalse(CollectionMotion.remote(null))
        assertFalse(CollectionMotion.remote("javascript:alert(1)"))
        assertFalse(CollectionMotion.remote("file:///private/movie.mp4"))
        assertTrue(CollectionMotion.remote("http://192.168.1.2/movie.mp4"))
    }

    @Test fun stillAndAnimatedImagesNeverShareAMemoryCacheEntry() {
        assertNotEquals(CollectionMotion.imageCacheKey(true, false), CollectionMotion.imageCacheKey(false, false))
        assertEquals(CollectionMotion.imageCacheKey(false, false), CollectionMotion.imageCacheKey(false, false))
    }

    @Test fun lowAndHighImageBudgetsNeverReuseEachOthersAnimatedDrawable() {
        assertNotEquals(CollectionMotion.imageCacheKey(true, true), CollectionMotion.imageCacheKey(true, false))
        assertNotEquals(CollectionMotion.imageCacheKey(false, true), CollectionMotion.imageCacheKey(false, false))
        assertTrue(CollectionMotion.imageBound(true) < CollectionMotion.imageBound(false))
        assertTrue(CollectionMotion.imageBound(false) <= 960)
    }

    @Test fun replacingAPreviewReleasesTheOldDecoderBeforeItsStaleDisposal() {
        val a = Any(); val b = Any(); val released = mutableListOf<Any>()
        val owner = ExclusiveCollectionPreview<Any> { released += it }
        owner.claim(a); owner.claim(b)
        assertEquals(listOf(a), released)
        owner.drop(a)
        assertEquals(listOf(a), released)
        owner.drop(b)
        assertEquals(listOf(a, b), released)
    }

    @Test fun repeatedClaimAndDisposalNeverDoubleReleaseADecoder() {
        val a = Any(); val released = mutableListOf<Any>()
        val owner = ExclusiveCollectionPreview<Any> { released += it }
        owner.claim(a); owner.claim(a)
        assertTrue(released.isEmpty())
        owner.drop(a); owner.drop(a)
        assertEquals(listOf(a), released)
    }

    @Test fun reentrantOldDecoderCleanupCannotDisposeTheNewOwner() {
        val a = Any(); val b = Any(); val released = mutableListOf<Any>()
        lateinit var owner: ExclusiveCollectionPreview<Any>
        owner = ExclusiveCollectionPreview { value -> released += value; owner.drop(value) }
        owner.claim(a); owner.claim(b); owner.drop(b)
        assertEquals(listOf(a, b), released)
    }

    @Test fun animationOptInKeepsTheExistingPreferenceAndSurvivesBackupRestore() {
        val from = PersistenceContext(temp.newFolder())
        assertFalse(Prefs(from).collectionHeroPreviews)
        for (enabled in listOf(true, false)) {
            Prefs(from).collectionHeroPreviews = enabled
            assertEquals(enabled, Prefs(from).collectionHeroPreviews)
            val to = PersistenceContext(temp.newFolder())
            BackupPersistence(to, FixtureSecrets()).restore(BackupPersistence(from, FixtureSecrets()).snapshot())
            assertEquals(enabled, Prefs(to).collectionHeroPreviews)
        }
    }

    @Test fun videoAndGifFolderFieldsAndLocalDisableChoiceSurviveEditingAndRoundTrip() {
        val source = CollectionSource(provider = "tmdb", tmdbSourceType = "LIST", tmdbId = 42)
        val folder = CollectionFolder(id = "f", title = "Folder", coverImageUrl = "https://example.test/still.gif",
            focusGifUrl = "https://example.test/focus.mp4", focusGifEnabled = false,
            heroVideoUrl = "https://example.test/hero.gif", sources = listOf(source))
        val original = MediaCollection(id = "c", title = "Collection", folders = listOf(folder))
        val encoded = Collections.encode(listOf(original.copy(title = "Renamed")))
        val loaded = Collections.parse(encoded)!!.single()
        assertEquals("Renamed", loaded.title)
        assertEquals(folder, loaded.folders.single())
    }

    @Test fun moreProfilesScrollInsteadOfMakingTheWholeMenuWider() {
        val two = MenuSizing.profileAvatarStripWidth(2)
        assertEquals(two, MenuSizing.profileAvatarStripWidth(20))
        assertEquals(two, MenuSizing.profileAvatarStripWidth(Int.MAX_VALUE))
        assertTrue(MenuSizing.profileAvatarStripWidth(1) < two)
        assertTrue(MenuSizing.profileMenuWidth(two + 28.dp, 150.dp, 1f) <= 150.dp)
        assertTrue(MenuSizing.profileMenuWidth(two + 28.dp, 960.dp, 1.5f) <= 328.dp)
    }

    @Test fun collectionAnimationSettingIsDiscoverableFromSettingsSearch() {
        // #163: Collections moved to Settings > Home > Widgets & collections.
        val text = SETTINGS_INDEX.single { it.first == SettingsTab.WIDGETS }.second.lowercase()
        listOf("animated collections", "video", "gif", "on", "off").forEach { assertTrue(it in text) }
    }
}
