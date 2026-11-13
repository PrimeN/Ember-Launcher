package app.ember.tv.ui

import androidx.compose.ui.res.stringResource
import app.ember.tv.R

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.data.StreamOption
import app.ember.tv.mediaserver.*
import kotlinx.coroutines.*

/** Ask only for qualities permitted by this account/server. Never silently switch versions. */
@Composable
internal fun ServerQualityDialog(source: StreamOption, onPlay: (StreamOption) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val servers = remember { MediaServers(context) }
    val transport = source.serverPlayback ?: return
    val scope = rememberCoroutineScope()
    var qualities by remember(source) { mutableStateOf(listOf(ServerQuality.ORIGINAL)) }
    var loading by remember(source) { mutableStateOf(true) }
    var note by remember(source) { mutableStateOf<String?>(null) }
    var request by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(source) {
        try { qualities = servers.qualities(transport.ref.value, transport.sourceId) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { note = "Quality options are unavailable. Original is still available." }
        finally { loading = false }
    }
    DisposableEffect(Unit) { onDispose { request?.cancel() } }
    MediaDialog("Playback quality", onDismiss) {
        Text("${source.addonName} · ${source.quality} ${source.codec} ${source.audio}".trim(), color = Mute, fontSize = 13.sp)
        Text(stringResource(R.string.tx_original_preserves_the_file_lower_qualities), color = Mute, fontSize = 13.sp)
        if (loading) Text(stringResource(R.string.tx_contacting_server), fontSize = 14.sp)
        else qualities.forEach { quality -> Chip(quality.label) {
            request?.cancel()
            request = scope.launch {
                loading = true; note = null
                try {
                    val resolved = servers.playback(transport.ref.value, transport.sourceId, quality)
                    ensureActive(); onDismiss(); onPlay(resolved)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { note = "This quality could not be started. Retry, choose Original, or select another version." }
                finally { loading = false }
            }
        } }
        note?.let { Text(it, color = Mute, fontSize = 13.sp) }
        Chip("Cancel", onClick = onDismiss)
    }
}
