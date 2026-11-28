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
import androidx.compose.ui.focus.onFocusChanged
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
 * left and right move it a step (the change applies at once), OK moves it on and wraps. At either
 * end the press moves focus on like any control.
 *
 * #474: a rounded card with the name and value above a thick pill track, like the TV's own picture
 * settings. Focused, the card turns light and the track dark; ‹ › show it moves with left and right.
 */
@Composable
internal fun StepSlider(
    name: String,
    values: List<Int>,
    selected: Int,
    label: (Int) -> String,
    modifier: Modifier = Modifier,
    width: Dp = 420.dp,
    /** False where a heading right above already names it. */
    showName: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onPick: (Int) -> Unit,
) {
    if (values.isEmpty()) return
    RecordSettingOption(name)
    val at = values.indexOf(selected).takeIf { it >= 0 } ?: StepSliders.nearest(values, selected)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Focusable(
        onClick = { onPick(values[(at + 1) % values.size]) },
        modifier = modifier.width(width).testTag("step_slider")
            .onFocusChanged { focused = it.isFocused }
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
        radius = TvTokens.Shape.Control, container = Wash, focusedContainer = PillColor, focusedContent = OnPillColor,
        outline = false, focusedScale = TvTokens.Focus.None,
    ) {
        val ink = androidx.tv.material3.LocalContentColor.current
        val accent = if (focused) OnPillColor else MaterialTheme.colorScheme.primary
        val share = if (values.size <= 1) 1f else at.toFloat() / (values.size - 1)
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (icon != null) androidx.tv.material3.Icon(icon, null, Modifier.size(20.dp), tint = ink)
                if (showName) Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f),
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                else Spacer(Modifier.weight(1f))
                Text(if (focused && at > 0) "‹" else " ", fontSize = 15.sp, color = ink.copy(alpha = .6f))
                Text(label(values[at]), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(if (focused && at < values.lastIndex) "›" else " ", fontSize = 15.sp, color = ink.copy(alpha = .6f))
            }
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(if (focused) accent.copy(alpha = .22f) else ink.copy(alpha = .16f))) {
                if (share > 0f) Box(Modifier.fillMaxWidth(share.coerceAtLeast(.04f)).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(if (focused) accent else ink))
            }
        }
    }
}
