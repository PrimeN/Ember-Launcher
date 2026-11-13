package app.ember.tv

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import app.ember.tv.data.InterfaceStyles
import app.ember.tv.ui.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pictures of the settings building blocks, compared between builds in private storage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun save(name: String, node: SemanticsNodeInteraction = compose.onRoot()) {
        val file = java.io.File("build/reports/ui/settings-$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { node.captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun page(night: Boolean) = compose.setContent {
        CompositionLocalProvider(LocalNight provides night, LocalReduceMotion provides true, LocalRenderOptions provides RenderOptions(device = "low")) {
            EmberTheme(0, InterfaceStyles.MODERN) {
                Column(Modifier.size(960.dp, 540.dp).background(if (night) Color(0xFF101012) else Color(0xFFF2F0EE)).verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Section("Buffer", "How far ahead the player loads.", id = "buffer") {
                        Choice(listOf("normal" to "Normal", "large" to "Large (4K remux)", "huge" to "Huge"), "large") {}
                    }
                    Section("Display", "Match the display to the video.") {
                        ToggleRow("Match resolution too", "Sends 1080p as 1080p.", true) {}
                        ToggleRow("Tunneled playback", null, false) {}
                        SettingValueRow("Carousel catalog", "Popular · Cinemeta") {}
                    }
                    ResetPageButton(SettingsTab.AUDIO_VIDEO)
                }
            }
        }
    }

    @Test fun controlsNight() { page(true); save("controls-night") }

    @Test fun controlsDay() { page(false); save("controls-day") }

    @Test fun choiceMenu() {
        page(true)
        compose.onNodeWithText("Large (4K remux)").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        save("choice-menu", compose.onAllNodes(isRoot()).onLast())
    }

    @Test fun resetConfirm() {
        page(true)
        compose.onNodeWithText("Reset Audio & video").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        save("reset-confirm", compose.onAllNodes(isRoot()).onLast())
    }
}
