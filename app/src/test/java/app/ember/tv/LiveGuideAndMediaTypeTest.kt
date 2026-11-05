package app.ember.tv

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.*
import androidx.media3.common.MimeTypes
import androidx.tv.material3.MaterialTheme
import app.ember.tv.data.*
import app.ember.tv.player.PlaybackErrors
import app.ember.tv.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.*
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Locale

class LiveGuideAndMediaTypeTest {
    @get:Rule val temp = TemporaryFolder()
    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun onClear() {}
        override fun insertTopDown(index: Int, instance: Unit) {}
        override fun insertBottomUp(index: Int, instance: Unit) {}
        override fun move(from: Int, to: Int, count: Int) {}
        override fun remove(index: Int, count: Int) {}
    }
    private fun contrast(a: Color, b: Color): Double = (maxOf(a.luminance(), b.luminance()) + .05) / (minOf(a.luminance(), b.luminance()) + .05)

    @Test fun liveActuallyInheritsReadableDayNightAndOledColoursForEveryTheme() {
        val oldDay = Ui.day; val oldOled = Ui.oled; val oldLight = Ui.light
        try {
            Ui.light = false
            for (accent in Accents.indices) for (day in listOf(false, true)) for (oled in listOf(false, true)) {
                Ui.day = day; Ui.oled = oled
                val recomposer = Recomposer(Dispatchers.Unconfined)
                val composition = Composition(EmptyApplier(), recomposer)
                var captured: List<Color>? = null
                try {
                    composition.setContent {
                        EmberTheme(accent) {
                            LivePageTheme {
                                captured = listOf(Ink, Fg, PillColor, OnPillColor,
                                    LiveColors.Background, LiveColors.Text, LiveColors.Focus, LiveColors.OnFocus,
                                    MaterialTheme.colorScheme.background)
                            }
                        }
                    }
                    val c = captured ?: error("Live theme was not composed")
                    assertEquals(c[0], c[4]); assertEquals(c[1], c[5]); assertEquals(c[2], c[6]); assertEquals(c[3], c[7]); assertEquals(c[0], c[8])
                    assertTrue("Live text contrast accent=$accent day=$day oled=$oled", contrast(c[5], c[4]) >= 4.5)
                    assertTrue("Live focus contrast accent=$accent day=$day oled=$oled", contrast(c[7], c[6]) >= 4.5)
                } finally { composition.dispose(); recomposer.cancel() }
            }
        } finally { Ui.day = oldDay; Ui.oled = oldOled; Ui.light = oldLight }
    }

    @Test fun featuredFocusNeverTargetsAHiddenEmptyOrAddonOnlyRow() {
        assertTrue(liveFeaturedAvailable(true, true, true, false, "source"))
        assertTrue(liveFeaturedAvailable(true, true, true, false, "favourites"))
        assertFalse(liveFeaturedAvailable(true, true, true, false, "addons"))
        assertFalse(liveFeaturedAvailable(false, true, true, false, "source"))
        assertFalse(liveFeaturedAvailable(true, false, true, false, "source"))
        assertFalse(liveFeaturedAvailable(true, true, false, false, "source"))
        assertFalse(liveFeaturedAvailable(true, true, true, true, "source"))
    }

    @Test fun iconHintsStayVisibleAtWindowEdgesAndInRtl() {
        val position = ToolbarHintPosition(6)
        val window = IntSize(960, 540); val popup = IntSize(160, 30)
        assertEquals(IntOffset(41, 68), position.calculatePosition(IntRect(100, 20, 142, 62), window, LayoutDirection.Ltr, popup))
        assertEquals(IntOffset(41, 454), position.calculatePosition(IntRect(100, 490, 142, 532), window, LayoutDirection.Rtl, popup))
        assertEquals(IntOffset(0, 68), position.calculatePosition(IntRect(0, 20, 42, 62), window, LayoutDirection.Ltr, popup))
        assertEquals(IntOffset(800, 68), position.calculatePosition(IntRect(918, 20, 960, 62), window, LayoutDirection.Rtl, popup))
        assertEquals(IntOffset.Zero, position.calculatePosition(IntRect.Zero, IntSize(100, 60), LayoutDirection.Ltr, IntSize(200, 90)))
    }

    @Test fun addonHeadersCannotPassControlOrUnicodeValuesThatOkHttpRejects() {
        for (bad in (0..31).filter { it != 9 }.map(Int::toChar) + listOf('\u007f', '\u00e9')) {
            assertTrue("control ${bad.code}", StreamHeaders.clean(listOf("X-Fixture" to "a${bad}b")).isEmpty())
        }
        val safe = StreamHeaders.clean(listOf("User-Agent" to "Fixture/1", "X-Tab" to "a\tb", "Referer" to "https://example.test/"))
        assertEquals(3, safe.size)
        val request = Request.Builder().url("https://example.test/")
        safe.forEach { (name, value) -> request.header(name, value) }
        assertEquals("a\tb", request.build().header("X-Tab"))
    }

    @Test fun reservedHeaderChecksDoNotDependOnTurkishLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(mapOf("ORIGIN" to "https://example.test"), StreamHeaders.clean(listOf(
                "CONNECTION" to "keep-alive", "TRANSFER-ENCODING" to "chunked", "ORIGIN" to "https://example.test")))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun unrelatedQueryValuesAndFolderNamesDoNotMisclassifyMoviesAsHls() {
        listOf("https://example.test/.m3u8-cache/movie.mp4", "https://example.test/movie.mp4?token=hls",
            "https://example.test/movie.mkv?signature=master.m3u8", "https://example.test/watch?user=dash").forEach {
            assertNull(it, PlaybackErrors.mimeHint(it))
        }
    }

    @Test fun explicitStreamFormatQueriesStillRecogniseHlsAndDash() {
        assertEquals(MimeTypes.APPLICATION_M3U8, PlaybackErrors.mimeHint("https://example.test/play?OUTPUT=m3u8"))
        assertEquals(MimeTypes.APPLICATION_M3U8, PlaybackErrors.mimeHint("https://example.test/play?mime=application%2Fvnd.apple.mpegurl"))
        assertEquals(MimeTypes.APPLICATION_MPD, PlaybackErrors.mimeHint("https://example.test/play?format=dash"))
        assertEquals(MimeTypes.APPLICATION_MPD, PlaybackErrors.mimeHint("https://example.test/play?mime=application%2Fdash%2Bxml"))
    }

    @Test fun betaUpdatePreferenceRoundTripsThroughTheActualBackupStore() {
        val from = PersistenceContext(temp.newFolder()); Prefs(from).updateBeta = true
        val payload = BackupPersistence(from, FixtureSecrets()).snapshot()
        val to = PersistenceContext(temp.newFolder())
        BackupPersistence(to, FixtureSecrets()).restore(payload)
        assertTrue(Prefs(to).updateBeta)
    }

    @Test fun mistypedBetaPreferenceIsRejectedBeforeChangingOtherSettings() {
        val source = PersistenceContext(temp.newFolder()); val destination = PersistenceContext(temp.newFolder())
        val target = BackupPersistence(destination, FixtureSecrets()); Prefs(destination).menuPosition = "side"
        val payload = BackupPersistence(source, FixtureSecrets()).snapshot()
        val invalid = JsonObject(payload + ("prefs.ember" to JsonObject(payload.getValue("prefs.ember").jsonObject +
            ("updateBeta" to buildJsonObject { put("t", "s"); put("v", "true") }))))
        assertThrows(Backup.BackupException::class.java) { target.restore(invalid) }
        assertEquals("side", Prefs(destination).menuPosition)
    }
}
