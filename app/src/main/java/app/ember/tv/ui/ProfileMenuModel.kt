package app.ember.tv.ui

import app.ember.tv.data.NavTabs
import app.ember.tv.data.Profile
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider

internal enum class ProfileMenuAction(val label: String, val page: String? = null, val settings: String? = null) {
    WATCHLIST("Watchlist", page = NavTabs.WATCHLIST),
    CONTINUE("Continue watching", page = NavTabs.PROGRESS),
    RECENT("Recently watched", page = NavTabs.RECENT),
    LIBRARY("Library", page = NavTabs.LIBRARY),
    PROFILES("Manage profiles", settings = "PROFILES"),
}

/** Personal shortcuts obey the same profile rules as the main pages, even with hidden tabs. */
internal fun profileMenuActions(profile: Profile, libraryVisible: Boolean): List<ProfileMenuAction> = buildList {
    if (profile.personal && !profile.kids) {
        add(ProfileMenuAction.WATCHLIST)
        add(ProfileMenuAction.CONTINUE)
        add(ProfileMenuAction.RECENT)
    }
    // #205: no "Manage profiles" row; the Add button beside the avatars opens it.
}

/** Anchor to the actual avatar in every navigation style, keeping the panel in the viewport. */
internal class ProfileMenuPosition(private val gap: Int, private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
        val maxY = (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)
        val x = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left
            else anchorBounds.right - popupContentSize.width
        val below = anchorBounds.bottom + gap
        val above = anchorBounds.top - gap - popupContentSize.height
        val y = if (below <= maxY) below else if (above >= margin) above else below.coerceAtMost(maxY)
        return IntOffset(x.coerceIn(margin, maxX), y.coerceIn(margin, maxY))
    }
}
