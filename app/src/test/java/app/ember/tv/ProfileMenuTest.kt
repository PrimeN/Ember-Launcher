package app.ember.tv

import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.ui.unit.*
import app.ember.tv.data.*
import app.ember.tv.state.ProfilesState
import app.ember.tv.state.LookState
import kotlinx.coroutines.*
import app.ember.tv.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class ProfileMenuTest {
    @Test fun adultShortcutsOpenExistingPersonalPagesEvenWithoutTheirTabs() {
        val actions = profileMenuActions(Profile("adult", "Adult"), libraryVisible = false)
        assertEquals(listOf(NavTabs.WATCHLIST, NavTabs.PROGRESS, NavTabs.RECENT), actions.mapNotNull { it.page })
        assertEquals(actions.size, actions.distinct().size)
        assertEquals("PROFILES", actions.last().settings)
        assertEquals(listOf("PROFILES"), actions.mapNotNull { it.settings })
    }

    @Test fun kidsNeverExposePersonalPagesEvenWithPersonalFlagOrLibraryEnabled() {
        val actions = profileMenuActions(Profile("kids", "Kids", kids = true, personal = true), true)
        assertTrue(actions.none { it.page != null })
        assertTrue(ProfileMenuAction.PROFILES in actions)
    }

    @Test fun adultWithPersonalRowsDisabledCannotUsePersonalShortcuts() {
        assertTrue(profileMenuActions(Profile("private", "Private", personal = false), true).none { it.page != null })
    }

    @Test fun libraryIsNotRepeatedInTheProfileMenu() {
        val p = Profile("adult", "Adult")
        val off = profileMenuActions(p, false)
        val on = profileMenuActions(p, true)
        assertFalse(ProfileMenuAction.LIBRARY in off)
        assertEquals(off.toSet(), on.toSet())
    }

    private val position = ProfileMenuPosition(gap = 12, margin = 16)
    @Test fun popupAlignsBelowAvatarAndMirrorsForRtl() {
        val anchor = IntRect(700, 20, 742, 62)
        val window = IntSize(1200, 700)
        val popup = IntSize(328, 400)
        assertEquals(IntOffset(700, 74), position.calculatePosition(anchor, window, LayoutDirection.Ltr, popup))
        assertEquals(IntOffset(414, 74), position.calculatePosition(anchor, window, LayoutDirection.Rtl, popup))
    }

    @Test fun popupMovesAboveLowAvatarAndClampsAtRightEdge() {
        assertEquals(IntOffset(856, 168), position.calculatePosition(IntRect(1100, 580, 1142, 622),
            IntSize(1200, 700), LayoutDirection.Ltr, IntSize(328, 400)))
    }

    @Test fun popupStaysInsideSmallViewport() {
        val offset = position.calculatePosition(IntRect(-20, 20, 22, 62), IntSize(480, 300),
            LayoutDirection.Rtl, IntSize(328, 268))
        assertEquals(IntOffset(16, 16), offset)
    }

    @Test fun quickSwitchOutOfKidsWaitsForPinAndDoesNotChangeHistoryBeforeApproval() {
        val prefs = memoryPrefs()
        prefs.profiles = listOf(Profile("kids", "Kids", kids = true), Profile("adult", "Adult"))
        prefs.currentProfile = "kids"
        var approve: (() -> Unit)? = null
        var changes = 0
        val state = ProfilesState(prefs, { emptyList() }, { emptyList() },
            { _, go -> approve = go }, {}, { changes++ })
        state.switchProfile("adult")
        assertNotNull(approve)
        assertEquals("kids", state.profileId)
        assertEquals("kids", prefs.currentProfile)
        assertEquals(0, changes)
        approve!!.invoke()
        assertEquals("adult", state.profileId)
        assertEquals("adult", prefs.currentProfile)
        assertEquals(1, changes)
    }

    @Test fun startupChooserRemainsIndependentOfManualMenu() {
        val prefs = memoryPrefs()
        prefs.profiles = listOf(Profile("main", "Main"), Profile("other", "Other"))
        prefs.askProfile = true
        val state = ProfilesState(prefs, { emptyList() }, { emptyList() }, { _, go -> go() }, {}, {})
        state.askProfileIfWanted()
        assertTrue(state.showProfilePicker)
        assertFalse(state.showProfileMenu)
        state.switchProfile("other")
        assertFalse(state.showProfilePicker)
        assertEquals("other", state.profileId)
    }

    @Test fun retiredInterfaceValuesMigrateWithoutChangingBasicBadgeStorage() {
        val values = mutableMapOf<String, Any?>("uiStyle" to "legacy", "badgeMode" to "legacy")
        val prefs = memoryPrefs(values)
        assertEquals("modern", prefs.uiStyle)
        assertEquals("modern", values["uiStyle"])
        assertEquals("legacy", prefs.badgeMode)
        assertEquals(appearanceKeys("modern"), appearanceKeys("legacy"))
        assertEquals(listOf("modern", "light"), SettingDefinitions.interfaceStyle.options.map { it.first })
        prefs.uiStyle = "light"
        assertEquals("light", prefs.uiStyle)
        prefs.uiStyle = "unknown-restored-value"
        assertEquals("modern", prefs.uiStyle)
    }

    @Test fun runtimeStyleChangesCannotReactivateTheRetiredInterface() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val prefs = memoryPrefs()
            val state = LookState(prefs, scope)
            state.updateUiStyle("light")
            assertEquals("light", state.uiStyle)
            assertTrue(Ui.light)
            state.updateUiStyle("legacy")
            assertEquals("modern", state.uiStyle)
            assertEquals("modern", prefs.uiStyle)
            assertFalse(Ui.light)
        } finally { scope.cancel() }
    }

    @Test fun sideNavigationChoiceSurvivesLookRecreationAndUnrelatedAppearanceChanges() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val oldLight=Ui.light;val oldDay=Ui.day;val oldOled=Ui.oled
        try {
            val values=mutableMapOf<String,Any?>()
            val state=LookState(memoryPrefs(values),scope)
            state.updateMenuPosition("side")
            state.updateTheme("day")
            state.updateUiStyle("light")
            state.updateUiStyle("modern")
            assertEquals("side",state.menuPosition)
            val restored=LookState(memoryPrefs(values),scope)
            assertEquals("side",restored.menuPosition)
            assertEquals("modern",restored.uiStyle)
        } finally {scope.cancel();Ui.light=oldLight;Ui.day=oldDay;Ui.oled=oldOled}
    }

    @Test fun existingGreyPreferencesLoadShadowWithoutChangingOtherSavedChoices() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val oldLight=Ui.light;val oldDay=Ui.day;val oldOled=Ui.oled
        try {
            for(key in listOf("accent2","accent3")) {
                val values=mutableMapOf<String,Any?>(key to 1,"menuPosition" to "side")
                val prefs=memoryPrefs(values)
                val state=LookState(prefs,scope)
                assertEquals(ColorThemes.SHADOW_EMBER,state.accent)
                assertEquals("Charcoal",Accents[state.accent].first)
                assertEquals("side",state.menuPosition)
                assertEquals(1,values[key])
            }
        } finally {scope.cancel();Ui.light=oldLight;Ui.day=oldDay;Ui.oled=oldOled}
    }

    private fun memoryPrefs(values: MutableMap<String, Any?> = mutableMapOf()): Prefs {
        val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when {
                method.name.startsWith("put") -> { values[args[0] as String] = args[1]; proxy }
                method.name == "remove" -> { values.remove(args[0]); proxy }
                method.name == "clear" -> { values.clear(); proxy }
                method.name == "commit" -> true
                else -> null
            }
        } as SharedPreferences.Editor
        val preferences = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when {
                method.name == "edit" -> editor
                method.name == "contains" -> values.containsKey(args[0])
                method.name == "getAll" -> values.toMap()
                method.name.startsWith("get") -> values[args[0]] ?: args[1]
                else -> null
            }
        } as SharedPreferences
        return Prefs(object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        })
    }
}
