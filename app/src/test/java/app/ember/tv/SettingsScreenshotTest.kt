package app.ember.tv

import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.Timer
import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
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

    @org.junit.After fun resetDay() { Ui.day = false }

    // #337: Day colours follow Ui.day, not LocalNight; text takes the theme's colour as in the app.
    private fun page(night: Boolean) = compose.setContent {
        Ui.day = !night
        CompositionLocalProvider(androidx.tv.material3.LocalContentColor provides Fg, LocalReduceMotion provides true, LocalRenderOptions provides RenderOptions(device = "low"), LocalInlineDialogs provides true) {
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

    // #471: the Hide sources section with a few filters on.
    @Test fun hideSources() {
        val scroll = ScrollState(0)
        compose.setContent {
            CompositionLocalProvider(androidx.tv.material3.LocalContentColor provides Fg, LocalReduceMotion provides true, LocalRenderOptions provides RenderOptions(device = "low")) {
                EmberTheme(0, InterfaceStyles.MODERN) {
                    var r by remember { mutableStateOf(app.ember.tv.data.SourceRules(uncached = true, cam = true, qualities = setOf("SD"), codecs = setOf("AV1"), maxSizeGb = 40, words = listOf("HDTS", "YIFY"))) }
                    Column(Modifier.size(960.dp, 540.dp).background(Color(0xFF101012)).verticalScroll(scroll).padding(24.dp)) {
                        HideSourcesSection(r, listOf("Comet", "MediaFusion", "Torrentio")) { f -> r = f(r) }
                    }
                }
            }
        }
        save("hide-sources")
        compose.runOnIdle { kotlinx.coroutines.runBlocking { scroll.scrollTo(scroll.maxValue) } }
        compose.waitForIdle()
        save("hide-sources-end")
    }

    // #473: System access › Make it lighter.
    @Test fun lighter() {
        compose.setContent {
            CompositionLocalProvider(androidx.tv.material3.LocalContentColor provides Fg, LocalReduceMotion provides true, LocalRenderOptions provides RenderOptions(device = "low")) {
                EmberTheme(0, InterfaceStyles.MODERN) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    Column(Modifier.size(960.dp, 540.dp).background(Color(0xFF101012)).padding(24.dp)) {
                        LighterSection(context, 0, ready = true, busy = false) {}
                    }
                }
            }
        }
        save("lighter")
    }

    // #474: sliders as cards with a thick track; the first one focused.
    @Test fun sliders() {
        compose.setContent {
            CompositionLocalProvider(androidx.tv.material3.LocalContentColor provides Fg, LocalReduceMotion provides true, LocalRenderOptions provides RenderOptions(device = "low")) {
                EmberTheme(0, InterfaceStyles.MODERN) {
                    val first = remember { androidx.compose.ui.focus.FocusRequester() }
                    Column(Modifier.size(960.dp, 540.dp).background(Color(0xFF101012)).padding(32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        StepSlider("Screen margins", listOf(0, 10, 20, 30, 40, 50), 20, { if (it == 0) "None" else "${it / 10}%" }, Modifier.focusRequester(first), width = 560.dp,
                            icon = androidx.compose.material.icons.Icons.Outlined.CropFree) {}
                        StepSlider("Seconds per slide", listOf(5, 10, 20, 30, 60), 10, { "${it}s" }, width = 560.dp,
                            icon = androidx.compose.material.icons.Icons.Outlined.Timer) {}
                        StepSlider("Guide rows", listOf(5, 6, 7), 7, { "$it rows" }, width = 560.dp) {}
                    }
                    LaunchedEffect(Unit) { first.requestFocus() }
                }
            }
        }
        save("sliders")
    }

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
