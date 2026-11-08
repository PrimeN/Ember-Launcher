package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.state.LookState
import app.ember.tv.ui.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class NavbarStyleTest {
    @get:Rule val temp=TemporaryFolder()
    @After fun resetUi(){Ui.emberX=false;Ui.light=false;Ui.day=false;Ui.oled=false}
    @Test fun emberIsDefaultAndOnlyThreeNamedChoicesAreOffered() {
        assertEquals(NavbarStyles.EMBER,Prefs(PersistenceContext(temp.newFolder())).navbarStyle)
        assertEquals(listOf("Ember","Ember Collapsed","Cinema Bar"),NavbarStyles.options.map {it.second})
        assertEquals(NavbarStyles.EMBER,NavbarStyles.normalize("unknown"))
    }
    @Test fun everyStyleSurvivesBackupWithoutChangingHomeMediaOrPlayer() {
        for((style,_) in NavbarStyles.options)for(launcher in listOf(InterfaceStyles.MODERN,InterfaceStyles.EMBER_X)) {
            val source=PersistenceContext(temp.newFolder());val prefs=Prefs(source).apply {
                uiStyle=launcher;navbarStyle=style;mediaPageLayout=MediaPageLayouts.EMBER_X
                playbackConfig=playbackConfig.copy(playerInterface="basic")
            }
            val target=PersistenceContext(temp.newFolder())
            BackupPersistence(target,FixtureSecrets()).restore(BackupPersistence(source,FixtureSecrets()).snapshot())
            val restored=Prefs(target)
            assertEquals(style,restored.navbarStyle);assertEquals(InterfaceStyles.home(launcher),restored.uiStyle)
            assertEquals(MediaPageLayouts.EMBER_X,restored.mediaPageLayout);assertEquals(prefs.playbackConfig,restored.playbackConfig)
        }
    }
    @Test fun lightBlocksChangesAndReturningToAnotherInterfaceRestoresTheSavedChoice() {
        val prefs=Prefs(PersistenceContext(temp.newFolder()));val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val look=LookState(prefs,scope)
            look.updateNavbarStyle(NavbarStyles.CINEMA);look.updateUiStyle(InterfaceStyles.LIGHT)
            look.updateNavbarStyle(NavbarStyles.COLLAPSED)
            assertEquals(NavbarStyles.CINEMA,look.navbarStyle);assertEquals(NavbarStyles.CINEMA,prefs.navbarStyle)
            look.updateUiStyle(InterfaceStyles.EMBER_X);assertEquals(NavbarStyles.CINEMA,look.navbarStyle)
            look.updateNavbarStyle(NavbarStyles.COLLAPSED);assertEquals(NavbarStyles.COLLAPSED,prefs.navbarStyle)
        } finally {scope.cancel()}
    }
    @Test fun settingsAndSideGeometryRespectTheSelectedStyleAndLight() {
        assertFalse("navbar-style" in lookKeys(SettingsTab.APPEARANCE,InterfaceStyles.LIGHT))
        for(launcher in listOf(InterfaceStyles.MODERN,InterfaceStyles.EMBER_X)) {
            assertTrue("navbar-style" in lookKeys(SettingsTab.APPEARANCE,launcher))
            assertTrue(NavbarStyles.side(NavbarStyles.EMBER,launcher,"side",NavTabs.HOME))
            for(style in listOf(NavbarStyles.COLLAPSED,NavbarStyles.CINEMA))assertFalse(NavbarStyles.side(style,launcher,"side",NavTabs.HOME))
        }
        assertFalse(NavbarStyles.side(NavbarStyles.EMBER,InterfaceStyles.LIGHT,"side",NavTabs.HOME))
        assertFalse(NavbarStyles.side(NavbarStyles.EMBER,InterfaceStyles.MODERN,"side",NavTabs.SETTINGS))
        assertEquals("navbar-style",SettingDefinitions.matching("Navbar style").single().id)
    }
    @Test fun sourceDependentTabsStayHiddenAndSearchLeadsTheNewMenus() {
        assertEquals(listOf(NavTabs.SEARCH,NavTabs.HOME,NavTabs.APPS),NavbarStyles.entries(listOf(NavTabs.HOME,NavTabs.SETTINGS,NavTabs.APPS,NavTabs.SEARCH,NavTabs.APPS)))
    }
    @Test fun onlyTheDarkThemeForcesNightColoursOnTheAlternativeBars() {
        assertTrue(NavbarStyles.forcesNight(NavbarStyles.COLLAPSED,day=false))
        assertTrue(NavbarStyles.forcesNight(NavbarStyles.CINEMA,day=false))
        assertFalse(NavbarStyles.forcesNight(NavbarStyles.CINEMA,day=true))
        assertFalse(NavbarStyles.forcesNight(NavbarStyles.COLLAPSED,day=true))
        assertFalse(NavbarStyles.forcesNight(NavbarStyles.EMBER,day=false))
    }
}
