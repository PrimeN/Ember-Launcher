package app.ember.tv

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import app.ember.tv.data.InterfaceStyles
import app.ember.tv.data.Profile
import app.ember.tv.ui.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #259: controls that show only a picture or a letter are read by name. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessibilityLabelsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aProfileAvatarIsReadByName() {
        compose.setContent {
            CompositionLocalProvider(LocalNight provides true, LocalReduceMotion provides true) {
                EmberTheme(0, InterfaceStyles.MODERN) { ProfileAvatar(Profile(id = "a", name = "Arash"), 64.dp) {} }
            }
        }
        compose.onNodeWithContentDescription("Arash").assertExists().assertHasClickAction()
        compose.onAllNodesWithText("A").assertCountEquals(0)
    }
}
