package app.ember.tv

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.ember.tv.data.*
import app.ember.tv.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #456: pick an app or a row up, move it with the arrows, OK to put it down, Back to put it back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MoveModeTest {
    @get:Rule val compose = createComposeRule()
    private fun app(i: Int) = AppEntry("app.$i", "App $i", Intent(), BitmapDrawable(RuntimeEnvironment.getApplication().resources,
        Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(40 + i * 9, 80, 120)) }))
    private val all = (1..12).map { app(it) }
    private var apps by mutableStateOf(all)
    private var placed: Boolean? = null
    private fun key(k: Key) = compose.onNodeWithTag("scene").performKeyInput { pressKey(k) }
    private fun order() = apps.map { it.pkg.removePrefix("app.") }.joinToString(",")
    private fun save(order: List<String>) { apps = order.map { pkg -> all.first { it.pkg == pkg } } }

    @After fun clear() { AppMove.session = null; MoveSession.end() }

    @Test fun sessionMovesWithinTheRowAndBackRestoresTheOrder() {
        var saved = listOf<String>()
        val session = AppMoveSession(all[1], save = { saved = it }) { placed = it }
        session.attach(AppMoveScope(listOf("app.1", "app.2", "app.3", "app.4")))
        assertTrue(session.attached)
        assertFalse(session.step(0, 1))          // one row: no Down
        assertTrue(session.step(1, 0)); assertEquals(listOf("app.1", "app.3", "app.2", "app.4"), saved)
        assertTrue(session.step(1, 0)); assertFalse(session.step(1, 0))
        session.cancel()
        assertEquals(listOf("app.1", "app.2", "app.3", "app.4"), saved)
        assertEquals(false, placed)
    }

    @Test fun anAppNotOnTheSurfaceNeverAttaches() {
        val session = AppMoveSession(all[0], save = {}) {}
        session.attach(AppMoveScope(listOf("app.5", "app.6")))
        assertFalse(session.attached)
        assertFalse(session.step(1, 0))
    }

    @Test fun appsPageMovesTheTileInPlaceAndOkKeepsIt() {
        compose.setContent {
            CompositionLocalProvider(LocalNight provides true, LocalReduceMotion provides true,
                LocalDensity provides Density(1f, 1f), LocalRenderOptions provides RenderOptions(device = "low")) {
                EmberTheme(0) { Column(Modifier.size(960.dp, 540.dp).background(Color(0xFF101012)).testTag("scene")) {
                    AppLibraryCanvas(apps, emptySet(), "owner", onLaunch = {}, onOptions = {}, onRefresh = {}, modifier = Modifier.weight(1f))
                } }
            }
        }
        compose.runOnIdle { AppMove.session = AppMoveSession(all[1], save = ::save) { placed = it } }
        compose.waitForIdle()
        assertTrue(AppMove.session!!.attached)
        compose.onNodeWithTag("app_library_app.2").assert(hasAnyDescendant(isFocused()))
        key(Key.DirectionRight); key(Key.DirectionRight)
        assertEquals("1,3,4,2,5,6,7,8,9,10,11,12", order())
        key(Key.DirectionDown)                    // six across: one row down
        assertEquals(9, apps.indexOfFirst { it.pkg == "app.2" })
        compose.onNodeWithTag("app_library_app.2").assert(hasAnyDescendant(isFocused()))
        key(Key.DirectionCenter)
        assertNull(AppMove.session); assertEquals(true, placed)
        assertEquals(9, apps.indexOfFirst { it.pkg == "app.2" })
    }

    @Test fun backPutsTheAppWhereItWas() {
        compose.setContent {
            CompositionLocalProvider(LocalNight provides true, LocalReduceMotion provides true,
                LocalDensity provides Density(1f, 1f), LocalRenderOptions provides RenderOptions(device = "low")) {
                EmberTheme(0) { Column(Modifier.size(960.dp, 540.dp).background(Color(0xFF101012)).testTag("scene")) {
                    AppLibraryCanvas(apps, emptySet(), "owner", onLaunch = {}, onOptions = {}, onRefresh = {}, modifier = Modifier.weight(1f))
                } }
            }
        }
        compose.runOnIdle { AppMove.session = AppMoveSession(all[0], save = ::save) { placed = it } }
        compose.waitForIdle()
        key(Key.DirectionRight); key(Key.DirectionDown)
        assertEquals(7, apps.indexOfFirst { it.pkg == "app.1" })
        compose.runOnIdle { AppMove.session!!.cancel() }
        assertEquals((1..12).joinToString(","), order())
        assertEquals(false, placed)
    }

    @Test fun moveButtonPicksTheRowUpAndArrowsMoveItLive() {
        var rows by mutableStateOf(listOf("Alpha", "Bravo", "Charlie", "Delta"))
        compose.setContent {
            CompositionLocalProvider(LocalNight provides true, LocalReduceMotion provides true) {
                EmberTheme(0) { Column(Modifier.size(960.dp, 540.dp).background(Color(0xFF101012)).testTag("scene")) {
                    rows.forEachIndexed { index, name ->
                        Row(Modifier.testTag("row_$name")) {
                            Text(name)
                            MoveButton(name, index, rows.lastIndex) { step -> rows = rows.moved(index, step) }
                        }
                    }
                } }
            }
        }
        compose.onNodeWithTag("row_Alpha").onChildren().filterToOne(hasContentDescription("Move")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertNotNull(MoveSession.active)
        key(Key.DirectionDown); key(Key.DirectionDown)
        assertEquals(listOf("Bravo", "Charlie", "Alpha", "Delta"), rows)
        key(Key.DirectionRight)                   // sideways keys stay with the row
        key(Key.DirectionCenter)
        assertNull(MoveSession.active)
        assertEquals(listOf("Bravo", "Charlie", "Alpha", "Delta"), rows)
    }
}
