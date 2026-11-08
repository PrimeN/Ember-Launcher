package app.ember.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import app.ember.tv.data.MediaPageLayouts

internal val LocalMediaPageLayout = staticCompositionLocalOf { MediaPageLayouts.MODERN }

/** Shade only the artwork beneath the side panel; single-column pages keep their existing art. */
@Composable
internal fun MediaPageRightFade(background: Color) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if(MediaPageLayouts.split(LocalMediaPageLayout.current,maxWidth.value,LocalDensity.current.fontScale,maxHeight.value-58f))
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                0f to Color.Transparent,.48f to Color.Transparent,.62f to background.copy(alpha=.48f),.75f to background,1f to background)))
    }
}

/** Only the title page adopts this style; Home, navigation and the player keep their choices. */
@Composable
internal fun MediaPageLayoutScope(layout: String, content: @Composable () -> Unit) {
    val normalized = MediaPageLayouts.normalize(layout)
    CompositionLocalProvider(LocalMediaPageLayout provides normalized,
        LocalInterfaceStyle provides MediaPageLayouts.interfaceStyle(normalized), LocalNight provides true,
        content = content)
}
