package app.ember.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.dp
import app.ember.tv.HomeViewModel

@Composable internal fun WatchStatusSettings(vm:HomeViewModel) {
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
    Section("Watched indicators","Show a check on watched movies, completed shows and episodes. Hold OK on a title to change its watched mark.",id="watch-status") {
        ToggleRow("Show watched indicators",null,vm.look.showWatchedBadges){vm.look.updateShowWatchedBadges(!vm.look.showWatchedBadges)}
    }
    Section("Episode spoilers","Blur unwatched episode artwork until it is watched or marked watched. Titles and descriptions remain visible.",id="episode-spoilers") {
        ToggleRow("Blur unwatched episode artwork",null,vm.look.blurUnwatchedEpisodes){vm.look.updateBlurUnwatchedEpisodes(!vm.look.blurUnwatchedEpisodes)}
    }
    }
}
