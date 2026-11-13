package app.ember.tv.ui

import androidx.compose.ui.res.stringResource
import app.ember.tv.R
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import app.ember.tv.data.KidsContentPolicy
import app.ember.tv.data.Profile

@Composable internal fun KidsContentSettings(profile:Profile,onChange:((Profile)->Profile)->Unit) {
    var genre by remember(profile.id){mutableStateOf(false)}
    var rating by remember(profile.id){mutableStateOf(false)}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Section(stringResource(R.string.st_content_lock),stringResource(R.string.st_choose_a_genre_a_maximum_age),id="kids-content-lock") {
            ButtonRow {
                Chip(profile.lockedGenre ?: stringResource(R.string.st_any_genre)){genre=true}
                Chip(profile.maxTvRating?.let {stringResource(R.string.st_up_to, it)} ?: stringResource(R.string.st_any_age_rating)){rating=true}
            }
            if(KidsContentPolicy.restricted(profile))Note(KidsContentPolicy.summary(profile)+stringResource(R.string.st_live_tv_is_hidden_while_a))
            Note(stringResource(R.string.st_us_tv_ratings_are_used_as))
        }
    }
    if(genre)OptionMenu("Lock to genre",listOf(MenuOption("Any genre",profile.lockedGenre==null){onChange {it.copy(lockedGenre=null)};genre=false})+
        KidsContentPolicy.genres.map {value->MenuOption(value,profile.lockedGenre==value){onChange {it.copy(lockedGenre=value)};genre=false}},close={genre=false})
    if(rating)OptionMenu("Maximum age rating",listOf(MenuOption("Any age rating",profile.maxTvRating==null){onChange {it.copy(maxTvRating=null)};rating=false})+
        KidsContentPolicy.ratings.map {value->MenuOption(value,profile.maxTvRating==value){onChange {it.copy(maxTvRating=value)};rating=false}},close={rating=false})
}
