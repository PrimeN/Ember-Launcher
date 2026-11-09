package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage

/** Live TV follows the app's colour theme (accent, Day/Night, OLED), like every other page. */
internal object LiveColors {
    val Background: Color @Composable @ReadOnlyComposable get() = Ink
    // Slightly see-through, so guide cells and channels sit on the theme glow (#141).
    val Cell: Color @Composable @ReadOnlyComposable get() = Panel.copy(alpha = .80f)
    val Current: Color @Composable @ReadOnlyComposable get() = PanelHi.copy(alpha = .90f)
    val Focus: Color @Composable @ReadOnlyComposable get() = PillColor
    val OnFocus: Color @Composable @ReadOnlyComposable get() = OnPillColor
    val Muted: Color @Composable @ReadOnlyComposable get() = Mute
    /** Titles, channel names and icons. */
    val Text: Color @Composable @ReadOnlyComposable get() = Fg
}

@Composable
internal fun LiveToolbarAction(label:String,icon:ImageVector,modifier:Modifier=Modifier,selected:Boolean=false,onClick:()->Unit) {
    ToolbarIcon(label, icon, modifier.size(38.dp), selected, onClick)
}

@Composable
internal fun LiveSidebarHeading(label:String) {
    Text(label.uppercase(),color=LiveColors.Muted,fontSize=TvTokens.Type.Caption.sp,fontWeight=FontWeight.SemiBold,
        letterSpacing=1.sp,modifier=Modifier.padding(start=12.dp,top=12.dp,bottom=6.dp).semantics {heading()})
}

@Composable
internal fun LiveSidebarEntry(
    title:String,count:Int,icon:ImageVector,modifier:Modifier=Modifier,selected:Boolean=false,expanded:Boolean?=null,
    nested:Boolean=false,onFocused:(FocusRequester)->Unit={},onEnterGuide:()->Unit={},onClick:()->Unit,onLongClick:(()->Unit)?=null,
) {
    var focused by remember {mutableStateOf(false)}
    val requester=remember {FocusRequester()}
    val rtl=LocalLayoutDirection.current==LayoutDirection.Rtl
    Focusable(onClick=onClick,onLongClick=onLongClick,modifier=modifier.fillMaxWidth().focusRequester(requester)
        .onFocusChanged {focused=it.isFocused;if(it.isFocused)onFocused(requester)}
        .onPreviewKeyEvent {event->
            if(event.key==if(rtl)Key.DirectionLeft else Key.DirectionRight) {
                if(event.type==KeyEventType.KeyDown)onEnterGuide();true
            } else false
        }.semantics {
            this.selected=selected
            stateDescription=listOfNotNull("$count channels",expanded?.let {if(it)"Expanded" else "Collapsed"}).joinToString(", ")
        },container=if(selected)androidx.tv.material3.MaterialTheme.colorScheme.primary.copy(alpha=.26f) else Color.Transparent,
        focusedContainer=LiveColors.Focus,focusedContent=LiveColors.OnFocus,focusedScale=TvTokens.Focus.Inline,radius=TvTokens.Shape.Control,outline=false) {
        val color=if(focused)LiveColors.OnFocus else LiveColors.Text
        Row(Modifier.fillMaxWidth().padding(start=if(nested)22.dp else 12.dp,end=10.dp,top=10.dp,bottom=10.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            if(!nested)Box(Modifier.size(34.dp).background(if(focused)LiveColors.OnFocus.copy(alpha=.12f) else LiveColors.Current,CircleShape),contentAlignment=Alignment.Center) {
                Icon(icon,null,tint=color,modifier=Modifier.size(19.dp))
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(title,color=color,fontSize=if(nested)12.sp else 14.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text("$count "+if(count==1)"channel" else "channels",color=color.copy(alpha=.7f),fontSize=TvTokens.Type.Caption.sp)
            }
            expanded?.let {Icon(if(it)Icons.Outlined.ExpandMore else if(rtl)Icons.Outlined.ChevronLeft else Icons.Outlined.ChevronRight,null,tint=color,modifier=Modifier.size(16.dp))}
        }
    }
}

/** Live inherits the app palette, including Day; no forced night text on light backgrounds. */
@Composable
internal fun LivePageTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(androidx.tv.material3.LocalContentColor provides Fg, androidx.tv.material3.LocalTextStyle provides
        androidx.tv.material3.MaterialTheme.typography.bodyLarge.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified), content = content)
}

/** Rendering and focus transfer must agree that the destination row actually exists. */
internal fun liveFeaturedAvailable(inline: Boolean, enabled: Boolean, hasChannels: Boolean, searching: Boolean, kind: String) =
    inline && enabled && hasChannels && !searching && kind != "addons"
