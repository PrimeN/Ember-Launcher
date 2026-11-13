package app.ember.tv.player

import androidx.compose.ui.res.stringResource
import app.ember.tv.R

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.ember.tv.data.Prefs
import app.ember.tv.ui.EmberTheme
import app.ember.tv.ui.Chip
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

/** Uses Android's storage grants; no broad video/storage permission is requested. */
class MediaFilesActivity:ComponentActivity() {
    private lateinit var openedProfile:String
    private var preparing by mutableStateOf(false)
    private fun openFiles(uris:List<android.net.Uri>) {
        if(preparing || uris.isEmpty())return
        preparing=true
        lifecycleScope.launch {
            try {
                val items=withContext(Dispatchers.IO) {uris.take(200).map {
                    ensureActive();MediaFiles.retain(this@MediaFilesActivity,it)
                    QueueMedia(it.toString(),MediaFiles.name(this@MediaFilesActivity,it))
                }}
                ensureActive()
                if(isFinishing || Prefs(this@MediaFilesActivity).currentProfile!=openedProfile)return@launch
                MediaFiles.launch(this@MediaFilesActivity,items)
            } catch(e:CancellationException) {throw e}
              catch(_:Exception) {Toast.makeText(this@MediaFilesActivity,"Could not open the selected files.",Toast.LENGTH_LONG).show()}
            finally {preparing=false}
        }
    }
    override fun onResume() {
        super.onResume()
        if(::openedProfile.isInitialized && Prefs(this).currentProfile!=openedProfile)finish()
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        openedProfile=Prefs(this).currentProfile
        setContent {EmberTheme(Prefs(this).accent,Prefs(this).uiStyle) {
            val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {uris ->
                openFiles(uris)
            }
            CompositionLocalProvider(androidx.tv.material3.LocalContentColor provides app.ember.tv.ui.Fg) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState()).padding(40.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                Text(stringResource(R.string.tx_open_media),fontSize=26.sp)
                Text(stringResource(R.string.tx_play_files_from_this_device_a))
                Chip(if(preparing) "Preparing files…" else "Choose files") {
                    if(!preparing)runCatching {picker.launch(arrayOf("video/*","audio/*","application/octet-stream"))}.onFailure {Toast.makeText(this@MediaFilesActivity,"No file picker is installed on this TV.",Toast.LENGTH_LONG).show()}
                }
                NetworkFilesPanel(this@MediaFilesActivity)
                Chip("Back"){finish()}
            }
            }
        }}
    }
}
