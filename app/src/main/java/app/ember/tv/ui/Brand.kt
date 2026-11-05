package app.ember.tv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import app.ember.tv.R

/** Drawings that come in each accent colourway, in the order of [Accents]. */
private val MARKS = listOf(R.drawable.ic_ember_mark, R.drawable.ic_ember_mark_grey, R.drawable.ic_ember_mark_blue, R.drawable.ic_ember_mark_green, R.drawable.ic_ember_mark_rose)
private val FOREGROUNDS = listOf(R.drawable.ic_launcher_foreground, R.drawable.ic_launcher_foreground_grey, R.drawable.ic_launcher_foreground_blue, R.drawable.ic_launcher_foreground_green, R.drawable.ic_launcher_foreground_rose)

/** Ember's mark: the screen with a play button and the flame, in the accent's colourway. */
@Composable
fun EmberMark(size: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(MARKS.getOrElse(app.ember.tv.data.ColorThemes.artworkIndex(LocalAccentIndex.current)) { MARKS[0] }), contentDescription = null, modifier = modifier.size(size))
}

/** The "ember" wordmark in Poppins, drawn as shapes; [tint] follows the theme (white at night, dark by day). */
@Composable
fun EmberWordmark(height: Dp, tint: Color, modifier: Modifier = Modifier) {
    Image(
        painterResource(R.drawable.ic_ember_wordmark),
        contentDescription = "Ember",
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.height(height),
    )
}

/** Mark and wordmark side by side, as in the menu bar. [height] is the mark's size. */
@Composable
fun EmberLogo(height: Dp, tint: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(height * 0.28f)) {
        EmberMark(height)
        EmberWordmark(height * 0.6f, tint)
    }
}

/** The app icon as the launcher shows it (both adaptive layers, rounded), for About. */
@Composable
fun EmberAppIcon(size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.23f))) {
        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
        Image(painterResource(FOREGROUNDS.getOrElse(app.ember.tv.data.ColorThemes.artworkIndex(LocalAccentIndex.current)) { FOREGROUNDS[0] }), null, Modifier.fillMaxSize())
    }
}
