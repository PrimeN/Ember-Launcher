package app.ember.tv.ui

import androidx.compose.ui.res.stringResource
import app.ember.tv.R

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import app.ember.tv.discover.*
import kotlinx.coroutines.*

@Composable
internal fun AiDiscoveryDialog(repository: ExploreRepository, region: String, onClose: () -> Unit, onReview: (ExploreQuery) -> Unit) {
    var prompt by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<AiDiscoverySuggestion?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableLongStateOf(0L) }
    fun cancelRequest() { generation++; job?.cancel(); busy = false }
    val scope = rememberCoroutineScope()
    val client = remember { AiDiscovery() }
    val first = remember { FocusRequester() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                cancelRequest()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); cancelRequest() }
    }
    TvDialog(onClose, width = 610.dp, resizeForKeyboard = true, scrollContent = true) {
        DialogHeading("Describe what to watch")
        Text(stringResource(R.string.tx_mistral_suggests_filters_you_review_before), color = Mute, fontSize = 13.sp)
        TvTextField(prompt, {
            cancelRequest(); prompt = it.take(1000); result = null; error = null
        }, placeholder = "e.g. Animated movies from 2010 onwards, rated 7+", focusRequester = first)
        if (busy) Text(stringResource(R.string.tx_preparing_suggested_filters), color = Mute, fontSize = 13.sp)
        error?.let { Text(it, color = Mute, fontSize = 13.sp) }
        result?.let { suggestion ->
            Text((listOf(suggestion.query.kind.label, suggestion.query.sort.label) + suggestion.query.summary()).joinToString(" · "), fontSize = 14.sp)
            suggestion.warnings.forEach { Text(it, color = Mute, fontSize = 13.sp) }
            Chip("Review filters") { onReview(suggestion.query) }
        }
        ButtonRow {
            Chip(when { busy -> "Preparing…"; result != null -> "Suggest again"; else -> "Suggest filters" }) {
                if (busy || prompt.trim().length < 3) return@Chip
                busy = true; error = null; result = null
                val request = ++generation
                job = scope.launch {
                    try { val suggestion = client.suggest(prompt, region, repository); if (request == generation) result = suggestion }
                    catch (_: TimeoutCancellationException) { if (request == generation) error = "The request took too long. Try again or use manual filters." }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { if (request == generation) error = "Could not prepare filters. Check your keys and provider plan, or use manual filters." }
                    finally { if (request == generation) busy = false }
                }
            }
            Chip(if (busy) "Cancel request" else "Close") {
                if (busy) cancelRequest() else onClose()
            }
        }
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    }
}
