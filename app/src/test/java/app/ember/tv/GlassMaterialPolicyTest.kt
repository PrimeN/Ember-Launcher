package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GlassMaterialPolicyTest {
    @get:Rule val files = TemporaryFolder()
    private val high = RenderOptions(glass = true, quality = "high")

    @Test fun olderDevicesUsePreparedGlassWithoutRequiringShaderApis() {
        for (sdk in 29..32) assertEquals(GlassMode.FROSTED, GlassPolicy.mode(high, sdk))
        assertEquals(GlassMode.LIQUID, GlassPolicy.mode(high, 33))
    }
    @Test fun disabledLowMemoryAndPressureAlwaysAvoidHeavyEffects() {
        for (sdk in listOf(29, 33, 37)) {
            assertEquals(GlassMode.TINT, GlassPolicy.mode(high.copy(glass = false), sdk))
            assertEquals(GlassMode.TINT, GlassPolicy.mode(high.copy(device = "low"), sdk))
            assertEquals(GlassMode.TINT, GlassPolicy.mode(high, sdk, lowRam = true))
            assertEquals(GlassMode.TINT, GlassPolicy.mode(high, sdk, pressured = true))
        }
    }
    @Test fun explicitGlassChoicesAreIndependentFromImageResolution() {
        val tint = high.copy(glassMethod = "tint")
        val frosted = high.copy(glassMethod = "cached")
        val liquid = high.copy(quality = "economy", device = "custom", glassMethod = "liquid")
        assertEquals(GlassMode.TINT, GlassPolicy.mode(tint, 35))
        assertEquals(high.heroMaxWidth, tint.heroMaxWidth)
        assertEquals(GlassMode.FROSTED, GlassPolicy.mode(frosted, 35))
        assertEquals(high.heroMaxWidth, frosted.heroMaxWidth)
        assertEquals(GlassMode.LIQUID, GlassPolicy.mode(liquid, 35))
        assertEquals(1280, liquid.heroMaxWidth)
        assertEquals(GlassMode.FROSTED, GlassPolicy.mode(liquid.copy(device = "medium"), 35))
    }
    @Test fun samplesAreBoundedEvenForPortraitAnd4kArtwork() {
        for ((width, height) in listOf(3840 to 2160, 2160 to 3840, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE, 10 to 20)) {
            val size = GlassPolicy.sampleSize(width, height)!!
            assertTrue(size.first in 1..384 && size.second in 1..384)
            assertTrue(size.first.toLong() * size.second * 4 <= 384L * 384 * 4)
        }
        assertNull(GlassPolicy.sampleSize(0, 100)); assertNull(GlassPolicy.sampleSize(100, -1))
    }
    @Test fun cropProjectionMatchesLightHeroAndKeepsWindowCoordinates() {
        val frame = HeroFraming.glassProjection(200, 100, HeroViewport(20f, 40f, 100f, 100f, crop = true), HeroFocus(.75f, .3f))!!
        assertEquals(-55f, frame.left, .001f); assertEquals(40f, frame.top, .001f)
        assertEquals(200f, frame.width, .001f); assertEquals(100f, frame.height, .001f)
        assertNull(HeroFraming.glassProjection(100, 100, HeroViewport(0f, 0f, Float.NaN, 100f, true)))
    }
    @Test fun glassChoicesSurviveBackupAndLegacyValuesRemainValid() {
        for (choice in GlassStyle.choices.map { it.first }) {
            val from = PersistenceContext(files.newFolder()); val to = PersistenceContext(files.newFolder())
            Prefs(from).navigationGlass = true; Prefs(from).navigationGlassMethod = choice
            BackupPersistence(to, FixtureSecrets()).restore(BackupPersistence(from, FixtureSecrets()).snapshot())
            assertEquals(choice, Prefs(to).navigationGlassMethod); assertTrue(Prefs(to).navigationGlass)
        }
        assertEquals("live", GlassStyle.normalize("unknown"))
        assertEquals("cached", GlassStyle.normalize("cached"))
    }
    @Test fun popupOffsetsAndFocusZoomDoNotMoveTheSampledBackground() {
        val frame=HeroProjection(100f,200f,800f,400f)
        val normal=GlassPolicy.localFrame(frame,10f,20f,GlassPlacement(50f,100f))
        val zoomed=GlassPolicy.localFrame(frame,10f,20f,GlassPlacement(50f,100f,2f,2f))
        assertEquals(HeroProjection(60f,120f,800f,400f),normal)
        assertEquals(HeroProjection(30f,60f,400f,200f),zoomed)
    }
    @Test fun readableMenusKeepMoreTintThanSmallGlassControls() {
        for (day in listOf(true, false)) {
            assertTrue(GlassPolicy.tintAlpha(true, true, day) >= .8f)
            assertTrue(GlassPolicy.tintAlpha(true, false, day) > GlassPolicy.tintAlpha(false, false, day))
            assertTrue(GlassPolicy.tintAlpha(false, false, day) > GlassPolicy.tintAlpha(false, true, day))
        }
        assertTrue(SettingDefinitions.matching("liquid refraction").any { it.id == "artwork" })
    }
    @Test fun brightAndDarkArtworkReceiveContrastWithoutChangingImageQuality() {
        assertEquals(.3f,GlassPolicy.readableTint(.3f,0f,false),.001f)
        assertTrue(GlassPolicy.readableTint(.3f,1f,false)>=.85f)
        assertTrue(GlassPolicy.readableTint(.5f,0f,true)>=.77f)
        assertEquals(.5f,GlassPolicy.readableTint(.5f,1f,true),.001f)
        assertTrue(high.copy(glassMethod="tint",custom=CustomPerformance(heroAnalysis=false)).hardwareHero)
    }
}
