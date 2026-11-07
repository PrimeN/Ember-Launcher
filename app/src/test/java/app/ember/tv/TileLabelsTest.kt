package app.ember.tv

import app.ember.tv.data.InterfaceStyles
import app.ember.tv.data.Prefs
import app.ember.tv.data.TileLabels
import app.ember.tv.ui.SettingDefinitions
import app.ember.tv.ui.SettingsTab
import app.ember.tv.ui.lookKeys
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** #43: labels under app, title and collection tiles can each be turned off. */
class TileLabelsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun eachKindTogglesIndependentlyAndUnknownKindsAreIgnored() {
        var off = emptySet<String>()
        assertTrue(TileLabels.shown(off, TileLabels.APPS))
        off = TileLabels.toggle(off, TileLabels.APPS)
        assertFalse(TileLabels.shown(off, TileLabels.APPS)); assertTrue(TileLabels.shown(off, TileLabels.MEDIA))
        off = TileLabels.toggle(off, TileLabels.COLLECTIONS)
        assertEquals(setOf(TileLabels.APPS, TileLabels.COLLECTIONS), off)
        assertEquals(setOf(TileLabels.COLLECTIONS), TileLabels.toggle(off, TileLabels.APPS))
        assertEquals(off, TileLabels.toggle(off, "future") - "future")
    }

    @Test fun savedChoiceRoundTripsAndDefaultsToAllShown() {
        val prefs = Prefs(PersistenceContext(temp.root))
        assertTrue(prefs.tileLabelsOff.isEmpty())
        prefs.tileLabelsOff = setOf(TileLabels.MEDIA, "junk")
        assertEquals(setOf(TileLabels.MEDIA), prefs.tileLabelsOff)
    }

    @Test fun settingIsOnAppearanceForEveryInterfaceAndSearchable() {
        for (style in listOf(InterfaceStyles.MODERN, "light", InterfaceStyles.EMBER_X)) assertTrue(style, "tile-labels" in lookKeys(SettingsTab.APPEARANCE, style))
        assertEquals("tile-labels", SettingDefinitions.matching("labels under tiles").single().id)
    }
}
