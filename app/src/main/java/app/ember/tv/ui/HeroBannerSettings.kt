package app.ember.tv.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.data.HeroArtworkMode
import app.ember.tv.data.HeroBanner

@Composable
internal fun HeroBannerSettings(vm: HomeViewModel) {
    var catalogs by remember { mutableStateOf(false) }
    val sourceFocus = remember { FocusRequester() }
    val choices = vm.rows.distinctBy(HeroBanner::sourceKey)
    val selected = choices.firstOrNull { HeroBanner.sourceKey(it) == vm.look.heroCatalog }
    Section("Hero banner", "Ten available titles with Watch now and watchlist actions. Right past the watchlist button advances to the next title.", id = "hero-banner") {
        Text("Artwork", fontSize = 15.sp)
        Choice(listOf(HeroArtworkMode.STANDARD to "Standard", HeroArtworkMode.FANART to "Fanart"), vm.look.heroArtworkMode, vm.look::updateHeroArtworkMode)
        Text(if (vm.look.heroArtworkMode == HeroArtworkMode.FANART)
            "Prefer Fanart backgrounds and logos. Add TMDB and Fanart API keys for the best coverage; missing images fall back to standard artwork."
            else "Use standard TMDB and catalog backgrounds and logos.", fontSize = 12.sp, color = Mute)
        if (vm.look.heroArtworkMode == HeroArtworkMode.FANART) Chip("Fanart API key") { vm.openSettingsPage("KEYS") }
        Chip("Catalog: " + (selected?.displayTitle ?: if (vm.look.heroCatalog == null) "Automatic mix" else "Unavailable — using automatic mix"), modifier = Modifier.focusRequester(sourceFocus)) { catalogs = true }
        Text("Choose a catalog enabled for Home, or mix them automatically. Up to ten distinct titles with artwork are shown; smaller catalogs show fewer.", fontSize = 12.sp, color = Mute)
    }
    if (catalogs) OptionMenu("Hero catalog", buildList {
        add(MenuOption("Automatic mix", selected = vm.look.heroCatalog == null) { vm.look.updateHeroCatalog(null) })
        choices.forEach { row -> add(MenuOption("${row.displayTitle} · ${row.addonName}", selected = vm.look.heroCatalog == HeroBanner.sourceKey(row)) {
            vm.look.updateHeroCatalog(HeroBanner.sourceKey(row))
        }) }
    }) { catalogs = false }
    RestoreToolbarFocus(catalogs, sourceFocus)
}
