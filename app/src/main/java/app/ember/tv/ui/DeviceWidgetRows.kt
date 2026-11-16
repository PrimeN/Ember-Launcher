package app.ember.tv.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.data.AppEntry
import app.ember.tv.forgetRecentApp
import app.ember.tv.light.LightMedia
import app.ember.tv.light.LightMediaProvider
import app.ember.tv.wantsAppsContinue
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate

/** Reads other apps' Continue watching while a widget on Home shows it, and follows its changes. */
@Composable
internal fun AppsContinueLoader(vm: HomeViewModel, refresh: Int) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val wanted = vm.wantsAppsContinue()
    val profile = vm.profiles.profileId
    val allowed = vm.appAccess.page(false).map { it.pkg }.toSet()
    val personal = vm.personalHomes.current
    LaunchedEffect(wanted, profile, allowed, personal, refresh, lifecycle) {
        if (!wanted) { vm.appsContinue = emptyList(); return@LaunchedEffect }
        vm.appsContinueAllowed = LightMediaProvider.granted(context)
        if (!vm.appsContinueAllowed) { vm.appsContinue = emptyList(); return@LaunchedEffect }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            callbackFlow {
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) { LightMediaProvider.invalidate(); trySend(Unit) }
                }
                runCatching { context.contentResolver.registerContentObserver(LightMediaProvider.watchUri, true, observer) }
                trySend(Unit)
                awaitClose { runCatching { context.contentResolver.unregisterContentObserver(observer) } }
            }.conflate().collectLatest {
                vm.appsContinue = runCatching { LightMediaProvider.read(context, allowed, watch = true, previews = false, personal = personal) }
                    .getOrDefault(emptyList()).filter { it.continuing }
            }
        }
    }
}

/** "Recently used apps": app banners, newest first. */
@Composable
internal fun RecentAppsRow(vm: HomeViewModel, apps: List<AppEntry>, onLaunch: (AppEntry) -> Unit, onAppOptions: (AppEntry) -> Unit, onFocus: () -> Unit) {
    var options by remember { mutableStateOf<AppEntry?>(null) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(6.dp)) {
        items(apps, key = { "recent:" + it.pkg }) { app ->
            AppBanner(app, onClick = { onLaunch(app) }, onLongClick = { options = app }, onFocus = onFocus)
        }
    }
    options?.let { app ->
        OptionMenu(app.label, listOf(
            MenuOption("Remove from Recently used") { vm.forgetRecentApp(app.pkg) },
            MenuOption("App options") { onAppOptions(app) },
        )) { options = null }
    }
}

/** "Continue watching in your apps": each programme opens in its own app. */
@Composable
internal fun AppMediaRow(vm: HomeViewModel, items: List<LightMedia>, onLaunch: (AppEntry) -> Unit, onFocus: () -> Unit) {
    var options by remember { mutableStateOf<LightMedia?>(null) }
    val apps = vm.appAccess.page(false).associateBy { it.pkg }
    fun open(item: LightMedia) {
        val app = apps[item.pkg]
        val intent = LightMediaProvider.episodeIntent(item)
        if (app != null && intent != null) onLaunch(app.copy(intent = intent))
        else vm.showNotice("This app no longer provides a playable link.")
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(6.dp)) {
        items(items, key = { "appmedia:" + it.pkg + ":" + it.id }) { item ->
            AppMediaCard(item, apps[item.pkg]?.label.orEmpty(), onClick = { open(item) }, onLongClick = { options = item }, onFocus = onFocus)
        }
    }
    options?.let { item ->
        OptionMenu(item.title, buildList {
            add(MenuOption("Resume in ${apps[item.pkg]?.label ?: "its app"}") { open(item) })
            add(MenuOption("Remove from this row") { vm.personalHomes.dismiss(item.id, LightMediaProvider.engagementToken(item)) })
        }) { options = null }
    }
}

@Composable
private fun AppMediaCard(item: LightMedia, appName: String, onClick: () -> Unit, onLongClick: () -> Unit, onFocus: () -> Unit) {
    Column(Modifier.width(260.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Focusable(onClick = onClick, onLongClick = onLongClick, card = true, container = PanelHi,
            modifier = Modifier.fillMaxWidth().height(146.dp)
                .semantics { contentDescription = listOf(item.title, appName).filter(String::isNotBlank).joinToString(", ") }
                .onFocusChanged { if (it.isFocused || it.hasFocus) onFocus() }) {
            Box(Modifier.fillMaxSize()) {
                val art = LightMediaProvider.validArtwork(item.artwork)
                if (art != null) coil.compose.AsyncImage(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else ArtworkPlaceholder(item.title, Modifier.fillMaxSize())
                item.progress?.takeIf { it > 0f }?.let { p ->
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66000000))) {
                        Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).fillMaxHeight().background(androidx.tv.material3.MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
                    }
                }
            }
        }
        Text(item.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(listOf(appName, item.description).filter(String::isNotBlank).joinToString(" · "), fontSize = 12.sp, color = Mute, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
