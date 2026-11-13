package app.ember.tv.ui

import androidx.compose.ui.res.stringResource
import app.ember.tv.R

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.ember.tv.data.*
import kotlinx.coroutines.launch

internal val CACHE_KEYS = listOf("cache-size", "image-duration", "metadata-duration", "catalog-duration", "network-duration")

@Composable
internal fun CacheManagementSettings(part: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config = CacheManagement.configuration
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var usage by remember { mutableStateOf<CacheManagement.Usage?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    fun apply(kind: CacheKind, amount: Int) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { CacheManagement.update(kind,amount); custom = false }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { error = "Could not update cache settings. Try again." }
            finally { busy = false }
        }
    }
    if (part == "cache-size") {
        LaunchedEffect(config) {
            try { usage = CacheManagement.usage(context); error = null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { error = "Could not read cache size. Try again." }
        }
        Section(stringResource(R.string.st_current_cache_size), stringResource(R.string.st_cache_storage_only_saved_lists_connections), id = part) {
            Text(usage?.let { Formatter.formatShortFileSize(context,it.total) } ?: "Reading cache size…", color = Fg)
            usage?.categories?.forEach { (kind, bytes) -> Text("${kind.label}: ${Formatter.formatShortFileSize(context,bytes)}", color = Mute) }
            ButtonRow {
                Chip(if (busy) stringResource(R.string.st_clearing) else stringResource(R.string.st_clear_cache_data), maxLines = 1) { if (!busy) confirm = true }
                Chip(stringResource(R.string.st_refresh_size), maxLines = 1) {
                    if (!busy) scope.launch {
                        try { usage = CacheManagement.usage(context); error = null }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { error = "Could not read cache size. Try again." }
                    }
                }
            }
            error?.let { Text(it,color=Mute) }
        }
        if (confirm) MediaDialog("Clear Cache Data?",{if(!busy)confirm=false}) {
            Text(stringResource(R.string.tx_downloaded_images_cached_metadata_catalogs_an),color=Mute)
            ButtonRow {
                Chip(if(busy)stringResource(R.string.st_clearing) else stringResource(R.string.st_clear_cache_data),maxLines=1) {
                    if(!busy) {
                        busy=true;error=null
                        scope.launch {
                            try { CacheManagement.clear(); usage=CacheManagement.usage(context); confirm=false }
                            catch(e:kotlinx.coroutines.CancellationException){throw e}
                            catch(_:Exception){error="Some cached data could not be cleared. Try again.";confirm=false}
                            finally{busy=false}
                        }
                    }
                }
                Chip(stringResource(R.string.ui_cancel),maxLines=1){if(!busy)confirm=false}
            }
        }
        return
    }
    val kind = when(part) {
        "image-duration" -> CacheKind.IMAGES
        "metadata-duration" -> CacheKind.METADATA
        "catalog-duration" -> CacheKind.CATALOGS
        else -> CacheKind.NETWORK
    }
    val days = kind == CacheKind.IMAGES
    val value = if(days)config.imageDays else (config.millis(kind)/3_600_000).toInt()
    val maximum = if(days)365 else 720
    val options = if(days)listOf(1,7,14,30,90) else listOf(1,6,12,24,72)
    val title = when(kind) {
        CacheKind.IMAGES -> "Image cache duration (days)"
        CacheKind.METADATA -> "Metadata cache duration (hours)"
        CacheKind.CATALOGS -> "Catalog cache duration (hours)"
        CacheKind.NETWORK -> "Network cache duration (hours)"
    }
    Section(title, if(kind==CacheKind.NETWORK) stringResource(R.string.st_maximum_reuse_time_providers_can_require) else stringResource(R.string.st_changing_this_duration_clears_the_correspondi), id=part) {
        ButtonRow {
            options.forEach { amount -> Chip(amount.toString(),selected=value==amount,maxLines=1){apply(kind,amount)} }
            Chip(if(value in options)stringResource(R.string.st_custom) else value.toString(),selected=value !in options,maxLines=1){if(!busy){draft=value.toString();custom=true}}
        }
        error?.let { Text(it,color=Mute) }
    }
    if(custom)MediaDialog(title,{if(!busy)custom=false},resizeForKeyboard=true) {
        Text(stringResource(R.string.tx_enter_a_value_from_1_to, maximum),color=Mute)
        TextInput(draft,{draft=it.take(6)},if(days)"Days" else "Hours",width=240,numeric=true)
        ButtonRow {
            Chip(if(busy)stringResource(R.string.st_saving) else stringResource(R.string.ui_save),maxLines=1){
                val amount=draft.toIntOrNull()
                if(amount!=null && amount in 1..maximum)apply(kind,amount) else error="Enter a number from 1 to $maximum."
            }
            Chip(stringResource(R.string.ui_cancel),maxLines=1){if(!busy)custom=false}
        }
        error?.let{Text(it,color=Mute)}
    }
}
