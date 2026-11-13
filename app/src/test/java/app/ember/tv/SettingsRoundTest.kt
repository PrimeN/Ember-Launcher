package app.ember.tv

import android.app.Application
import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.ember.tv.data.PreferenceKeys
import app.ember.tv.data.Prefs
import app.ember.tv.player.PlaybackConfig
import app.ember.tv.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w960dp-h540dp-land-mdpi")
class SettingsRoundTest {
    @get:Rule val compose = createComposeRule()

    @Before fun clean() = SettingsOptionIndex.clearForTest()

    /** #270: one row shows the current answer; the menu changes it. */
    @Test fun choiceIsOneValueRowWithAMenu() {
        var picked by mutableStateOf("large")
        compose.setContent {
            EmberTheme(0) {
                Section("Buffer") { Choice(listOf("normal" to "Normal", "large" to "Large (4K remux)", "huge" to "Huge"), picked) { picked = it } }
            }
        }
        compose.onNodeWithText("Large (4K remux)").assertExists()
        compose.onNodeWithText("Huge").assertDoesNotExist()
        compose.onNodeWithText("Large (4K remux)").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Huge").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals("huge", picked) }
    }

    /** #269: options drawn on a page are found by Search and lead to their section. */
    @Test fun drawnOptionsAreSearchable() {
        compose.setContent {
            EmberTheme(0) {
                CompositionLocalProvider(LocalSettingsPage provides SettingsTab.AUDIO_VIDEO) {
                    Section("Fixture section", id = "fixture-section") { ToggleRow("Fixture option zq", null, false) {} }
                }
            }
        }
        compose.runOnIdle {
            val hit = SettingsOptionIndex.search("zq").first { it.label == "Fixture option zq" }
            assertEquals(SettingsTab.AUDIO_VIDEO, hit.tab)
            assertEquals("fixture-section", hit.anchor)
            assertTrue(SettingsOptionIndex.search("fixture section").any { it.tab == SettingsTab.AUDIO_VIDEO })
        }
    }

    @Test fun seedCoversPagesNotYetOpened() {
        val labels = SettingsOptionSeed.entries.map { it.tab to it.label }
        assertTrue(SettingsTab.AUDIO_VIDEO to "Passthrough (bitstream)" in labels)
        assertTrue(labels.none { it.first == SettingsTab.SEARCH })
        SettingsOptionIndex.load(RuntimeEnvironment.getApplication())
        assertTrue(SettingsOptionIndex.search("passthrough").any { it.tab == SettingsTab.AUDIO_VIDEO })
    }

    /** #273: a page reset clears only that page's settings. */
    @Test fun resetClearsOnlyThatPage() {
        val app = RuntimeEnvironment.getApplication()
        val sp = app.getSharedPreferences("ember", Context.MODE_PRIVATE)
        val prefs = Prefs(app)
        sp.edit().putString(PreferenceKeys.THEME.name, "day").putInt(PreferenceKeys.ACCENT.name, 3)
            .putString(PreferenceKeys.ADDONS.name, "[\"https://example.test/manifest.json\"]").commit()
        prefs.playbackConfig = PlaybackConfig(buffer = "huge", resume = "start", audioPick = "language")
        SettingsReset.apply(app, SettingsTab.APPEARANCE)
        assertFalse(sp.contains(PreferenceKeys.THEME.name))
        assertFalse(sp.contains(PreferenceKeys.ACCENT.name))
        assertTrue(sp.contains(PreferenceKeys.ADDONS.name))
        SettingsReset.apply(app, SettingsTab.AUDIO_VIDEO)
        val c = Prefs(app).playbackConfig
        assertEquals("large", c.buffer)
        assertEquals("start", c.resume)
        assertEquals("language", c.audioPick)
        assertFalse(SettingsReset.available(SettingsTab.ADDONS))
        assertFalse(SettingsReset.available(SettingsTab.PROFILES))
        assertTrue(SettingsReset.available(SettingsTab.SUBTITLES))
    }
}
