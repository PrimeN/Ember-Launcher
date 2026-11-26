package app.ember.tv

import android.media.tv.TvInputInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import app.ember.tv.data.CollectionSource
import app.ember.tv.data.HomeWidgets
import app.ember.tv.ui.TvInputLook
import org.junit.Assert.*
import org.junit.Test

/** #467: the TV's inputs as a built-in widget. */
class TvInputsWidgetTest {
    @Test fun inputsAreABuiltInWidgetSource() {
        assertTrue(HomeWidgets.deviceSources.any { it.first == HomeWidgets.TV_INPUTS && it.second == "TV inputs" })
        assertEquals("TV inputs", HomeWidgets.sourceLabel(CollectionSource(provider = HomeWidgets.DEVICE, catalogId = HomeWidgets.TV_INPUTS), emptyList()))
    }

    @Test fun eachKindOfInputHasItsIcon() {
        assertEquals(Icons.Outlined.SettingsInputHdmi, TvInputLook.icon(TvInputInfo.TYPE_HDMI))
        assertEquals(Icons.Outlined.LiveTv, TvInputLook.icon(TvInputInfo.TYPE_TUNER))
        assertEquals(Icons.Outlined.SettingsInputComponent, TvInputLook.icon(TvInputInfo.TYPE_COMPOSITE))
        assertEquals(Icons.Outlined.Input, TvInputLook.icon(TvInputInfo.TYPE_OTHER))
    }
}
