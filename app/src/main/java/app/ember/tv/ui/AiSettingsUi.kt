package app.ember.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.data.AiProvider
import app.ember.tv.data.AiSettings
import app.ember.tv.data.ApiKeys
import app.ember.tv.discover.AiChat
import kotlinx.coroutines.launch

/**
 * #438 Settings › Discover › AI: which AI service Ember uses (several have free tiers), its key,
 * its model (from the service's own list) and, for your own server, its address.
 */
@Composable
internal fun AiServiceSettings(vm: HomeViewModel) {
    @Suppress("UNUSED_VARIABLE") val rev = vm.keysRev + AiSettings.revision
    val p = AiSettings.provider
    var picking by remember { mutableStateOf(false) }
    var models by remember(p) { mutableStateOf<List<String>?>(null) }
    var modelNote by remember(p) { mutableStateOf<String?>(null) }
    var modelMenu by remember(p) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingValueRow("AI service", p.label + if (p.free != null) " · free tier" else "",
            note = if (AiSettings.ready(p)) "Ready" else if (p == AiProvider.LOCAL) "Add your server's address and pick a model" else "Add its key below") { picking = true }
        Text((p.free?.let { "$it " } ?: "") + p.keyHelp, color = Mute, fontSize = 13.sp, modifier = Modifier.width(760.dp))
        if (p == AiProvider.LOCAL) {
            var url by remember { mutableStateOf(AiSettings.localUrl) }
            TvTextField(url, { url = it.take(200) }, placeholder = "http://192.168.1.20:11434/v1", onSubmit = { AiSettings.setLocalUrl(url) })
            Chip("Save address") { AiSettings.setLocalUrl(url) }
        }
        ApiKeys.SERVICES.flatMap { it.fields }.firstOrNull { it.id == p.keyId }?.let { KeyField(vm, it) }
        SettingValueRow("Model", AiSettings.model(p).ifBlank { "Choose one" },
            note = modelNote ?: "Pick from the service's own list", unavailable = if (!AiSettings.ready(p) && p != AiProvider.LOCAL) "Add the key first." else null) {
            modelNote = "Loading the list…"
            scope.launch {
                runCatching { AiChat.models(p) }
                    .onSuccess { list -> models = list; modelNote = if (list.isEmpty()) "The service listed no models." else null; modelMenu = list.isNotEmpty() }
                    .onFailure { modelNote = "Couldn't get the list. Check the key${if (p == AiProvider.LOCAL) " and address" else ""}." }
            }
        }
    }
    if (picking) OptionMenu("AI service", AiProvider.entries.map { s ->
        MenuOption(s.label + (if (s.free != null) " · free tier" else "") + (if (AiSettings.ready(s)) " · ready" else ""), selected = s == p) { AiSettings.choose(s) }
    }) { picking = false }
    if (modelMenu) OptionMenu("Model", models.orEmpty().take(80).map { m ->
        MenuOption(m + if (p == AiProvider.OPENROUTER && m.endsWith(":free")) " · free" else "", selected = m == AiSettings.model(p)) { AiSettings.setModel(p, m) }
    }) { modelMenu = false }
}

