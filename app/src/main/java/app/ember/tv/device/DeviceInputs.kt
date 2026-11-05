package app.ember.tv.device

import android.content.Context
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.content.Intent
import android.widget.Toast
import app.ember.tv.R

internal data class DeviceInput(val id: String, val label: String, val connected: Boolean?, val type: Int = TvInputInfo.TYPE_OTHER)
internal object DeviceInputs {
    fun eligible(passthrough: Boolean, hidden: Boolean) = passthrough && !hidden
    fun list(context: Context, includeTuners: Boolean = false): List<DeviceInput> = runCatching {
        val manager = context.getSystemService(TvInputManager::class.java) ?: return emptyList()
        manager.tvInputList.mapNotNull { input -> runCatching {
            if (input.isHidden(context) || (!input.isPassthroughInput && !(includeTuners && input.type == TvInputInfo.TYPE_TUNER))) return@runCatching null
            val fallback = if (input.type == TvInputInfo.TYPE_HDMI) "HDMI" else context.getString(R.string.device_input)
            val label = app.ember.tv.data.firstUsableMetadata(
                { input.loadCustomLabel(context)?.toString()?.trim()?.takeIf(String::isNotEmpty) },
                { input.loadLabel(context)?.toString()?.trim()?.takeIf(String::isNotEmpty) }) ?: fallback
            val connected = when (runCatching { manager.getInputState(input.id) }.getOrNull()) {
                TvInputManager.INPUT_STATE_CONNECTED -> true
                TvInputManager.INPUT_STATE_DISCONNECTED -> false
                else -> null
            }
            DeviceInput(input.id, label, connected, input.type)
        }.getOrNull() }.distinctBy { it.id }.sortedBy { it.label.lowercase(java.util.Locale.ROOT) }
    }.getOrDefault(emptyList())

    fun open(context: Context, id: String, includeTuners: Boolean = false) {
        // Hidden/deleted inputs must not remain usable through a stale quick-panel snapshot.
        val input = runCatching { context.getSystemService(TvInputManager::class.java)?.getTvInputInfo(id) }.getOrNull()
        val visible = input != null && runCatching { !input.isHidden(context) && (input.isPassthroughInput || includeTuners && input.type == TvInputInfo.TYPE_TUNER) }.getOrDefault(false)
        if (!visible || input == null) {
            Toast.makeText(context, R.string.device_input_unavailable, Toast.LENGTH_LONG).show(); return
        }
        val uri = if (input.isPassthroughInput) TvContract.buildChannelUriForPassthroughInput(id) else TvContract.buildChannelsUriForInput(id)
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
            Toast.makeText(context, R.string.device_input_unavailable, Toast.LENGTH_LONG).show()
        }
    }
}
