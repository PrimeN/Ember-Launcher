package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.data.ColorThemes

/** Preview the candidate palette rather than recoloring every choice with the current theme. */
internal fun themeChoiceColors(index: Int, selected: Boolean, focused: Boolean, background: Color): Pair<Color, Color> {
    val accent = Accents[ColorThemes.normalize(index)].second
    val fill = lerp(background, accent, if (focused) 1f else if (selected) .88f else .5f)
    return fill to readablePillText(fill)
}

internal fun themePreviewSwatches(index: Int): List<Color> = when (ColorThemes.normalize(index)) {
    ColorThemes.MODERN_EMBER -> listOf(ModernEmberColors.Focus, ModernEmberColors.Violet, ModernEmberColors.Teal)
    ColorThemes.SHADOW_EMBER -> listOf(ShadowEmberColors.Accent, ShadowEmberColors.SurfaceHi, ShadowEmberColors.Focus)
    else -> listOf(Accents[ColorThemes.normalize(index)].second)
}

@Composable
internal fun ThemePaletteSwatch(index: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        themePreviewSwatches(index).forEach { color -> Box(Modifier.size(10.dp).background(color, CircleShape)) }
    }
}

@Composable
internal fun ThemeColorPicker(selected: Int, onSelect: (Int) -> Unit) {
    ButtonRow(Modifier.selectableGroup(), spacing = 10.dp) {
        Accents.forEachIndexed { index, (label, _) ->
            var focused by remember(index) { mutableStateOf(false) }
            val chosen = index == selected
            val resting = themeChoiceColors(index, chosen, false, Panel)
            val focus = themeChoiceColors(index, chosen, true, Panel)
            Focusable(onClick = { onSelect(index) }, modifier = Modifier.onFocusChanged { focused = it.isFocused }
                .semantics { this.selected = chosen; contentDescription = label }, role = Role.RadioButton,
                radius = TvTokens.Shape.Pill, container = resting.first, focusedContainer = focus.first,
                focusedContent = focus.second, focusedScale = app.ember.tv.ui.TvTokens.Focus.Control) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = if (focused) focus.second else resting.second, fontSize = TvTokens.Type.Label.sp,
                        fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium, maxLines = 1, softWrap = false)
                    ThemePaletteSwatch(index)
                }
            }
        }
    }
}
