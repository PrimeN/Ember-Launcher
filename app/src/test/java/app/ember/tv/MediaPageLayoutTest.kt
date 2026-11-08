package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.state.LookState
import app.ember.tv.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaPageLayoutTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun oldInstallationsKeepTheirEffectiveLayoutThenStopFollowingHome() {
        for((launcher,_) in InterfaceStyles.options) {
            val context=PersistenceContext(temp.newFolder())
            val prefs=Prefs(context).apply {uiStyle=launcher}
            val expected=MediaPageLayouts.initial(launcher)
            assertEquals(expected,prefs.mediaPageLayout)
            prefs.uiStyle=if(launcher==InterfaceStyles.EMBER_X)InterfaceStyles.LIGHT else InterfaceStyles.EMBER_X
            assertEquals(expected,Prefs(context).mediaPageLayout)
        }
    }
    @Test fun everyChoiceSurvivesBackupAndDoesNotChangeHomeOrPlayback() {
        for((layout,_) in MediaPageLayouts.options) for((launcher,_) in InterfaceStyles.options) {
            val source=PersistenceContext(temp.newFolder());val prefs=Prefs(source)
            prefs.uiStyle=launcher;prefs.mediaPageLayout=layout
            prefs.playbackConfig=prefs.playbackConfig.copy(playerInterface="basic",audioDelayMs=75)
            val target=PersistenceContext(temp.newFolder())
            BackupPersistence(target,FixtureSecrets()).restore(BackupPersistence(source,FixtureSecrets()).snapshot())
            val restored=Prefs(target)
            assertEquals(layout,restored.mediaPageLayout);assertEquals(launcher,restored.uiStyle)
            assertEquals(prefs.playbackConfig,restored.playbackConfig)
        }
    }
    @Test fun changingHomeDoesNotOverwriteTheInMemoryOrSavedLayout() {
        val prefs=Prefs(PersistenceContext(temp.newFolder()));val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val look=LookState(prefs,scope)
            look.updateMediaPageLayout(MediaPageLayouts.IMMERSIVE)
            for((launcher,_) in InterfaceStyles.options) {
                look.updateUiStyle(launcher)
                assertEquals(MediaPageLayouts.IMMERSIVE,look.mediaPageLayout)
                assertEquals(MediaPageLayouts.IMMERSIVE,prefs.mediaPageLayout)
            }
        } finally {scope.cancel();Ui.emberX=false;Ui.light=false}
    }
    @Test fun unknownLayoutsAndTightViewportsHaveSafeDefaults() {
        assertEquals(MediaPageLayouts.MODERN,MediaPageLayouts.normalize("retired"))
        assertTrue(MediaPageLayouts.split(MediaPageLayouts.EMBER_X,960f,1f))
        assertFalse(MediaPageLayouts.split(MediaPageLayouts.EMBER_X,640f,1f))
        assertFalse(MediaPageLayouts.split(MediaPageLayouts.EMBER_X,960f,1.5f))
        assertFalse(MediaPageLayouts.split(MediaPageLayouts.EMBER_X,960f,1f,280f))
        assertFalse(MediaPageLayouts.split(MediaPageLayouts.EMBER_X,Float.NaN,1f))
        assertFalse(MediaPageLayouts.split(MediaPageLayouts.MODERN,960f,1f))
        assertFalse(MediaPageLayouts.split(MediaPageLayouts.IMMERSIVE,960f,1f))
    }
    @Test fun settingIsAvailableAndSearchableForEveryInterface() {
        for((launcher,_) in InterfaceStyles.options) assertTrue("media-page-layout" in lookKeys(SettingsTab.APPEARANCE,launcher))
        val setting=SettingDefinitions.matching("Media page layout").single()
        assertEquals(SettingsTab.APPEARANCE,setting.tab)
        assertEquals(listOf("Modern","Immersive Glass","Ember X"),setting.options.map {it.second})
        assertTrue(SettingDefinitions.matching("Immersive Glass").any {it.id==setting.id})
    }
}
