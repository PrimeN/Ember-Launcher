package app.ember.tv.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme

/** Floating surfaces stay readable over artwork and share the launcher's focus palette. */
internal object FloatingPanelStyle {
    val scrim=Color.Black.copy(alpha=.38f)
    val background:Color @Composable get()=if(isEmberX)EmberXColors.Surface else if(isShadowEmber)ShadowEmberColors.Surface else if(isModernEmber)ModernEmberColors.Surface else Color(0xFF1D1E22)
    val header:Color @Composable get()=if(isEmberX)EmberXColors.Raised else if(isShadowEmber)ShadowEmberColors.Background else if(isModernEmber)ModernEmberColors.Background else Color(0xFF232428)
    val accent:Color @Composable get()=if(isEmberX)EmberXColors.Accent else if(isShadowEmber)ShadowEmberColors.Focus else if(isModernEmber)ModernEmberColors.Focus else MaterialTheme.colorScheme.primary
}

@Composable
internal fun Modifier.floatingPanelSurface():Modifier {
    val shape=RoundedCornerShape((if(isEmberX)EmberXLayout.Radius else TvTokens.Shape.Panel).dp)
    return glassSurface(shape,panel=true)
}

/** Bounded TV modal; callers choose a scrollable form or provide their own bounded lazy body. */
@Composable
internal fun TvDialog(
    close:()->Unit,modifier:Modifier=Modifier,width:Dp=680.dp,height:Dp=540.dp,resizeForKeyboard:Boolean=false,scrollContent:Boolean=false,
    horizontalAlignment:Alignment.Horizontal=Alignment.Start,contentPadding:Dp=TvTokens.Space.XXL.dp,glassSurface:Boolean=false,content:@Composable ColumnScope.()->Unit,
) {
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        DialogMotion()
        if(resizeForKeyboard)KeyboardResizesDialog()
        var panelBounds by remember {mutableStateOf(Rect.Zero)}
        val dismiss by rememberUpdatedState(close)
        val soundContext=androidx.compose.ui.platform.LocalContext.current
        BoxWithConstraints(Modifier.fillMaxSize().onPreviewKeyEvent {RemoteSounds.onKey(soundContext,it.nativeKeyEvent);false}.pointerInput(Unit) {
            detectTapGestures {point->if(!panelBounds.contains(point))dismiss()}
        }.then(if(resizeForKeyboard)Modifier.imePadding() else Modifier).padding(24.dp),
            contentAlignment=Alignment.Center) {
            val size=MenuSizing.dialogSize(maxWidth,maxHeight,width,height)
            val shape=RoundedCornerShape(TvTokens.Shape.Panel.dp)
            val surface=if(glassSurface) Modifier.glassSurface(shape,panel=true)
                else Modifier.background(Panel,shape)
            CompositionLocalProvider(LocalContentColor provides Fg) {
                Column(modifier.guardOpeningPress().width(size.first).heightIn(max=size.second).onGloballyPositioned {panelBounds=it.boundsInRoot()}.then(surface)
                    .then(if(scrollContent)Modifier.verticalScroll(rememberMenuScrollState()) else Modifier).padding(contentPadding),
                    horizontalAlignment=horizontalAlignment,verticalArrangement=Arrangement.spacedBy(TvTokens.Space.M.dp),content=content)
            }
        }
    }
}

/** Modal headings share one hierarchy and keep their full spoken title. */
@Composable
internal fun DialogHeading(title:String,modifier:Modifier=Modifier) {
    androidx.tv.material3.Text(title,modifier=modifier.semantics {heading()},color=Fg,fontSize=TvTokens.Type.Dialog.sp,lineHeight=26.sp,
        fontWeight=FontWeight.SemiBold,maxLines=3,overflow=TextOverflow.Ellipsis)
}

/** Transient menus start at the top rather than restoring a previous dialog session's offset. */
@Composable
internal fun rememberMenuScrollState(): androidx.compose.foundation.ScrollState =
    remember { androidx.compose.foundation.ScrollState(0) }

/** Scale the panel's dimensions, text, icons and spacing together; keep user text magnification. */
@Composable
internal fun CompactPanelContent(content: @Composable () -> Unit) {
    val parent=androidx.compose.ui.platform.LocalDensity.current
    val density=remember(parent) {androidx.compose.ui.unit.Density(parent.density*MenuSizing.COMPACT_PANEL_SCALE,parent.fontScale)}
    CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides density,content=content)
}
