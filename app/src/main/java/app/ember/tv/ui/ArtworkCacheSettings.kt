package app.ember.tv.ui

import android.text.format.Formatter
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.Text
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Clear only Coil image caches and derived app bitmaps, never connection/preferences stores. */
@Composable internal fun ArtworkCacheSettings() {
    val context=LocalContext.current
    val loader=context.imageLoader
    val scope=rememberCoroutineScope()
    var bytes by remember {mutableStateOf<Long?>(null)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(loader){bytes=withContext(Dispatchers.IO){loader.diskCache?.size ?: 0}}
    Section("Artwork cache","Downloaded images reload as needed after clearing.",id="image-cache") {
        Text(bytes?.let {"Stored images: ${Formatter.formatShortFileSize(context,it)}"} ?: "Reading image cache…",color=Mute)
        Chip(if(busy)"Clearing…" else "Clear artwork cache") {
            if(!busy) {
                busy=true;error=null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {loader.diskCache?.clear();loader.memoryCache?.clear();AppArtwork.clear()}
                        bytes=withContext(Dispatchers.IO){loader.diskCache?.size ?: 0}
                    } catch(e:kotlinx.coroutines.CancellationException){throw e}
                    catch(_:Exception){error="Could not clear the image cache. Try again."}
                    finally {busy=false}
                }
            }
        }
        error?.let {Text(it,color=Mute)}
    }
}
