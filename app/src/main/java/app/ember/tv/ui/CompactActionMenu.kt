package app.ember.tv.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/** Wraps its contents in both directions, with a scroll cap for large fonts and long custom lists. */
@Composable
internal fun CompactActionMenu(title: String, labels: List<String>, close: () -> Unit, content: @Composable ColumnScope.(FocusRequester) -> Unit) {
    val closeLabel=androidx.compose.ui.res.stringResource(app.ember.tv.R.string.ui_close)
    val density=LocalDensity.current
    val window=LocalWindowInfo.current.containerSize
    val measure=rememberTextMeasurer()
    val labelStyle=actionMenuTextStyle()
    val width=remember(title,labels,density,window,measure,labelStyle) {with(density) {
        fun textWidth(text:String,size:androidx.compose.ui.unit.TextUnit,weight:FontWeight)=
            measure.measure(text,TextStyle(fontSize=size,fontWeight=weight),softWrap=false,maxLines=1).size.width.toDp()
        MenuSizing.actionMenuWidth((labels.maxOfOrNull {measure.measure(MenuSizing.actionLabel(it),labelStyle,softWrap=false,maxLines=1).size.width.toDp()} ?: 0.dp),
            textWidth(title,TvTokens.Type.Navigation.sp,FontWeight.SemiBold),(window.width.toDp()-48.dp).coerceAtLeast(0.dp))
    }}
    val first=remember {FocusRequester()}
    val closeFocus=remember {FocusRequester()}
    TvDialog(close,width=width,height=with(density){window.height.toDp()},scrollContent=true,
        contentPadding=MenuSizing.ACTION_DIALOG_PADDING,glassSurface=true) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(title,color=Fg,fontSize=TvTokens.Type.Navigation.sp,fontWeight=FontWeight.SemiBold,maxLines=1,softWrap=false,overflow=TextOverflow.Ellipsis,
                modifier=Modifier.weight(1f).semantics {heading()})
            Focusable(close,Modifier.focusRequester(closeFocus).size(32.dp).semantics {contentDescription=closeLabel},radius=app.ember.tv.ui.TvTokens.Shape.Pill,
                container=Wash,focusedContainer=PillColor,focusedContent=OnPillColor,outline=false) {
                Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){MaterialGlyph(Icons.Outlined.Close,androidx.tv.material3.LocalContentColor.current,18.dp)}
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Fg.copy(alpha=.10f)))
        Column(Modifier.fillMaxWidth().focusGroup(),verticalArrangement=Arrangement.spacedBy(2.dp)) {content(first)}
        LaunchedEffect(labels.isNotEmpty()) {
            withFrameNanos { }
            if(labels.isEmpty() || !runCatching {first.requestFocus()}.getOrDefault(false))runCatching {closeFocus.requestFocus()}
        }
    }
}
