package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.R
import app.ember.tv.data.CatalogNames

/** Shared by Home layout and Catalogs; clearing an alias restores the provider label. */
@Composable
internal fun CatalogRenameButton(vm: HomeViewModel, key: String, original: String) {
    var open by remember(key) { mutableStateOf(false) }
    Chip(stringResource(R.string.ui_rename)) { open = true }
    if (open) {
        var name by remember(key) { mutableStateOf(CatalogNames.label(key, original)) }
        TvDialog({open=false},width=560.dp,resizeForKeyboard=true,scrollContent=true) {
            DialogHeading("Rename catalog")
            Text(original, color = Mute, fontSize = 13.sp)
            TextInput(name, { name = it.take(120) }, "Catalog name", width = 500)
            ButtonRow(spacing=10.dp) {
                Chip(stringResource(R.string.ui_save)) { vm.renameCatalog(key, name); open = false }
                Chip(stringResource(R.string.ui_use_original)) { vm.renameCatalog(key, ""); open = false }
                Chip(stringResource(R.string.ui_cancel)) { open = false }
            }

        }
    }
}
