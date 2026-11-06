package app.ember.tv

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.drawable.Drawable
import app.ember.tv.data.*
import app.ember.tv.ui.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FocusBackgroundTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun photoContrastOnlyAppliesToArtworkAndNotToDayThemeOrTileColours() {
        val media=FocusTile(FocusTileKind.MEDIA,"movie","art")
        val collection=FocusTile(FocusTileKind.COLLECTION,"collection","art")
        assertTrue(focusUsesArtwork(media,FocusBackground.ARTWORK,FocusBackground.OFF))
        assertTrue(focusUsesArtwork(collection,FocusBackground.OFF,FocusBackground.ARTWORK))
        assertFalse(focusUsesArtwork(media,FocusBackground.COLORS,FocusBackground.ARTWORK))
        assertFalse(focusUsesArtwork(media,FocusBackground.OFF,FocusBackground.ARTWORK))
        assertFalse(focusUsesArtwork(FocusTile(FocusTileKind.APP,"app",null),FocusBackground.ARTWORK,FocusBackground.ARTWORK))
        assertFalse(focusUsesArtwork(null,FocusBackground.ARTWORK,FocusBackground.ARTWORK))
    }
    private class FixtureDrawable : Drawable() {
        override fun draw(canvas: Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Legacy Drawable contract") override fun getOpacity() = -3
    }

    @Test fun palettesIgnoreTransparentBlackBordersAndWhiteLogos() {
        val red = 0xffd82028.toInt()
        val pixels = IntArray(250) { when { it < 100 -> 0; it < 150 -> 0xff000000.toInt(); it < 200 -> 0xffffffff.toInt(); else -> red } }
        val selected = TilePalette.color(pixels)!!
        assertTrue((selected shr 16 and 255) > 200)
        assertTrue((selected shr 8 and 255) < 60)
        assertEquals(255, selected ushr 24)
    }

    @Test fun colorfulRegionsBeatEquallyPopulatedGreyAndEmptyTilesHaveNoInventedColor() {
        val color = TilePalette.color(IntArray(100) { if (it < 50) 0xff606060.toInt() else 0xff2850d8.toInt() })!!
        assertTrue((color and 255) > 200)
        assertNull(TilePalette.color(intArrayOf()))
        assertNull(TilePalette.color(intArrayOf(0, 0xff000000.toInt(), 0xffffffff.toInt())))
    }

    @Test fun largeGreyAreasStillYieldAValidNeutralPalette() {
        val grey = TilePalette.color(IntArray(100) { 0xff606060.toInt() })!!
        assertEquals(grey shr 16 and 255, grey shr 8 and 255)
        assertEquals(grey shr 8 and 255, grey and 255)
    }

    @Test fun lateImagesCannotReplaceAnotherFocusedTileOrReturnAfterClear() {
        val state = FocusBackdropState()
        val old = FocusTile(FocusTileKind.MEDIA, "a", "a.jpg")
        val current = FocusTile(FocusTileKind.COLLECTION, "b", "b.jpg")
        val image = FocusTileImage(FixtureDrawable(), "fixture-image")
        state.focus(old, null)
        state.focus(current, null)
        state.loaded(old, image)
        assertNull(state.image)
        assertEquals(current, state.tile)
        state.loaded(current, image)
        assertSame(image, state.image)
        state.clear()
        state.loaded(current, image)
        assertNull(state.tile)
        assertNull(state.image)
    }

    @Test fun aNewFocusImmediatelyDropsThePreviousTilesImage() {
        val state = FocusBackdropState()
        val image = FocusTileImage(FixtureDrawable(), "first")
        state.focus(FocusTile(FocusTileKind.APP, "app", null), image)
        state.focus(FocusTile(FocusTileKind.MEDIA, "movie", "movie.jpg"), null)
        assertNull(state.image)
        assertEquals(FocusTileKind.MEDIA, state.tile?.kind)
    }

    @Test fun defaultsAreColorsAndAllThreeChoicesPersistAndRoundTrip() {
        val from = PersistenceContext(temp.newFolder()); val prefs = Prefs(from)
        assertTrue(prefs.appFocusColors)
        assertEquals(FocusBackground.COLORS, prefs.mediaFocusBackground)
        assertEquals(FocusBackground.COLORS, prefs.collectionFocusBackground)
        for (mode in FocusBackground.choices.map { it.first }) {
            prefs.appFocusColors = false; prefs.mediaFocusBackground = mode; prefs.collectionFocusBackground = mode
            val to = PersistenceContext(temp.newFolder())
            BackupPersistence(to, FixtureSecrets()).restore(BackupPersistence(from, FixtureSecrets()).snapshot())
            val restored = Prefs(to)
            assertFalse(restored.appFocusColors)
            assertEquals(mode, restored.mediaFocusBackground)
            assertEquals(mode, restored.collectionFocusBackground)
            assertEquals(mode, Prefs(from).mediaFocusBackground)
        }
    }

    @Test fun malformedLocalChoicesFallBackToColors() {
        val context = PersistenceContext(temp.newFolder()); val prefs = Prefs(context)
        prefs.mediaFocusBackground = "unknown"
        assertEquals(FocusBackground.COLORS, prefs.mediaFocusBackground)
        context.getSharedPreferences("ember", 0).edit().putBoolean("mediaFocusBackground", true)
            .putInt("collectionFocusBackground", 9).putString("appFocusColors", "wrong").commit()
        assertEquals(FocusBackground.COLORS, prefs.mediaFocusBackground)
        assertEquals(FocusBackground.COLORS, prefs.collectionFocusBackground)
        assertTrue(prefs.appFocusColors)
    }

    @Test fun wrongTypedBackupIsRejectedBeforeApplyingSettings() {
        val from = PersistenceContext(temp.newFolder())
        val valid = BackupPersistence(from, FixtureSecrets()).snapshot()
        val to = PersistenceContext(temp.newFolder()); Prefs(to).mediaFocusBackground = FocusBackground.ARTWORK
        for (key in listOf("mediaFocusBackground", "collectionFocusBackground", "appFocusColors")) {
            val broken = JsonObject(valid + ("prefs.ember" to JsonObject(valid.getValue("prefs.ember").jsonObject +
                (key to buildJsonObject { put("t", "i"); put("v", 3) }))))
            assertTrue(runCatching { BackupPersistence(to, FixtureSecrets()).restore(broken) }.isFailure)
            assertEquals(FocusBackground.ARTWORK, Prefs(to).mediaFocusBackground)
        }
    }

    @Test fun searchLinksToTheExactBackgroundSectionInBothInterfaces() {
        for (query in listOf("tile colors", "collections backdrop", "media rows", "app colors")) {
            assertTrue(SettingDefinitions.matching(query).any { it.id == "focus-backgrounds" && it.tab == SettingsTab.PERFORMANCE })
        }
        assertTrue("focus-backgrounds" in appearanceKeys("modern"))
        assertTrue("focus-backgrounds" in appearanceKeys("light"))
    }

    @Test fun openingRowsDoesNotFetchIdleArtworkUnlessSelectedButFeaturedHeroesKeepIt() {
        assertFalse(FocusBackground.idleArtwork(false, FocusBackground.COLORS))
        assertFalse(FocusBackground.idleArtwork(false, FocusBackground.OFF))
        assertTrue(FocusBackground.idleArtwork(false, FocusBackground.ARTWORK))
        for ((mode, _) in FocusBackground.choices) assertTrue(FocusBackground.idleArtwork(true, mode))
    }
}
