package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.tv.material3.MaterialTheme

/**
 * Search's own background (#120): soft glows in the theme's accent (and a neighbouring hue) over the
 * page colour, instead of the focused title's artwork. Static gradients only: no blur pass, no
 * animation and no image, so it costs nothing while scrolling results on a low-end TV.
 */
@Composable
internal fun SearchBackdrop(modifier: Modifier = Modifier) {
    val base = Ink
    // Shadow Ember (#139): neutral charcoal and grey, darker, and no hue turned round the wheel.
    val shadow = isShadowEmber
    val accent = if (shadow) SearchGlow.shadowMain(Ui.isDay) else MaterialTheme.colorScheme.primary
    val glow = if (shadow) SearchGlow.shadowStrength(Ui.isDay, Ui.isOled) else SearchGlow.strength(Ui.isDay, Ui.isOled)
    val neighbour = remember(accent, shadow) { if (shadow) SearchGlow.shadowFar(Ui.isDay) else SearchGlow.neighbour(accent) }
    Box(modifier.fillMaxSize().testTag("search_backdrop").background(base).drawWithCache {
        val reach = maxOf(size.width, size.height)
        val main = Brush.radialGradient(listOf(accent.copy(alpha = glow), Color.Transparent),
            center = Offset(size.width * .14f, size.height * .02f), radius = reach * .62f)
        val far = Brush.radialGradient(listOf(neighbour.copy(alpha = glow * .72f), Color.Transparent),
            center = Offset(size.width * .94f, size.height * .98f), radius = reach * .56f)
        val haze = Brush.radialGradient(listOf(accent.copy(alpha = glow * .30f), Color.Transparent),
            center = Offset(size.width * .62f, size.height * .38f), radius = reach * .42f)
        // Settles into the page colour at the bottom so the last row of results stays calm.
        val floor = Brush.verticalGradient(0f to Color.Transparent, .72f to Color.Transparent, 1f to base.copy(alpha = .50f))
        onDrawBehind {
            drawRect(main); drawRect(far); drawRect(haze); drawRect(floor)
        }
    })
}

internal object SearchGlow {
    /** Day keeps the tint light; OLED stays close to black; Night carries the most colour. */
    fun strength(day: Boolean, oled: Boolean): Float = when { day -> .16f; oled -> .20f; else -> .30f }

    /** Shadow Ember's glows: charcoal greys, a little lighter than the page at night, darker by day. */
    fun shadowMain(day: Boolean) = if (day) Color(0xFF8A9099) else Color(0xFF5B5F66)
    fun shadowFar(day: Boolean) = if (day) Color(0xFFA7ACB3) else Color(0xFF3F4247)
    fun shadowStrength(day: Boolean, oled: Boolean): Float = when { day -> .14f; oled -> .16f; else -> .24f }

    /** The accent turned a little round the colour wheel, so the two glows read as one gentle wash. */
    fun neighbour(accent: Color, degrees: Float = 38f): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(accent.toArgb(), hsv)
        hsv[0] = (hsv[0] + degrees + 360f) % 360f
        return Color(android.graphics.Color.HSVToColor(hsv))
    }
}
