package app.ember.tv.ui

import androidx.compose.ui.res.stringResource
import app.ember.tv.R
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.dp
import app.ember.tv.HomeViewModel

@Composable internal fun WatchStatusSettings(vm:HomeViewModel) {
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
    Section(stringResource(R.string.st_watched_indicators),stringResource(R.string.st_show_a_check_on_watched_movies),id="watch-status") {
        ToggleRow(stringResource(R.string.st_show_watched_indicators),null,vm.look.showWatchedBadges){vm.look.updateShowWatchedBadges(!vm.look.showWatchedBadges)}
    }
    Section(stringResource(R.string.st_episode_spoilers),stringResource(R.string.st_blur_unwatched_episode_artwork_until_it),id="episode-spoilers") {
        ToggleRow(stringResource(R.string.st_blur_unwatched_episode_artwork),null,vm.look.blurUnwatchedEpisodes){vm.look.updateBlurUnwatchedEpisodes(!vm.look.blurUnwatchedEpisodes)}
    }
    }
}
