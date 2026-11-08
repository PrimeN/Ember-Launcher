package app.ember.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import app.ember.tv.data.MediaPageLayouts

internal val LocalMediaPageLayout = staticCompositionLocalOf { MediaPageLayouts.MODERN }

/** Only the title page adopts this style; Home, navigation and the player keep their choices. */
@Composable
internal fun MediaPageLayoutScope(layout: String, content: @Composable () -> Unit) {
    val normalized = MediaPageLayouts.normalize(layout)
    CompositionLocalProvider(LocalMediaPageLayout provides normalized,
        LocalInterfaceStyle provides MediaPageLayouts.interfaceStyle(normalized), LocalNight provides true,
        content = content)
}
