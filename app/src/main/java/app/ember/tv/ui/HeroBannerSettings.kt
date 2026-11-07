package app.ember.tv.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.data.HeroArtworkMode

@Composable
internal fun HeroBannerSettings(vm: HomeViewModel) {
    var catalogs by remember { mutableStateOf(false) }
    val sourceFocus = remember { FocusRequester() }
    Section("Hero banner", "Ten available titles with Watch now and watchlist actions. Right past the watchlist button advances to the next title.", id = "hero-banner") {
        Text("Artwork", fontSize = 15.sp)
        Choice(listOf(HeroArtworkMode.STANDARD to "Standard", HeroArtworkMode.FANART to "Fanart"), vm.look.heroArtworkMode, vm.look::updateHeroArtworkMode)
        Text(if (vm.look.heroArtworkMode == HeroArtworkMode.FANART)
            "Prefer Fanart backgrounds and logos. Add TMDB and Fanart API keys for the best coverage; missing images fall back to standard artwork."
            else "Use standard TMDB and catalog backgrounds and logos.", fontSize = 12.sp, color = Mute)
        if (vm.look.heroArtworkMode == HeroArtworkMode.FANART) Chip("Fanart API key") { vm.openSettingsPage("KEYS") }
        Chip("Catalog: " + catalogSourceLabel(vm, vm.look.heroCatalog), modifier = Modifier.focusRequester(sourceFocus)) { catalogs = true }
        Text("Choose any addon catalog, or mix the Home catalogs automatically. Up to ten distinct titles with artwork are shown; smaller catalogs show fewer.", fontSize = 12.sp, color = Mute)
    }
    if (catalogs) CatalogSourcePicker(vm, "Hero catalog", vm.look.heroCatalog, vm.look::updateHeroCatalog) { catalogs = false }
    RestoreToolbarFocus(catalogs, sourceFocus)
}
