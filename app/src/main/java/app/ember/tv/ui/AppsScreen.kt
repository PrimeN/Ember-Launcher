package app.ember.tv.ui

import androidx.compose.ui.res.stringResource
import app.ember.tv.R

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.data.AppEntry

@Composable
fun AppsScreen(vm: HomeViewModel, onLaunch: (AppEntry) -> Unit, onAppOptions: (AppEntry) -> Unit) {
    val access = vm.appAccess
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.ui_apps), fontSize = 26.sp)
        Text(
            stringResource(R.string.ui_hold_ok_on_an_app_to),
            color = Mute,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        var showHidden by androidx.compose.runtime.remember(vm.profiles.profile) { androidx.compose.runtime.mutableStateOf(false) }
        if (access.hiddenOnPage > 0) {
            Chip(if (showHidden) "Done showing hidden apps" else "Show ${access.hiddenOnPage} hidden app(s)", modifier = Modifier.padding(bottom = 8.dp)) { showHidden = !showHidden }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(8.dp),
        ) {
            items(access.page(showHidden), key = { it.pkg }) { app ->
                AppTile(
                    app,
                    dim = app.pkg in vm.appsPageOff,
                    onClick = { onLaunch(app) },
                    onLongClick = { onAppOptions(app) },
                )
            }
        }
    }
}
