package app.ember.tv

import androidx.core.net.toUri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight

import app.ember.tv.data.launchInstalledApp
import app.ember.tv.ui.guardOpeningPress
import app.ember.tv.ui.sceneEnter
import app.ember.tv.ui.MediaDialog
import app.ember.tv.ui.TvTokens

import app.ember.tv.ui.detailsFocusGroup
import app.ember.tv.ui.MenuActionRow
import app.ember.tv.ui.CompactActionMenu
import app.ember.tv.ui.AppManagement
import app.ember.tv.data.mediaKey
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import app.ember.tv.R

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.tv.material3.Text
import app.ember.tv.data.LightNavigation
import app.ember.tv.data.AppEntry
import app.ember.tv.data.LocalLibrary
import app.ember.tv.data.Meta
import app.ember.tv.data.NavTabs
import app.ember.tv.data.Prefs
import app.ember.tv.player.PlayRequest
import app.ember.tv.player.PlayerApp
import app.ember.tv.streaming.Offer
import app.ember.tv.ui.AppsScreen
import app.ember.tv.ui.Chip
import app.ember.tv.ui.DetailsScreen
import app.ember.tv.ui.initialNavigationFocus
import app.ember.tv.ui.startupInput
import app.ember.tv.ui.EmberTheme
import app.ember.tv.ui.Ink
import app.ember.tv.ui.Mute
import app.ember.tv.ui.Panel
import app.ember.tv.ui.PanelHi
import app.ember.tv.ui.SettingsScreen
import app.ember.tv.ui.DiscoverScreen
import app.ember.tv.ui.LibraryScreen
import app.ember.tv.ui.SearchScreen
import app.ember.tv.ui.WatchlistScreen
import androidx.lifecycle.compose.currentStateAsState
import app.ember.tv.ui.sceneContent
import kotlinx.coroutines.delay

/** Resume or Start over, for a title with a resume point. */
@Composable
internal fun ResumeDialog(label: String, onResume: () -> Unit, onStartOver: () -> Unit, onCancel: () -> Unit) {
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val heading = stringResource(R.string.ui_continue_watching)
    val startOver = stringResource(R.string.ui_start_over)
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Screen width in this app's scaled dp (Interface size changes density), not the system's dp.
    val screenPx = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width
    val maxWidth = (with(density) { screenPx.toDp() } - 48.dp).coerceAtLeast(1.dp)
    val width = with(density) { maxOf(
        measurer.measure(heading,androidx.compose.ui.text.TextStyle(fontSize=18.sp),softWrap=false).size.width.toDp() + 40.dp,
        measurer.measure("▶ $label",androidx.compose.ui.text.TextStyle(fontSize=TvTokens.Type.Label.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold),softWrap=false).size.width.toDp() + 98.dp,
        measurer.measure(startOver,androidx.compose.ui.text.TextStyle(fontSize=TvTokens.Type.Label.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.Medium),softWrap=false).size.width.toDp() + 76.dp,
    ) }.coerceIn(minOf(220.dp,maxWidth),maxWidth)
    Dialog(onDismissRequest = onCancel, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
        app.ember.tv.ui.DialogMotion()
        Column(
            modifier = Modifier
                .guardOpeningPress().width(width)
                .background(Panel, RoundedCornerShape(20.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(heading, fontSize = 18.sp, maxLines=1, softWrap=false, overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Chip("▶ $label", selected = true, modifier = Modifier.fillMaxWidth().focusRequester(first), maxLines=1, onClick = onResume)
            Chip(stringResource(R.string.ui_start_over), modifier = Modifier.fillMaxWidth(), maxLines=1, onClick = onStartOver)
        }
    }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { first.requestFocus() }
    }
}
@Composable
internal fun PlayerChooserDialog(players: List<PlayerApp>, onPick: (String) -> Unit, onCancel: () -> Unit) {
    val builtIn=stringResource(R.string.ui_built_in_player)
    CompactActionMenu(stringResource(R.string.ui_play_with),listOf(builtIn)+players.map {it.label},onCancel) {first->
        MenuActionRow(builtIn,Modifier.focusRequester(first),Icons.Outlined.PlayCircleOutline) {onPick(Prefs.PLAYER_BUILTIN)}
        players.forEach {p->MenuActionRow(p.label,icon=Icons.Outlined.SmartDisplay) {onPick(p.pkg)}}
        if(players.isEmpty())Text(stringResource(R.string.ui_no_other_video_players_are_installed),color=Mute,fontSize=TvTokens.Type.Compact.sp)
    }
}
@Composable
internal fun LinkDialog(link: LinkUi, onCancel: () -> Unit) {
    MediaDialog("Connect ${link.trackerName}",onCancel) {
        Text(stringResource(R.string.ui_on_your_phone_or_computer_open),color=Mute,fontSize=TvTokens.Type.Body.sp)
        Text(link.url,fontSize=18.sp)
        Text(stringResource(R.string.ui_and_enter_this_code),color=Mute,fontSize=TvTokens.Type.Body.sp)
        Text(link.code,fontSize=44.sp,letterSpacing=6.sp)
        Text(stringResource(R.string.ui_waiting_for_approval_this_closes_by),color=Mute,fontSize=TvTokens.Type.Caption.sp)
        Chip(stringResource(R.string.ui_cancel),onClick=onCancel)
    }
}
@Composable
internal fun AppOptionsDialog(app: AppEntry, hidden: Boolean, vm: HomeViewModel, onMove: () -> Unit, onClose: () -> Unit) {
    if(LightNavigation.destination(app.pkg)!=null) {
        val hidden=app.pkg in vm.personalHomes.current.lightNavHidden
        val actions=listOf("Move with D-pad","Move earlier","Move later",if(hidden)"Show on Home" else "Hide")
        CompactActionMenu(app.label,actions,onClose) {first->
            MenuActionRow(actions[0],modifier=Modifier.focusRequester(first),icon=Icons.Outlined.OpenWith,onClick=onMove)
            MenuActionRow(actions[1]){vm.moveApp(app.pkg,-1)}
            MenuActionRow(actions[2]){vm.moveApp(app.pkg,1)}
            MenuActionRow(actions[3]){vm.toggleLightNavigation(app.pkg);onClose()}
        }
        return
    }
    val context=androidx.compose.ui.platform.LocalContext.current
    val labels=listOf("Move with D-pad",stringResource(R.string.ui_move_earlier),stringResource(R.string.ui_move_later),
        if(hidden)"Show on Home" else "Remove from Home",if(app.pkg in vm.appsPageOff)"Unhide" else "Hide",
        "App folder", "Choose artwork", if(app.pkg in vm.personalHomes.current.artwork)"Restore original artwork" else "Original artwork",
        if(app.pkg in vm.launcherExtras.options.lockedApps)"Remove PIN lock" else "Require PIN", "Uninstall")
    CompactActionMenu(app.label,labels,onClose) {first->
            MenuActionRow(labels[0],modifier=Modifier.focusRequester(first), icon=Icons.Outlined.OpenWith, onClick = onMove)
            MenuActionRow(labels[1],icon=Icons.AutoMirrored.Outlined.KeyboardArrowLeft) { vm.moveApp(app.pkg, -1) }
            MenuActionRow(labels[2],icon=Icons.AutoMirrored.Outlined.KeyboardArrowRight) { vm.moveApp(app.pkg, 1) }
            MenuActionRow(labels[3],icon=if(hidden)Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,divider=true) {
                vm.toggleHidden(app.pkg)
                onClose()
            }
            MenuActionRow(labels[4],icon=Icons.Outlined.Apps) {
                vm.toggleAppsPage(app.pkg)
                onClose()
            }
            MenuActionRow(labels[5],icon=Icons.Outlined.Folder) {onClose();vm.openSettingsGuarded {vm.homeFeatureAction="edit:assign:${app.pkg}"}}
            MenuActionRow(labels[6],icon=Icons.Outlined.Image) {onClose();vm.openSettingsGuarded {vm.homeFeatureAction="edit:art:${app.pkg}"}}
            if(app.pkg in vm.personalHomes.current.artwork)MenuActionRow(labels[7],icon=Icons.Outlined.Restore) {onClose();vm.openSettingsGuarded {vm.personalHomes.update {it.copy(artwork=it.artwork-app.pkg)}}}
            MenuActionRow(labels[8],icon=Icons.Outlined.Lock) {
                onClose();val owner=vm.profiles.profileId
                if(!vm.pin.pinSet){vm.showNotice("Set the administrator PIN first.");vm.openSettingsPage("PROFILES")}
                else vm.pin.withPin("Enter the administrator PIN") {if(owner==vm.profiles.profileId)vm.launcherExtras.update {
                    it.copy(lockedApps=if(app.pkg in it.lockedApps)it.lockedApps-app.pkg else it.lockedApps+app.pkg)}}
            }
            MenuActionRow(labels[9],icon=Icons.Outlined.DeleteOutline,destructive=true,divider=true) {
                onClose()
                val uninstall:()->Unit={if(vm.appAccess.canLaunch(app.pkg))AppManagement.uninstall(context,app.pkg)}
                vm.openSettingsGuarded(uninstall)
            }
    }
}
