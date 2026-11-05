package app.ember.tv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import app.ember.tv.data.KidsContentPolicy
import app.ember.tv.data.Profile

@Composable internal fun KidsContentSettings(profile:Profile,onChange:((Profile)->Profile)->Unit) {
    var genre by remember(profile.id){mutableStateOf(false)}
    var rating by remember(profile.id){mutableStateOf(false)}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Section("Content lock","Choose a genre, a maximum age certification, or both. Titles without required metadata stay hidden.",id="kids-content-lock") {
            ButtonRow {
                Chip(profile.lockedGenre ?: "Any genre"){genre=true}
                Chip(profile.maxTvRating?.let {"Up to $it"} ?: "Any age rating"){rating=true}
            }
            if(KidsContentPolicy.restricted(profile))Note(KidsContentPolicy.summary(profile)+". Live TV is hidden while a content lock is active.")
            Note("US TV ratings are used as a ceiling; movies use G, PG, PG-13 and R equivalents. Genre alone is not an age restriction. Other apps keep their own parental controls.")
        }
    }
    if(genre)OptionMenu("Lock to genre",listOf(MenuOption("Any genre",profile.lockedGenre==null){onChange {it.copy(lockedGenre=null)};genre=false})+
        KidsContentPolicy.genres.map {value->MenuOption(value,profile.lockedGenre==value){onChange {it.copy(lockedGenre=value)};genre=false}},close={genre=false})
    if(rating)OptionMenu("Maximum age rating",listOf(MenuOption("Any age rating",profile.maxTvRating==null){onChange {it.copy(maxTvRating=null)};rating=false})+
        KidsContentPolicy.ratings.map {value->MenuOption(value,profile.maxTvRating==value){onChange {it.copy(maxTvRating=value)};rating=false}},close={rating=false})
}
