package app.ember.tv

import androidx.compose.ui.focus.FocusRequester
import app.ember.tv.ui.NavigationFocus
import app.ember.tv.ui.navigationTabForPage
import app.ember.tv.data.NavTabs
import org.junit.Assert.*
import org.junit.Test

class NavigationMemoryTest {
    @Test fun returningFromEachPageSelectsItsOwnTabRatherThanSearch() {
        val tabs = listOf(NavTabs.SEARCH, NavTabs.HOME, NavTabs.DISCOVER, NavTabs.APPS, NavTabs.LIVE)
        tabs.forEach { page -> assertEquals(page, navigationTabForPage(page, tabs)) }
        assertEquals(NavTabs.HOME, navigationTabForPage(NavTabs.RECENT, tabs))
        assertEquals(NavTabs.HOME, navigationTabForPage(NavTabs.WATCHLIST, tabs))
        assertEquals(NavTabs.LIBRARY, navigationTabForPage(NavTabs.PROGRESS, tabs + NavTabs.LIBRARY))
        assertEquals(NavTabs.WATCHLIST, navigationTabForPage(NavTabs.WATCHLIST, tabs + NavTabs.LIBRARY + NavTabs.WATCHLIST))
    }

    @Test fun navigationReturnsToLogicalControlWhenSceneIsRecreated() {
        val navigation = NavigationFocus()
        val oldCard = FocusRequester()
        navigation.register("home", "card", oldCard)
        navigation.focused("home", "card")
        assertSame(oldCard, navigation.content("home"))
        navigation.unregister("home", "card", oldCard)
        assertSame(navigation.page("home"), navigation.content("home"))
        val recreatedCard = FocusRequester()
        navigation.register("home", "card", recreatedCard)
        assertSame(recreatedCard, navigation.content("home"))
        // A delayed dispose from the old composition cannot unregister its replacement.
        navigation.unregister("home", "card", oldCard)
        assertSame(recreatedCard, navigation.content("home"))
    }

    @Test fun scenesAndProfilesKeepIndependentFocusHistory() {
        val navigation = NavigationFocus()
        val card = FocusRequester(); val app = FocusRequester()
        navigation.register("home", "same-id", card)
        navigation.register("apps", "same-id", app)
        navigation.focused("home", "same-id"); navigation.focused("apps", "same-id")
        assertSame(card, navigation.content("home"))
        assertSame(app, navigation.content("apps"))
        val otherProfile = NavigationFocus()
        assertNull(otherProfile.preferred("home"))
        assertNotSame(navigation.tab("home"), otherProfile.tab("home"))
    }
}
