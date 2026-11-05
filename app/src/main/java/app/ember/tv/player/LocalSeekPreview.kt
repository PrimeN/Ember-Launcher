package app.ember.tv.player

import androidx.core.net.toUri

import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

/** One small frame on demand; no movie download, disk index, full-resolution bitmap or frame cache. */
@Composable
internal fun LocalSeekPreview(uri:String,at:Long) {
    val context=LocalContext.current
    val frame by produceState<android.graphics.Bitmap?>(null,uri,at/1000) {
        value=null
        delay(180)
        value=withContext(Dispatchers.IO) {
            runCatching {val r=MediaMetadataRetriever();try {r.setDataSource(context,uri.toUri());r.getScaledFrameAtTime(at*1000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,320,180)} finally {r.release()}}.getOrNull()
        }
    }
    frame?.let {Image(it.asImageBitmap(),null,Modifier.size(240.dp,135.dp).background(Color.Black))}
}
