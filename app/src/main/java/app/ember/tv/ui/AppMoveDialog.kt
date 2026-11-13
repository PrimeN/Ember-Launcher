package app.ember.tv.ui

import androidx.compose.ui.res.stringResource
import app.ember.tv.R

import app.ember.tv.setAppOrder
import app.ember.tv.lightAppItems
import app.ember.tv.data.LightNavigation
import app.ember.tv.data.InterfaceStyles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.data.AppEntry
import app.ember.tv.data.AppOrder

/** Read the latest draft on every key repeat, rather than a previous composition's index. */
internal class AppMoveDraft(initial: List<String>, private val pkg: String) {
    var order by mutableStateOf(initial.distinct()); private set
    val index: Int get() = order.indexOf(pkg)
    fun reconcile(permitted: List<String>) {
        val allowed = permitted.toSet()
        order = order.filter { it in allowed } + permitted.distinct().filter { it !in order }
    }
    fun move(columns: Int, horizontal: Int = 0, vertical: Int = 0): Boolean {
        val from = index
        val to = AppOrder.target(from, order.size, columns, horizontal, vertical)
        if (from == to) return false
        order = AppOrder.move(order, from, to)
        return true
    }
}

/** App move mode: a stable grid focus, selected app follows arrows, OK places it. */
@Composable
fun AppMoveDialog(app: AppEntry, vm: HomeViewModel, close: () -> Unit) {
    val light=vm.look.uiStyle==InterfaceStyles.LIGHT
    val permitted = if(light)vm.lightAppItems(true) else vm.appAccess.page(showHidden = true)
    val draft = remember(app.pkg, vm.profiles.profileId) { AppMoveDraft(permitted.map { it.pkg }, app.pkg) }
    val entries = permitted.associateBy { it.pkg }
    // Package/profile changes must not reintroduce uninstalled or forbidden apps while moving.
    val packages = permitted.map { it.pkg }
    LaunchedEffect(packages) { draft.reconcile(packages) }
    val order = draft.order.filter { it in entries }
    val index = order.indexOf(app.pkg)
    val grid = rememberLazyGridState()
    val horizontal = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    val focus = remember { FocusRequester() }
    val columns = if(light)6 else 4
    fun save() { vm.setAppOrder(draft.order.filter { it in entries }); vm.showNotice("App order saved."); close() }
    LaunchedEffect(index, order.size) {
        if (index < 0) close() else grid.scrollToItem(index)
    }
    TvDialog(close, width = 720.dp) {
        BackHandler(onBack = close)
        DialogHeading("Move ${app.label}")
        Text(stringResource(R.string.tx_use_the_d_pad_to_place), color = Mute, fontSize = 13.sp)
        Text(stringResource(R.string.tx_position_of, (index + 1), order.size), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Box(Modifier.fillMaxWidth().weight(1f, fill = false).focusRequester(focus)
            .onPreviewKeyEvent { event ->
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> {
                        if (event.type == KeyEventType.KeyDown) when (event.key) {
                            Key.DirectionLeft -> draft.move(columns, horizontal = -horizontal)
                            Key.DirectionRight -> draft.move(columns, horizontal = horizontal)
                            Key.DirectionUp -> draft.move(columns, vertical = -1)
                            else -> draft.move(columns, vertical = 1)
                        }
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (event.type == KeyEventType.KeyUp) save()
                        true
                    }
                    else -> false
                }
            }.semantics {
                contentDescription = "Move ${app.label}, position ${index + 1} of ${order.size}"
                onClick("Save order") { save(); true }
                customActions = listOf(
                    CustomAccessibilityAction("Move earlier") { draft.move(columns, horizontal = -1) },
                    CustomAccessibilityAction("Move later") { draft.move(columns, horizontal = 1) },
                    CustomAccessibilityAction("Move up") { draft.move(columns, vertical = -1) },
                    CustomAccessibilityAction("Move down") { draft.move(columns, vertical = 1) },
                    CustomAccessibilityAction("Cancel") { close(); true },
                )
            }.focusable()) {
            LazyVerticalGrid(columns = GridCells.Fixed(columns), state = grid,
                modifier = Modifier.heightIn(max = 320.dp), userScrollEnabled = false,
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(8.dp)) {
                items(order, key = { it }) { pkg ->
                    val item = entries.getValue(pkg)
                    val destination=LightNavigation.destination(item.pkg)
                    val bitmap = if(destination==null)rememberAppArtwork(item) else null
                    val selected = pkg == app.pkg
                    Column(Modifier.alpha(if (selected) 1f else .6f)
                        .background(if (selected) WashHi else Panel, RoundedCornerShape(10.dp))
                        .then(if (selected) Modifier.border(2.dp, Ring, RoundedCornerShape(10.dp)) else Modifier)
                        .padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
                            if(destination!=null)NavGlyph(destination,Fg)
                            else if (bitmap != null) Image(bitmap.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else ArtworkPlaceholder(item.label, Modifier.fillMaxSize())
                        }
                        Text(item.label, fontSize = 12.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Chip("Save order", maxLines = 1, onClick = ::save)
            Chip("Cancel", maxLines = 1, onClick = close)
        }
        LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
    }
}
