package app.ember.tv.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Lists load their next page on their own (#109): when the last few cards come into view, the
 * next page is asked for. [enabled] is false while a page loads, at the end, after a failure,
 * or when "Load more as I scroll" is off.
 */
@Composable
internal fun AutoLoadMore(state: LazyGridState, count: Int, enabled: Boolean, onLoad: () -> Unit) {
    LaunchedEffect(state, count, enabled) {
        if (!enabled || count == 0) return@LaunchedEffect
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.distinctUntilChanged()
            .collect { last -> if (nearEnd(last, count)) onLoad() }
    }
}

@Composable
internal fun AutoLoadMore(state: LazyListState, count: Int, enabled: Boolean, onLoad: () -> Unit) {
    LaunchedEffect(state, count, enabled) {
        if (!enabled || count == 0) return@LaunchedEffect
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.distinctUntilChanged()
            .collect { last -> if (nearEnd(last, count)) onLoad() }
    }
}

/** True when the last visible card is within a few of the end. */
internal fun nearEnd(lastVisible: Int, count: Int, within: Int = 8) = count > 0 && lastVisible >= (count - within).coerceAtLeast(0)

/**
 * The end of a paged list (#109): grey placeholders while the next page loads on its own, Retry
 * after a failure, and the Load more button only when automatic loading is off.
 */
@Composable
internal fun PageFooter(auto: Boolean, loading: Boolean, failed: Boolean, moreLabel: String = "Load more", wide: Boolean = false, onLoad: () -> Unit) {
    when {
        failed && !loading -> Chip("Retry", onClick = onLoad)
        auto -> if (loading) LoadingCards(wide)
        else -> Chip(if (loading) "Loading…" else moreLabel, onClick = onLoad)
    }
}
