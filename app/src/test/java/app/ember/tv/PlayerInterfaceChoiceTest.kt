package app.ember.tv

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.ember.tv.data.*
import app.ember.tv.player.*
import app.ember.tv.ui.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlayerInterfaceChoiceTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun explicitChoiceWorksAcrossEveryLauncherWithoutChangingTheLauncher() {
        for ((style, _) in PlayerInterfaces.options) for ((launcher, _) in InterfaceStyles.options) {
            val config = PlaybackConfig(playerInterface = style, playerLook = "simple")
            assertEquals(style, PlayerInterfaces.resolve(config, launcher))
            assertTrue(PlayerInterfaces.customControls(style, false, false))
            assertEquals(if (style == PlayerInterfaces.EMBER_X) InterfaceStyles.EMBER_X else InterfaceStyles.MODERN,
                PlayerInterfaces.layoutStyle(style))
            assertFalse(PlayerInterfaces.customControls(style, true, false))
            assertFalse(PlayerInterfaces.customControls(style, false, true))
        }
    }
    @Test fun oldSettingsAndBackupsRetainTheirPreviousEffectiveControls() {
        val oldBasic = Json.decodeFromString<PlaybackConfig>("""{"playerLook":"simple","audioDelayMs":120}""")
        val oldModern = Json.decodeFromString<PlaybackConfig>("""{"playerLook":"advanced"}""")
        assertEquals(PlayerInterfaces.BASIC, PlayerInterfaces.resolve(oldBasic, InterfaceStyles.MODERN))
        assertEquals(PlayerInterfaces.MODERN, PlayerInterfaces.resolve(oldModern, InterfaceStyles.LIGHT))
        assertEquals(PlayerInterfaces.EMBER_X, PlayerInterfaces.resolve(oldBasic, InterfaceStyles.EMBER_X))
        assertEquals(PlayerInterfaces.EMBER_X, PlayerInterfaces.resolve(oldModern, InterfaceStyles.EMBER_X))
        assertEquals(120, oldBasic.audioDelayMs)
        assertNull(oldModern.playerInterface)
        assertEquals(PlayerInterfaces.MODERN, PlayerInterfaces.resolve(PlaybackConfig(playerInterface = "retired"), InterfaceStyles.EMBER_X))
    }
    @Test fun allExplicitInterfacesSurvivePreferencesAndBackupWithoutChangingPlaybackSettings() {
        for ((style, _) in PlayerInterfaces.options) {
            val source = PersistenceContext(temp.newFolder())
            val prefs = Prefs(source)
            prefs.uiStyle = InterfaceStyles.LIGHT
            val expected = prefs.playbackConfig.copy(playerInterface = style, audioDelayMs = -80, engine = "mpv", resume = "resume", playerLook = "simple")
            prefs.playbackConfig = expected
            assertEquals(expected, Prefs(source).playbackConfig)
            val target = PersistenceContext(temp.newFolder())
            BackupPersistence(target, FixtureSecrets()).restore(BackupPersistence(source, FixtureSecrets()).snapshot())
            val restored = Prefs(target)
            assertEquals(expected, restored.playbackConfig)
            assertEquals(InterfaceStyles.LIGHT, restored.uiStyle)
            assertEquals(style, PlayerInterfaces.resolve(restored.playbackConfig, restored.uiStyle))
        }
    }
    @Test fun settingsSearchReachesTheFourChoicesAtThePlayerSection() {
        // #192: Basic became Ember, Ember Modern became Glass, Ember X became Basic. The stored values did not change.
        assertEquals(listOf("Ember", "Glass", "Basic", "Streaming"), SettingDefinitions.playerInterface.options.map { it.second })
        assertEquals(listOf(PlayerInterfaces.BASIC, PlayerInterfaces.MODERN, PlayerInterfaces.EMBER_X, PlayerInterfaces.STREAMING), PlayerInterfaces.options.map { it.first })
        for (query in listOf("Ember", "Glass", "Basic", "Streaming", "Ember Modern", "Ember X", "player interface")) {
            val hit = SettingDefinitions.matching(query).single { it.id == "player_look" }
            assertEquals(SettingsTab.PLAYER, hit.tab)
            assertTrue(hit.id in PLAYER_KEYS)
        }
        assertEquals("Night", SettingDefinitions.theme.options.single { it.first == "night" }.second)
        assertEquals("Day", SettingDefinitions.theme.options.single { it.first == "day" }.second)
    }
    @Test fun colorButtonsPreviewTheirOwnPalettesWithReadableTextInEveryState() {
        fun contrast(a: Color, b: Color): Float {
            val light = maxOf(a.luminance(), b.luminance())
            val dark = minOf(a.luminance(), b.luminance())
            return (light + .05f) / (dark + .05f)
        }
        for (index in Accents.indices.filter(app.ember.tv.data.ColorThemes::offered)) {
            assertEquals(Accents[index].second, themePreviewSwatches(index).first())
            for (current in Accents.indices) for (day in listOf(false, true)) {
                val surface = emberColorScheme(current, day, false).surface
                val normal = themeChoiceColors(index, false, false, surface)
                val selected = themeChoiceColors(index, true, false, surface)
                assertNotEquals(normal.first, selected.first)
                for ((chosen, focused) in listOf(false to false, true to false, false to true, true to true)) {
                    val (fill, text) = themeChoiceColors(index, chosen, focused, surface)
                    assertTrue(contrast(fill, text) >= 4.5f)
                    if (focused) assertEquals(Accents[index].second, fill)
                }
            }
        }
        assertEquals(3, themePreviewSwatches(ColorThemes.MODERN_EMBER).size)
        assertEquals(3, themePreviewSwatches(ColorThemes.SHADOW_EMBER).size)
    }
}
