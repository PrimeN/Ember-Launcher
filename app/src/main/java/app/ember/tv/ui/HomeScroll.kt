package app.ember.tv.ui

import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalFocusManager

/** Manual scrolling must not leave a pinned, invisible hero button owning remote focus. */
@Composable
internal fun ReleaseHiddenHeroFocus(state: LazyListState, heroFocused: Boolean) {
    val manager = LocalFocusManager.current
    val focused by rememberUpdatedState(heroFocused)
    val visible by remember(state) { derivedStateOf {
        val layout = state.layoutInfo
        layout.visibleItemsInfo.any {
            it.key == "hero" && it.offset + it.size > layout.viewportStartOffset && it.offset < layout.viewportEndOffset
        }
    } }
    // Key on visibility alone: requesting an offscreen hero while moving Up must still restore it.
    LaunchedEffect(visible) { if (!visible && focused) manager.clearFocus() }
}

/** Vertical focus scrolling for the Home list; nested rows keep their normal horizontal policy. */
internal class HomeBringIntoViewSpec(
    private val state: LazyListState,
    private val normal: BringIntoViewSpec,
    private val heroFocused: () -> Boolean,
    private val heroFits: () -> Boolean,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val distance = normal.calculateScrollDistance(offset, size, containerSize)
        if (heroFocused()) return HeroLayout.focusScroll(true, distance, heroFits())
        val layout = state.layoutInfo
        val hero = layout.visibleItemsInfo.firstOrNull { it.key == "hero" } ?: return distance
        val bottom = (hero.offset + hero.size - layout.viewportStartOffset).toFloat()
        // The normal TV pivot can leave the banner's buttons and dots above the focused row.
        return if (bottom > 0f && offset >= bottom - 1f) maxOf(distance, bottom) else distance
    }
}
