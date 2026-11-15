package app.ember.tv.player

import androidx.lifecycle.lifecycleScope
import app.ember.tv.ui.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import android.widget.FrameLayout
import kotlinx.coroutines.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun PlayerActivity.showBookmarks() {
    if(live || trailer)return
    val key=resumeKey;val visit=playbackVisit
    val store=PlaybackBookmarks(this,localActivityScope)
    lifecycleScope.launch {
        val list=withContext(Dispatchers.IO){store.read(key)}
        if(visit!=playbackVisit || resumeKey!=key)return@launch
        showPlayerActions("Bookmarks",listOf(PlayerDialogAction("Save current position") {
            if(visit==playbackVisit && resumeKey==key)player?.currentPosition?.let {at->bookmarkName("Bookmark name",clock(at)) {label->
                lifecycleScope.launch {val saved=withContext(Dispatchers.IO){store.add(key,at,label)};if(!saved)android.widget.Toast.makeText(this@showBookmarks,"Could not save bookmark. Limit: 50 per title, 512 titles.",android.widget.Toast.LENGTH_LONG).show();if(visit==playbackVisit)showBookmarks()}
            }}
        })+list.map {bookmark->PlayerDialogAction("${clock(bookmark.positionMs)} · ${bookmark.name}") {
            showPlayerActions(bookmark.name,listOf(
                PlayerDialogAction("Play from ${clock(bookmark.positionMs)}") {if(visit==playbackVisit)player?.seekTo(bookmark.positionMs)},
                PlayerDialogAction("Rename") {bookmarkName("Rename bookmark",bookmark.name) {label->lifecycleScope.launch {val saved=withContext(Dispatchers.IO){store.rename(key,bookmark.id,label)};if(!saved)bookmarkWriteFailed();if(visit==playbackVisit)showBookmarks()}}},
                PlayerDialogAction("Remove",destructive=true) {lifecycleScope.launch {val saved=withContext(Dispatchers.IO){store.remove(key,bookmark.id)};if(!saved)bookmarkWriteFailed();if(visit==playbackVisit)showBookmarks()}},
            ))
        }},message=if(list.isEmpty())"Save a scene to return to it later." else null)
    }
}

/** Reuses the existing TV text field and dialog; the main player layout stays unchanged. */
internal fun PlayerActivity.bookmarkName(title:String,initial:String,saved:(String)->Unit) {
    actionDialog?.dismiss()
    val host=ComposeView(this)
    val dialog=PlayerActionDialog(host,currentFocus,{})
    actionDialog=dialog
    addContentView(host,FrameLayout.LayoutParams(1,1))
    host.setContent {
        val render=rememberDeviceRenderOptions(prefs.contentDensity,prefs.navigationGlass,prefs.artworkQuality,
            prefs.devicePerformance,prefs.customPerformance).copy(glassMethod=prefs.navigationGlassMethod)
        CompositionLocalProvider(LocalNight provides true,LocalRenderOptions provides render,
            LocalReduceMotion provides rememberReduceMotion()) {EmberTheme(prefs.accent,playerLayoutStyle) {
        var text by remember {mutableStateOf(initial)}
        TvDialog({dialog.dismiss()},width=400.dp,resizeForKeyboard=true) {
            val view=androidx.compose.ui.platform.LocalView.current
            DisposableEffect(view) {dialog.target=view;onDispose {dialog.target=null}}
            DialogHeading(title)
            TvTextField(text,{text=it.take(80)},Modifier.fillMaxWidth(),placeholder="Name")
            ButtonRow {Chip("Save") {val value=PlaybackBookmarks.label(text);if(value.isNotEmpty()){dialog.dismiss();saved(value)}};Chip("Cancel") {dialog.dismiss()}}
        }
    }}}
}

private fun PlayerActivity.bookmarkWriteFailed() {
    android.widget.Toast.makeText(this,"Could not update bookmark. Please try again.",android.widget.Toast.LENGTH_LONG).show()
}
