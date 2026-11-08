package app.ember.tv.ui

import androidx.compose.runtime.Composable
import app.ember.tv.HomeViewModel
import app.ember.tv.data.AppEntry
import app.ember.tv.refreshApps

@Composable
fun AppsScreen(vm: HomeViewModel, onLaunch: (AppEntry) -> Unit, onAppOptions: (AppEntry) -> Unit) {
    val access = vm.appAccess
    AppLibraryCanvas(access.page(true), vm.appsPageOff, vm.profiles.profileId,
        onLaunch = onLaunch, onOptions = onAppOptions, onRefresh = { vm.refreshApps(force = true) })
}
