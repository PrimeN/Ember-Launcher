package app.ember.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import app.ember.tv.data.InterfaceStyles

/** Native colour roles for the Ember X interface. Palette values preserve the selected visual design. */
internal object EmberXColors {
    val Accent:Color
        @Composable @ReadOnlyComposable get()=androidx.tv.material3.MaterialTheme.colorScheme.primary
    val Background=Color(0xFF0B0B0D)
    val Raised=Color(0xFF131317)
    val Surface=Color(0xFF18181D)
    val SurfaceHi=Color(0xFF212128)
    val Selected=Color(0xFF2B2B33)
    val Text=Color(0xFFF4F4F5)
    val Secondary=Color(0xFFC4C4CC)
    val Muted=Color(0xFF8A8A96)
    val Line=Color.White.copy(alpha=.08f)
}
internal val isEmberX:Boolean
    @Composable @ReadOnlyComposable get()=LocalInterfaceStyle.current==InterfaceStyles.EMBER_X

/** Layout ratios use available TV space rather than assuming a desktop pixel resolution. */
internal object EmberXLayout {
    const val Gutter=40
    const val NavHeight=54
    const val Radius=8
    fun heroHeight(viewport:Float,fontScale:Float)=maxOf(viewport.coerceAtLeast(0f)*.73f,280f*fontScale.coerceAtLeast(1f))
    fun splitDetails(width:Float,fontScale:Float)=width.isFinite() && fontScale.isFinite() && width>=740f && fontScale<=1.3f
    fun posterCount(width:Float,fontScale:Float):Float=when {
        fontScale>1.25f->4.25f
        width>=1100f->6.25f
        else->5.25f
    }
}
