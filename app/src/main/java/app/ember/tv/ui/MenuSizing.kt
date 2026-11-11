package app.ember.tv.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Compact TV menus expand for accessibility text and stay inside the actual window. */
internal object MenuSizing {
    const val COMPACT_PANEL_SCALE=0.7f
    /** A wide dialog (#204): nearly the whole screen, less the margin every dialog keeps. */
    val WIDE_DIALOG = 900.dp
    val WIDE_DIALOG_HEIGHT = 620.dp
    val ACTION_PADDING = 10.dp
    val ACTION_ICON = 18.dp
    val ACTION_GAP = 8.dp
    val ACTION_DIALOG_PADDING = 12.dp
    private val whitespace = Regex("\\s+")
    fun actionLabel(text: String) = text.replace(whitespace, " ").trim()
    /** Fit one-line actions plus padding. Long headings ellipsize instead of inflating the menu. */
    fun actionMenuWidth(label: Dp, title: Dp, available: Dp): Dp =
        minOf(available.coerceAtLeast(0.dp), maxOf(label + ACTION_PADDING * 2 + ACTION_ICON + ACTION_GAP + 4.dp,
            minOf(title, 140.dp) + 38.dp) + ACTION_DIALOG_PADDING * 2)
    /** Extra profiles scroll; they must not inflate the whole dropdown. Includes the Add slot. */
    fun profileAvatarStripWidth(profileCount: Int): Dp {
        val slots = profileCount.coerceIn(1, 2) + 1
        return 48.dp * slots + 6.dp * (slots - 1) + 8.dp
    }
    fun panelWidth(available: Dp, fontScale: Float): Dp = minOf(available, if (fontScale > 1.2f) 440.dp else 384.dp)
    fun profileWidth(available: Dp, fontScale: Float): Dp = minOf(available, if (fontScale > 1.2f) 328.dp else 288.dp)
    /** The profile menu as wide as its content needs, no narrower than 200 dp and no wider than [profileWidth]. */
    fun profileMenuWidth(content: Dp, available: Dp, fontScale: Float): Dp =
        minOf(profileWidth(available, fontScale), content.coerceAtLeast(200.dp))
    fun dialogSize(availableWidth:Dp,availableHeight:Dp,width:Dp,height:Dp):Pair<Dp,Dp> =
        minOf(availableWidth.coerceAtLeast(0.dp),width.coerceAtLeast(0.dp)) to
            minOf(availableHeight.coerceAtLeast(0.dp),height.coerceAtLeast(0.dp))
    fun quickColumns(availableWidth:Dp,fontScale:Float)=if(availableWidth<320.dp || fontScale>1.2f)1 else 2
    /** One height for every Quick settings tile (icon, one-line name, two-line note), growing with larger text. */
    fun quickTileHeight(fontScale:Float):Dp=(104f*fontScale.coerceAtLeast(1f)).dp
}
