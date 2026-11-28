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
        // #426
        Choice(listOf("show" to "Show","dim" to "Dim","hide" to "Hide"),vm.look.watchedOnHome,"Watched titles on Home") {vm.look.updateWatchedOnHome(it)}
        // #475
        ToggleRow("Hide finished titles while browsing","Films you've watched and shows you've finished leave Discover, Explore, collections, More like this and Spark. Shows you're part-way through stay.",
            vm.look.hideFinished){vm.look.updateHideFinished(!vm.look.hideFinished)}
        // #425
        ToggleRow("Episodes left on Continue watching","\"8 episodes left\" under a show you're part-way through (needs a TMDB key).",vm.look.episodesLeft,
            unavailable=if(app.ember.tv.tracker.Tmdb.available)null else "Needs a TMDB key in ${SettingsTab.KEYS.path}."){vm.look.updateEpisodesLeft(!vm.look.episodesLeft)}
    }
    Section(stringResource(R.string.st_episode_spoilers),stringResource(R.string.st_blur_unwatched_episode_artwork_until_it),id="episode-spoilers") {
        ToggleRow(stringResource(R.string.st_blur_unwatched_episode_artwork),null,vm.look.blurUnwatchedEpisodes){vm.look.updateBlurUnwatchedEpisodes(!vm.look.blurUnwatchedEpisodes)}
    }
    }
}
