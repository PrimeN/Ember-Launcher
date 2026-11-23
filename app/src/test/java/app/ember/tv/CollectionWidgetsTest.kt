package app.ember.tv

import app.ember.tv.state.HomeSectionsState
import app.ember.tv.ui.SettingsCategory
import app.ember.tv.ui.SettingsTab
import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionWidgetsTest {
    @Test fun aPinnedCollectionNotYetPlacedGoesAfterTheBannerAndApps() {
        // #405: collections are Home sections; pinned ones keep their place at the top until moved.
        val c = HomeSectionsState.collectionSection("abc")
        val layout = listOf(HomeViewModel.HOME_HERO to true, HomeViewModel.HOME_APPS to true, HomeViewModel.HOME_CONTINUE to true, c to true)
        assertEquals(listOf(HomeViewModel.HOME_HERO, HomeViewModel.HOME_APPS, c, HomeViewModel.HOME_CONTINUE),
            HomeSectionsState.leadPinned(layout, setOf(c)).map { it.first })
        assertEquals(layout, HomeSectionsState.leadPinned(layout, emptySet()))
        assertEquals("abc", HomeSectionsState.collectionId(c))
    }

    @Test fun startupIsASystemSetting() {
        // #407
        assertEquals(SettingsCategory.SYSTEM, SettingsCategory.of(SettingsTab.DEFAULT_HOME))
    }
}
