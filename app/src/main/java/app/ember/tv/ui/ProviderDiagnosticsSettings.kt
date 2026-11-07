package app.ember.tv.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.data.SourceDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Diagnostics stay out of browsing; export contains anonymous references and timings only. */
@Composable
internal fun ProviderDiagnosticsSettings() {
    val context=androidx.compose.ui.platform.LocalContext.current
    val scope=rememberCoroutineScope()
    val rows by SourceDiagnostics.records.collectAsState()
    var showAll by remember {mutableStateOf(false)}
    var pending by remember {mutableStateOf<String?>(null)}
    var message by remember {mutableStateOf<String?>(null)}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri->
        val text=pending;pending=null
        if(uri!=null && text!=null)scope.launch {
            try {
                withContext(Dispatchers.IO) {context.contentResolver.openOutputStream(uri,"wt")?.bufferedWriter()?.use {it.write(text)} ?: error("No output")}
                message="Diagnostics saved."
            } catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){message="Could not save diagnostics. Choose another document provider."}
        }
    }
    Section("Provider diagnostics","Recent request timings from this session. Source names, URLs, credentials and raw errors are omitted.",id="provider-diagnostics") {
        if(rows.isEmpty())Note("No completed source requests in this session yet.")
        ButtonRow {
                Chip("Export timings") {
                    pending=SourceDiagnostics.export()
                    try {save.launch("ember-provider-diagnostics.json")}
                    catch(_:ActivityNotFoundException){pending=null;message="No document picker is installed."}
                    catch(_:SecurityException){pending=null;message="The device blocked document access."}
                }
                Chip("Clear") {SourceDiagnostics.clear()}
                if(rows.size>12)Chip(if(showAll)"Show latest 12" else "Show all ${rows.size}") {showAll=!showAll}
        }
        rows.take(if(showAll)64 else 12).forEach {row->key(row.group,row.reference) {
            Focusable(onClick={},modifier=Modifier.fillMaxWidth(),focusedScale=TvTokens.Focus.None) {
                Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text("${row.group.replaceFirstChar {it.uppercase()}} · ${row.reference}",fontSize=13.sp)
                    Text(listOfNotNull(row.durationMillis?.let {"$it ms"},
                        if(row.success)if(row.empty)"No results" else "Success" else row.failure?.name?.lowercase()?.replace('_',' ') ?: "Unavailable",
                        "Cached".takeIf {row.cached}).joinToString(" · "),fontSize=12.sp)
                }
            }
        }}
        message?.let {Note(it)}
    }
}
