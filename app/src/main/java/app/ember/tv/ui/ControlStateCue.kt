package app.ember.tv.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** One contrasting edge; no stacked black/white outlines or extra background fill. */
internal object ControlStateCue {
    val Light=Color.White
    val Dark=Color(0xFF101114)
    fun edge(background:Color):Color {
        val back=background.luminance()
        fun contrast(color:Color):Float {val value=color.luminance();return (maxOf(back,value)+.05f)/(minOf(back,value)+.05f)}
        return if(contrast(Light)>=contrast(Dark))Light else Dark
    }
}

internal fun Modifier.focusCue(focused:Boolean,radius:Int,color:Color=ControlStateCue.Light)=drawWithContent {
    drawContent()
    if(focused) {
        val stroke=2.dp.toPx()
        val inset=stroke/2
        val edge=Size((size.width-stroke).coerceAtLeast(0f),(size.height-stroke).coerceAtLeast(0f))
        val corner=CornerRadius((minOf(radius.dp.toPx(),size.minDimension/2)-inset).coerceAtLeast(0f))
        drawRoundRect(color,Offset(inset,inset),edge,corner,style=Stroke(stroke))
    }
}
