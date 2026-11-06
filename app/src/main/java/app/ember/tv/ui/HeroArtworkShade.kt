package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Readable text on artwork without spreading the light page colour over the image. */
@Composable
internal fun HeroArtworkShade(background: Color, shade: Float) {
    val day=Ui.isDay && !LocalNight.current && !LocalFloating.current
    val scrim=if(day)Color(0xFF111215) else background
    val opacity=shade.coerceIn(0f,1f)
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
        0f to scrim.copy(alpha=.82f*opacity),
        .26f to scrim.copy(alpha=.66f*opacity),
        .46f to scrim.copy(alpha=.30f*opacity),
        .72f to Color.Transparent)))
    // Only the very bottom joins the Day page; the image itself remains full colour.
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
        0f to scrim.copy(alpha=.18f*opacity), .16f to Color.Transparent,
        (if(day).90f else .70f) to Color.Transparent, 1f to background)))
}
