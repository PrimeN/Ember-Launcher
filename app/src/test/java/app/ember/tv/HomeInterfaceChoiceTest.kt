package app.ember.tv

import android.content.Context
import android.content.Intent
import app.ember.tv.data.*
import app.ember.tv.home.*
import app.ember.tv.state.LookState
import app.ember.tv.ui.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class HomeInterfaceChoiceTest {
    @get:Rule val temp=TemporaryFolder()
    @After fun reset(){Ui.emberX=false;Ui.light=false;Ui.day=false}
    private fun app(id:String)=AppEntry(id,id,Intent())
    @Test fun homeOffersOnlyEmberAndEmberLightWithEmberDefaults() {
        assertEquals(listOf("Ember","Ember Light"),InterfaceStyles.options.map {it.second})
        val prefs=Prefs(PersistenceContext(temp.newFolder()))
        assertEquals(InterfaceStyles.MODERN,prefs.uiStyle);assertEquals(NavbarStyles.EMBER,prefs.navbarStyle)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {val look=LookState(prefs,scope);look.updateUiStyle(InterfaceStyles.EMBER_X)
            assertEquals(InterfaceStyles.MODERN,look.uiStyle);assertFalse(Ui.emberX)
            look.updateUiStyle(InterfaceStyles.LIGHT);assertTrue(Ui.light)
        } finally {scope.cancel()}
    }
    @Test fun retiringCinemaHomePreservesExplicitMediaAndPlayerChoices() {
        val context=PersistenceContext(temp.newFolder());val prefs=Prefs(context)
        prefs.mediaPageLayout=MediaPageLayouts.MODERN;prefs.playbackConfig=prefs.playbackConfig.copy(playerInterface="basic")
        context.getSharedPreferences("ember",Context.MODE_PRIVATE).edit().putString("uiStyle",InterfaceStyles.EMBER_X).putBoolean("emberXInterfaceMigrated",true).apply()
        assertEquals(InterfaceStyles.MODERN,prefs.uiStyle)
        assertEquals(MediaPageLayouts.MODERN,prefs.mediaPageLayout);assertEquals("basic",prefs.playbackConfig.playerInterface)
        assertEquals(InterfaceStyles.EMBER_X,MediaPageLayouts.interfaceStyle(MediaPageLayouts.EMBER_X))
    }
    @Test fun shortcutsRespectAllowedTabsEvenWhenHiddenItemsAreRequested() {
        val tabs=listOf(NavTabs.HOME,NavTabs.DISCOVER,NavTabs.SEARCH,NavTabs.SETTINGS)
        val entries=LightNavigation.ordered(listOf(app("player")),tabs,emptyList(),emptySet(),true)
        assertEquals(listOf(NavTabs.DISCOVER),entries.mapNotNull {LightNavigation.destination(it.pkg)})
        assertFalse(entries.any {it.pkg==LightNavigation.id(NavTabs.LIVE) || it.pkg==LightNavigation.id(NavTabs.LIBRARY)})
        assertNull(LightNavigation.destination("player"));assertNull(LightNavigation.destination("ember:nav:uninstall"))
    }
    @Test fun appAndNavigationTilesMoveTogetherAndKeepTheirCustomOrder() {
        val apps=(1..7).map {app("app.$it")};val nav=LightNavigation.id(NavTabs.DISCOVER)
        val initial=LightNavigation.ordered(apps,listOf(NavTabs.DISCOVER),emptyList(),emptySet(),false)
        val draft=AppMoveDraft(initial.map {it.pkg},nav)
        assertTrue(draft.move(6,vertical=-1))
        val ordered=LightNavigation.ordered(apps,listOf(NavTabs.DISCOVER),draft.order,emptySet(),false)
        assertEquals(nav,ordered[1].pkg);assertEquals(initial.size,ordered.size);assertEquals(7,apps.size)
    }
    @Test fun hiddenShortcutsStayRecoverableWithoutChangingTheInstalledAppList() {
        val apps=listOf(app("player"));val id=LightNavigation.id(NavTabs.LIBRARY)
        assertEquals(listOf("player"),LightNavigation.ordered(apps,listOf(NavTabs.LIBRARY),emptyList(),setOf(id),false).map {it.pkg})
        assertEquals(listOf("player",id),LightNavigation.ordered(apps,listOf(NavTabs.LIBRARY),emptyList(),setOf(id),true).map {it.pkg})
        assertEquals(listOf("player"),apps.map {it.pkg})
    }
    @Test fun mixedTileOrderAndHiddenDestinationsSurviveBackupForTheProfile() {
        val source=PersistenceContext(temp.newFolder());val prefs=Prefs(source).apply {uiStyle=InterfaceStyles.LIGHT}
        val homes=PersonalHomes(prefs);val id=LightNavigation.id(NavTabs.DISCOVER)
        homes.update {it.copy(lightOrder=listOf(id,"player"),lightNavHidden=setOf(id))}
        val target=PersistenceContext(temp.newFolder())
        BackupPersistence(target,FixtureSecrets()).restore(BackupPersistence(source,FixtureSecrets()).snapshot())
        val restored=PersonalHomes(Prefs(target)).current
        assertEquals(listOf(id,"player"),restored.lightOrder);assertEquals(setOf(id),restored.lightNavHidden)
        assertEquals(InterfaceStyles.LIGHT,Prefs(target).uiStyle)
    }
    @Test fun emberLightOnlyOffersContentPagesAsTiles() {
        // Home is where the tiles live (#129); Search is in the header, Apps and Settings elsewhere (#134).
        val entries=LightNavigation.entries(listOf(NavTabs.HOME,NavTabs.DISCOVER,NavTabs.SEARCH,NavTabs.APPS,NavTabs.SETTINGS,
            NavTabs.LIVE,NavTabs.LIBRARY,NavTabs.WATCHLIST))
        assertEquals(listOf(NavTabs.DISCOVER,NavTabs.LIVE,NavTabs.LIBRARY,NavTabs.WATCHLIST),entries.mapNotNull {LightNavigation.destination(it.pkg)})
    }
}
