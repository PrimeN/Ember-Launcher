package app.ember.tv

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.ember.tv.data.InterfaceStyles
import app.ember.tv.discover.ExploreKind
import app.ember.tv.discover.ExploreQuery
import app.ember.tv.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #251, #252: Discover's year, genre and award choices, drawn and pressed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiscoverFilterVisualsUiTest {
    @get:Rule val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(LocalNight provides true, LocalReduceMotion provides true) { EmberTheme(0, InterfaceStyles.MODERN) { Column { content() } } }
    }
    private fun press(node: SemanticsNodeInteraction) = node.performSemanticsAction(SemanticsActions.OnClick) { it() }

    @Test fun aYearRangeHasNamedSlidersAndNoDecadeChips() {
        var query by mutableStateOf(ExploreQuery(fromYear = 1990, toYear = 2005))
        show { YearPane(query) { query = it } }
        compose.onNodeWithContentDescription("Earliest year").assertExists()
        compose.onNodeWithContentDescription("Latest year").assertExists()
        compose.onAllNodesWithText("1980s").assertCountEquals(0)
        press(compose.onNodeWithText("Any year"))
        assertNull(query.fromYear); assertNull(query.toYear)
    }

    @Test fun genresAreTilesThatToggle() {
        var toggled: String? = null
        show { GenreTiles(ExploreQuery(kind = ExploreKind.MOVIE)) { toggled = it } }
        press(compose.onNodeWithTag("genre_comedy"))
        assertEquals("comedy", toggled)
    }

    @Test fun everyAwardIsATileAndNoneClearsIt() {
        var picked: String? = "unset"
        show { AwardTiles(ExploreQuery()) { picked = it } }
        for (key in listOf("oscars", "globes", "bafta", "cannes", "venice", "berlin", "emmys", "actors")) compose.onNodeWithTag("award_$key").assertExists()
        press(compose.onNodeWithTag("award_emmys")); assertEquals("emmys", picked)
        press(compose.onNodeWithTag("award_none")); assertNull(picked)
    }
}
