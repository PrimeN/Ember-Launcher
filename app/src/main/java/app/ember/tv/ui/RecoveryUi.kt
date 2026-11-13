package app.ember.tv.ui

import androidx.compose.ui.res.stringResource
import app.ember.tv.R

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.data.SourceStatus
import java.text.DateFormat
import java.util.Date

@Composable
fun RecoveryPanel(title: String, message: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 18.sp, modifier = Modifier.semantics { heading() })
        Text(message, fontSize = 13.sp, color = Mute)
        if (action != null) Chip(action, onClick = onAction)
    }
}

@Composable
fun SourceStatusRows(statuses: Collection<SourceStatus>, retry: (String) -> Unit) {
    statuses.forEach { status ->
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val freshness = status.lastSuccess?.let { "Last refreshed ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}." }
            Text(status.name + when {
                status.loading -> " · Refreshing…"
                status.failed && status.failure == app.ember.tv.data.SourceFailure.ACCESS -> " · Reconnect this account or check library permissions." + if(status.cached) " Showing saved content." else ""
                status.failed && status.failure == app.ember.tv.data.SourceFailure.OFFLINE -> " · Offline. Check the network and wake the server." + if(status.cached) " Showing saved content." else ""
                status.failed && status.failure == app.ember.tv.data.SourceFailure.TIMEOUT -> " · Timed out. Retry when the source responds."
                status.failed && status.failure == app.ember.tv.data.SourceFailure.QUOTA -> " · Provider request limit reached. Wait before retrying."
                status.failed && status.cached -> " · Unavailable; showing saved content."
                status.failed -> " · Source unavailable. Retry this source."
                status.empty -> " · Connected; no titles returned."
                status.cached -> " · Saved content"
                status.lastSuccess == null -> " · Not refreshed yet"
                else -> " · Up to date"
            }, fontSize = 13.sp, color = Mute)
            freshness?.let { Text(it, fontSize = 12.sp, color = Mute) }
            if ((status.failed || status.lastSuccess == null) && !status.loading) Chip("Retry ${status.name}") { retry(status.id) }
        }
    }
}

/** A large set of failed providers must not consume the channel or title viewport. */
@Composable
fun SourceStatusButton(label: String, statuses: Collection<SourceStatus>, retry: (String) -> Unit) {
    if (statuses.isEmpty()) return
    var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val failed = statuses.filter { it.failed }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (failed.isNotEmpty()) Text(stringResource(R.string.tx_unavailable) + failed.take(3).joinToString { it.name } +
            if (failed.size > 3) " and ${failed.size - 3} more" else "", color = Mute, fontSize = 13.sp, maxLines = 2)
        Chip(label + when {
            failed.isNotEmpty() -> " · ${failed.size} unavailable"
            statuses.any { it.loading } -> " · Refreshing…"
            statuses.any { it.lastSuccess == null } -> " · Not refreshed yet"
            else -> " · Up to date"
        }) { open = true }
    }
    if (open) TvDialog({open=false},width=640.dp,height=520.dp,scrollContent=true) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { first.requestFocus() }
        DialogHeading(label)
        Chip("Done", modifier = Modifier.focusRequester(first)) { open = false }
        SourceStatusRows(statuses) { id -> first.requestFocus(); retry(id) }

    }
}

/** Stable, silent loading shapes: they occupy space but never enter the remote focus chain. */
/** A slow pulse for loading placeholders (#184); steady with Reduce motion. Read it in a draw phase, not composition. */
@Composable
internal fun loadingPulse(): androidx.compose.runtime.State<Float> {
    if (LocalReduceMotion.current || LocalRenderOptions.current.lowPower) return remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    return androidx.compose.animation.core.rememberInfiniteTransition(label = "loading").animateFloat(initialValue = .55f, targetValue = 1f, animationSpec =
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "loadingPulse")
}

/** The placeholder for a Hero banner or Cinematic carousel widget (#184): one panel of the height the widget will have. */
@Composable
internal fun LoadingPanel(height: androidx.compose.ui.unit.Dp) {
    val pulse = loadingPulse()
    Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp).height(height).graphicsLayer { alpha = pulse.value }
        .background(PanelHi, RoundedCornerShape(TvTokens.Shape.Panel.dp)).clearAndSetSemantics { contentDescription = "Loading titles" })
}

@Composable
fun LoadingCards(wide:Boolean=true) {
    val pulse = loadingPulse()
    BoxWithConstraints(Modifier.fillMaxWidth().graphicsLayer { alpha = pulse.value }.clearAndSetSemantics { contentDescription = "Loading titles" }) {
        val count=LocalRenderOptions.current.count(wide)
        val width=CardSizes.width(maxWidth,count,14.dp,4.dp)
        Row(horizontalArrangement=Arrangement.spacedBy(14.dp),modifier=Modifier.padding(vertical=12.dp)) {
            repeat(count.toInt()) {Box(Modifier.width(width).height(if(wide)width*9f/16f else width*1.5f).background(PanelHi,RoundedCornerShape(12.dp)))}
        }
    }
}

@Composable
fun HomeRecovery(vm: HomeViewModel) {
    val state = vm.catalogs
    if (vm.homeCatalogsWanted && state.loading && vm.rows.isEmpty() && vm.homeBlocks.none {it is HomeViewModel.HomeBlock.Loading}) LoadingCards(vm.look.modernCards!="poster")
    if (vm.homeCatalogsWanted && !state.loading && vm.rows.isEmpty()) RecoveryPanel(
        if (vm.addons.isEmpty()) "Add your first source" else if (state.health.entries.values.any { it.failed }) "Sources unavailable" else "No catalogs on Home",
        if (vm.addons.isEmpty()) "Connect an addon to browse movies and shows. Your apps are ready to use."
        else if (state.health.entries.values.any { it.failed }) "Saved content stays available. Source settings shows which source failed and retries it."
        else "Your sources may be empty or their Home catalogs switched off. Choose catalogs in Home settings.",
        "Source settings", vm::openSourceSettings)
    if(!vm.profiles.profile.kids && vm.addons.isEmpty() && !vm.plexConnected && vm.serverAccounts.isEmpty() && !vm.look.setupChecklistHidden) {
        ButtonRow {
            Chip("Optional setup checklist") {vm.openSettingsPage("FEATURES")}
            Chip("Dismiss") {vm.look.hideSetupChecklist(true)}
        }
    }
    // Source status (addons, trackers, media servers) lives in Settings, on each source's page.
}

/** List rows (a picture and two lines) while a list loads, e.g. a season's episodes (#108). */
@Composable
fun LoadingListRows(count: Int = 4) {
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "Loading" }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(count) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(128.dp).height(72.dp).background(PanelHi, RoundedCornerShape(10.dp)))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width(180.dp).height(12.dp).background(PanelHi, RoundedCornerShape(4.dp)))
                    Box(Modifier.width(120.dp).height(10.dp).background(PanelHi, RoundedCornerShape(4.dp)))
                }
            }
        }
    }
}
