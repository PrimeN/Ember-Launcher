package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme

@Composable
private fun inlineDetailsTextStyle(secondary:Boolean=false):TextStyle = MaterialTheme.typography.bodyMedium.copy(
    fontSize=if(secondary)TvTokens.Type.Detail.sp else TvTokens.Type.Compact.sp,
    lineHeight=if(secondary)14.sp else 18.sp,
    fontWeight=if(secondary)FontWeight.Normal else FontWeight.SemiBold,
    platformStyle=PlatformTextStyle(includeFontPadding=false))

/** Glass stays visible at rest; focus and saved states use the navigation palette. */
internal fun detailsActionFill(enabled:Boolean,focused:Boolean,selected:Boolean,focusColor:Color,selectionColor:Color):Color=when {
    !enabled -> Color.Transparent
    focused -> focusColor
    selected -> selectionColor
    else -> Color.Transparent
}

/** Small labels do not inherit a wide fixed CTA; long details still fit within a sensible cap. */
internal fun detailsActionWidth(label:Dp,detail:Dp,icon:Boolean):Dp =
    maxOf(label + if(icon)54.dp else 24.dp, detail + 24.dp, 72.dp).coerceAtMost(224.dp)

/** Primary labels always fit; source details use the remaining space on the same line. */
internal fun inlineDetailsActionWidth(label:Dp,detail:Dp,icon:Boolean):Dp {
    val primary=maxOf(label + if(icon)54.dp else 24.dp,72.dp)
    return maxOf(primary,minOf(primary + if(detail>0.dp)detail+8.dp else 0.dp,224.dp))
}

@Composable
private fun measuredDetailsActionWidth(label:String,detail:String?,icon:Boolean):Dp {
    val measurer=androidx.compose.ui.text.rememberTextMeasurer()
    val density=androidx.compose.ui.platform.LocalDensity.current
    val primaryStyle=inlineDetailsTextStyle()
    val secondaryStyle=inlineDetailsTextStyle(true)
    return with(density) {
        val primary=measurer.measure(label,primaryStyle,softWrap=false,maxLines=1).size.width.toDp()
        val secondary=detail?.let {measurer.measure(it,secondaryStyle,softWrap=false,maxLines=1).size.width.toDp()} ?: 0.dp
        inlineDetailsActionWidth(primary,secondary,icon)
    }
}

/** Shared glass styling; labels stay sharp and only the sampled artwork is blurred. */
@Suppress("ModifierParameter") // Callers pass label/icon/text positionally; modifier stays a named argument.
@Composable
internal fun DetailsActionButton(
    label:String, icon:ImageVector?=null, text:String?=null, detail:String?=null,
    selected:Boolean=false, enabled:Boolean=true, width:Dp=56.dp, minHeight:Dp=42.dp, compact:Boolean=false, inline:Boolean=false, modifier:Modifier=Modifier, onClick:()->Unit,
) {
    var focused by remember {mutableStateOf(false)}
    val compactHeight=maxOf(minHeight,with(androidx.compose.ui.platform.LocalDensity.current){32.sp.toDp()}+10.dp)
    val inlineHeight=maxOf(minHeight,with(androidx.compose.ui.platform.LocalDensity.current){18.sp.toDp()}+12.dp)
    val hasDetail=!detail.isNullOrBlank()
    val primary=isEmberX && text!=null && icon==Icons.Outlined.PlayArrow
    val foreground=if(!enabled)Fg.copy(alpha=.45f) else if(primary)Color.Black else if(focused)OnPillColor else Fg
    // Primary stays light, but focus must still read without the zoom (reduced motion or low power).
    val fill=if(primary && enabled)(if(focused)Color.White else Color.White.copy(alpha=.74f)) else detailsActionFill(enabled,focused,selected,PillColor,WashHi)
    val shape=if(isEmberX)androidx.compose.foundation.shape.RoundedCornerShape(14.dp) else CircleShape
    Focusable(onClick={if(enabled)onClick()},enabled=enabled,modifier=modifier.width(width).then(when {inline->Modifier.height(inlineHeight);compact->Modifier.height(compactHeight);else->Modifier.heightIn(min=minHeight)})
        .onFocusChanged {focused=it.isFocused}.selectionCue(selected,Fg).semantics {
            contentDescription=label
            if(selected)this.selected=true
            if(!enabled)disabled()
        },radius=if(isEmberX)14 else TvTokens.Shape.Pill,container=Color.Transparent,focusedContainer=Color.Transparent,
        focusedContent=foreground,outline=false,focusedScale=TvTokens.Focus.Control) {
        FrostedPill(Modifier.fillMaxWidth()) {
            if(inline) {
                Row(Modifier.fillMaxWidth().height(inlineHeight).navigationGlass(shape).background(fill,shape)
                    .padding(horizontal=if(text==null)10.dp else 12.dp,vertical=6.dp),
                    horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
                    icon?.let {Icon(it,null,tint=foreground,modifier=Modifier.size(22.dp))}
                    if(text!=null) {
                        if(icon!=null)Spacer(Modifier.width(8.dp))
                        Text(text,color=foreground,fontSize=TvTokens.Type.Compact.sp,lineHeight=18.sp,fontWeight=FontWeight.SemiBold,
                            maxLines=1,softWrap=false,overflow=TextOverflow.Ellipsis,
                            style=inlineDetailsTextStyle())
                        detail?.takeIf(String::isNotBlank)?.let {
                            Spacer(Modifier.width(8.dp))
                            Text(it,color=foreground.copy(alpha=.8f),fontSize=TvTokens.Type.Detail.sp,lineHeight=14.sp,
                                maxLines=1,softWrap=false,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f),
                                style=inlineDetailsTextStyle(true))
                        }
                    }
                }
            } else {
            Column(Modifier.fillMaxWidth().navigationGlass(shape).background(fill,shape)
                .then(if(compact)Modifier.fillMaxHeight() else Modifier.heightIn(min=minHeight))
                .padding(horizontal=if(compact)10.dp else if(text==null)10.dp else 12.dp,vertical=if(compact)4.dp else 6.dp),
                horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
                    icon?.let {Icon(it,null,tint=foreground,modifier=Modifier.size(if(compact)18.dp else 22.dp))}
                    if(text!=null) {
                        if(icon!=null)Spacer(Modifier.width(if(compact)6.dp else 8.dp))
                        Text(text,color=foreground,fontSize=if(compact)12.sp else TvTokens.Type.Compact.sp,lineHeight=if(compact)16.sp else 18.sp,fontWeight=FontWeight.SemiBold,
                            textAlign=TextAlign.Center,maxLines=if(compact && hasDetail)1 else 2,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f,fill=false),
                            style=TextStyle(platformStyle=PlatformTextStyle(includeFontPadding=false)))
                    }
                }
                detail?.takeIf(String::isNotBlank)?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it,color=foreground.copy(alpha=.8f),fontSize=TvTokens.Type.Caption.sp,lineHeight=14.sp,textAlign=TextAlign.Center,
                        maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.fillMaxWidth(),
                        style=TextStyle(platformStyle=PlatformTextStyle(includeFontPadding=false)))
                }
            }
            }
        }
    }
}

@Composable
internal fun DetailsBackButton(modifier:Modifier=Modifier,onBack:()->Unit) {
    DetailsActionButton("Back to browsing",Icons.AutoMirrored.Outlined.ArrowBack,width=42.dp,modifier=modifier,onClick=onBack)
}

/** Play and Trailer lead a single action strip; icon actions follow on the right. */
@Composable
internal fun DetailsActions(
    saved:Boolean,watched:Boolean,canMarkWatched:Boolean,playback:DetailsPlaybackPresentation,trailer:Boolean,
    first:FocusRequester,onWatched:()->Unit,onSaved:()->Unit,onMore:()->Unit,
    onPlay:()->Unit,onTrailer:()->Unit,modifier:Modifier=Modifier,
) {
    var trailerFocused by remember {mutableStateOf(false)}
    LaunchedEffect(trailer) {if(!trailer && trailerFocused)runCatching {first.requestFocus()}}
    val actionHeight=maxOf(42.dp,with(androidx.compose.ui.platform.LocalDensity.current){18.sp.toDp()}+12.dp)
    Row(modifier.horizontalScroll(rememberScrollState()).focusGroup(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
        val playWidth=measuredDetailsActionWidth(playback.label,playback.detail,true)
        val trailerWidth=measuredDetailsActionWidth("Trailer",null,false)
        DetailsActionButton(listOfNotNull(playback.label,playback.detail).joinToString(" · "),Icons.Outlined.PlayArrow,
            text=playback.label,detail=playback.detail,enabled=playback.enabled,width=playWidth,minHeight=actionHeight,inline=true,
            modifier=if(playback.enabled)Modifier.focusRequester(first) else Modifier,onClick=onPlay)
        if(trailer)DetailsActionButton("Play trailer",text="Trailer",width=trailerWidth,minHeight=actionHeight,inline=true,
            modifier=Modifier.onFocusChanged {trailerFocused=it.isFocused},onClick=onTrailer)
        DetailsActionButton(if(!canMarkWatched)"Select an episode before marking watched" else if(watched)"Mark as unwatched" else "Mark as watched",Icons.Outlined.Visibility,
            selected=watched,enabled=canMarkWatched,width=actionHeight,minHeight=actionHeight,inline=true,onClick=onWatched)
        DetailsActionButton(if(saved)"Remove from watchlist" else "Add to watchlist",if(saved)Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
            selected=saved,width=actionHeight,minHeight=actionHeight,inline=true,
            modifier=if(playback.enabled)Modifier else Modifier.focusRequester(first),onClick=onSaved)
        DetailsActionButton("About and more",Icons.Outlined.MoreHoriz,width=actionHeight,minHeight=actionHeight,inline=true,onClick=onMore)
    }
}
