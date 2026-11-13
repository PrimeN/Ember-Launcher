package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** Where a step slider goes on a left or right press (#213). */
internal object StepSliders {
    /** The step after [index] that way, or null at the end, so the press moves focus on instead. */
    fun step(index: Int, count: Int, forward: Boolean): Int? = (if (forward) index + 1 else index - 1).takeIf { it in 0 until count }
    /** The step nearest a value that isn't one of them, such as an old saved choice. */
    fun nearest(values: List<Int>, value: Int): Int = values.indices.minByOrNull { kotlin.math.abs(values[it] - value) } ?: 0
}

/**
 * A slider over a few set values (#213), in place of a row of number buttons. One focus stop:
 * left and right move it a step (the change applies at once), OK moves it on and wraps. The chosen
 * value is written beside the track; at either end the press moves focus on like any control.
 */
@Composable
internal fun StepSlider(
    name: String,
    values: List<Int>,
    selected: Int,
    label: (Int) -> String,
    modifier: Modifier = Modifier,
    width: Dp = 420.dp,
    onPick: (Int) -> Unit,
) {
    if (values.isEmpty()) return
    RecordSettingOption(name)
    val at = values.indexOf(selected).takeIf { it >= 0 } ?: StepSliders.nearest(values, selected)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val accent = MaterialTheme.colorScheme.primary
    Focusable(
        onClick = { onPick(values[(at + 1) % values.size]) },
        modifier = modifier.width(width).testTag("step_slider")
            .semantics {
                contentDescription = name
                stateDescription = label(values[at])
                progressBarRangeInfo = ProgressBarRangeInfo(at.toFloat(), 0f..(values.size - 1).coerceAtLeast(1).toFloat(), (values.size - 2).coerceAtLeast(0))
                setProgress { target -> values.getOrNull(target.toInt().coerceIn(0, values.lastIndex))?.let { onPick(it); true } ?: false }
            }
            .onPreviewKeyEvent { e ->
                val forward = when (e.key) { Key.DirectionRight -> !rtl; Key.DirectionLeft -> rtl; else -> return@onPreviewKeyEvent false }
                val next = StepSliders.step(at, values.size, forward) ?: return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyDown) onPick(values[next])
                true
            },
        radius = TvTokens.Shape.Control, container = Wash, focusedScale = TvTokens.Focus.None,
    ) {
        val ink = androidx.tv.material3.LocalContentColor.current
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BoxWithConstraints(Modifier.weight(1f).height(20.dp), contentAlignment = Alignment.CenterStart) {
                val share = if (values.size <= 1) 1f else at.toFloat() / (values.size - 1)
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(ink.copy(alpha = .25f)))
                Box(Modifier.fillMaxWidth(share).height(4.dp).clip(RoundedCornerShape(2.dp)).background(accent))
                // A mark at every step, and the knob on the chosen one.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    values.indices.forEach { i -> Box(Modifier.size(6.dp).clip(CircleShape).background(if (i <= at) accent else ink.copy(alpha = .45f))) }
                }
                Box(Modifier.offset(x = (maxWidth - 18.dp) * share).size(18.dp).clip(CircleShape).background(ink))
            }
            Text(label(values[at]), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.widthIn(min = 64.dp), maxLines = 1)
        }
    }
}
