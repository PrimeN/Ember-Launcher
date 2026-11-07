package app.ember.tv.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.runtime.*

/**
 * One Move control per row (#107): the Move icon used elsewhere in Ember, opening To the top,
 * Up, Down and To the bottom (or the front / left / right / end for rows that run sideways).
 * [onMove] gets the step: negative is towards the top or front.
 */
@Composable
internal fun MoveButton(name: String, index: Int, lastIndex: Int, horizontal: Boolean = false, onMove: (Int) -> Unit) {
    if (lastIndex <= 0 || index !in 0..lastIndex) return
    var open by remember { mutableStateOf(false) }
    ToolbarIcon("Move", Icons.Outlined.OpenWith) { open = true }
    if (open) OptionMenu("Move ${name.ifBlank { "item" }}", moveOptions(index, lastIndex, horizontal).map { (label, step) -> MenuOption(label) { onMove(step) } }) { open = false }
}

/** The choices for an item at [index] of [lastIndex] + 1, with their steps. */
internal fun moveOptions(index: Int, lastIndex: Int, horizontal: Boolean = false): List<Pair<String, Int>> = buildList {
    if (index > 1) add((if (horizontal) "To the front" else "To the top") to -index)
    if (index > 0) add((if (horizontal) "Left" else "Up") to -1)
    if (index < lastIndex) add((if (horizontal) "Right" else "Down") to 1)
    if (index < lastIndex - 1) add((if (horizontal) "To the end" else "To the bottom") to lastIndex - index)
}

/** [index] moved by [step], clamped to the list. */
internal fun <T> List<T>.moved(index: Int, step: Int): List<T> {
    if (index !in indices) return this
    val to = (index + step).coerceIn(0, lastIndex)
    return if (to == index) this else toMutableList().apply { add(to, removeAt(index)) }
}
