package app.ember.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

internal object DescriptionContrast {
    const val DAY_TEXT=0xFF3C4043.toInt()
    const val NIGHT_TEXT=0xFFE8EAED.toInt()
}

/** Text sits directly on the continuous hero scrim; no dark box, padding block or drop shadow. */
@Composable internal fun ReadableDescription(text:String,modifier:Modifier=Modifier,fontSize:TextUnit=TvTokens.Type.CompactBody.sp,
    lineHeight:TextUnit=20.sp,maxLines:Int=3,minLines:Int=1) {
    val day=Ui.isDay && !LocalNight.current && !LocalFloating.current
    Text(text,modifier,
        color=Color(if(day)DescriptionContrast.DAY_TEXT else DescriptionContrast.NIGHT_TEXT),fontSize=fontSize,lineHeight=lineHeight,
        maxLines=maxLines,minLines=minLines,overflow=TextOverflow.Ellipsis)
}
