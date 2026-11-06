package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import app.ember.tv.discover.*
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
internal fun ExploreFilters(initial: ExploreQuery, region: String, repository: ExploreRepository,
    onApply: (ExploreQuery) -> Unit, onClose: () -> Unit,
    searchTools: (@Composable ColumnScope.() -> Unit)? = null) {
    var draft by remember { mutableStateOf(initial) }
    var years by remember { mutableStateOf(initial.fromYear?.toString().orEmpty() to initial.toYear?.toString().orEmpty()) }
    var rating by remember { mutableStateOf(initial.minRating?.toString().orEmpty() to initial.maxRating?.toString().orEmpty()) }
    var votes by remember { mutableStateOf("${initial.minMovieVotes}" to "${initial.minTvVotes}") }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf("Type and sort") }
    var picker by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        DialogMotion()
        KeyboardResizesDialog()
        CompositionLocalProvider(LocalFloating provides true, LocalFocusScene provides null) {
            BoxWithConstraints(Modifier.fillMaxSize().guardOpeningPress()) {
                Column(Modifier.align(Alignment.CenterEnd).padding(12.dp).width(minOf(520.dp, maxWidth)).fillMaxHeight()
                    .floatingPanelSurface().padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Discover filters", color = Fg, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                        Chip("Close", modifier = Modifier.focusRequester(first), onClick = onClose)
                    }
                    Text("Match every category. Multiple people, studios or services within a category match any selected entry.", color = Mute, fontSize = 12.sp)
                    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberMenuScrollState()).padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilterGroup("Type and sort", expanded, { expanded = it }) {
                            ButtonRow { ExploreKind.entries.forEach { k -> Chip(k.label, draft.kind == k) {
                                draft = draft.copy(kind = k, oscars = if (k == ExploreKind.TV) OscarMode.ANY else draft.oscars,
                                    bestPicture = if (k == ExploreKind.TV) false else draft.bestPicture)
                            } } }
                            ButtonRow { ExploreSort.entries.forEach { s -> Chip(s.label, draft.sort == s) { draft = draft.copy(sort = s) } } }
                            Text("All mixes movies and shows evenly where possible. Only released titles are included.", color = Mute, fontSize = 12.sp)
                        }
                        FilterGroup("Genre", expanded, { expanded = it }) {
                            ButtonRow { ExploreGenres.all.filter { g -> g.key in draft.genres || draft.kind == ExploreKind.ALL || g.ids(draft.kind).isNotEmpty() }.forEach { g ->
                                Chip(g.label, g.key in draft.genres) { draft = draft.copy(genres = if (g.key in draft.genres) draft.genres - g.key else draft.genres + g.key) }
                            } }
                            ButtonRow {
                                Chip("Clear genres") { draft = draft.copy(genres = emptySet(), allGenres = false) }
                                Chip("Match any genre", !draft.allGenres) { draft = draft.copy(allGenres = false) }
                                Chip("Match all genres", draft.allGenres) { draft = draft.copy(allGenres = true) }
                            }
                            Text("Genres follow the movie/show catalog. Some genres exist for only one type.", color = Mute, fontSize = 12.sp)
                        }
                        FilterGroup("Year", expanded, { expanded = it }) {
                            Text("Movie release year or a show's premiere year. Blank means any year.", color = Mute, fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TvTextField(years.first, { years = years.copy(first = it.take(4)) }, Modifier.weight(1f), placeholder = "From year")
                                TvTextField(years.second, { years = years.copy(second = it.take(4)) }, Modifier.weight(1f), placeholder = "To year")
                            }
                            ButtonRow {
                                Chip("Any") { years = "" to "" }
                                Chip("1990s") { years = "1990" to "1999" }
                                Chip("2000s") { years = "2000" to "2009" }
                                Chip("2010s") { years = "2010" to "2019" }
                                Chip("2020 onward") { years = "2020" to "" }
                            }
                        }
                        FilterGroup("Rating", expanded, { expanded = it }) {
                            Text("TMDB audience score out of 10. Other scores on title pages keep their own sources.", color = Mute, fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TvTextField(rating.first, { rating = rating.copy(first = it.take(5)) }, Modifier.weight(1f), placeholder = "Minimum score")
                                TvTextField(rating.second, { rating = rating.copy(second = it.take(5)) }, Modifier.weight(1f), placeholder = "Maximum score")
                            }
                            ButtonRow {
                                Chip("Any score") { rating = "" to "" }
                                Chip("7+") { rating = "7" to "" }
                                Chip("8+") { rating = "8" to "" }
                            }
                            if (draft.sort == ExploreSort.RATING) {
                                Text("Minimum votes for Highest rated: movies / shows. Keeps tiny samples from leading the chart.", color = Mute, fontSize = 12.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    TvTextField(votes.first, { votes = votes.copy(first = it.take(7)) }, Modifier.weight(1f), placeholder = "Movie votes")
                                    TvTextField(votes.second, { votes = votes.copy(second = it.take(7)) }, Modifier.weight(1f), placeholder = "Show votes")
                                }
                            }
                        }
                        FilterGroup("Streaming service", expanded, { expanded = it }) {
                            Text("Availability in $region, from JustWatch via TMDB. This selection is independent of your title-page provider settings.", color = Mute, fontSize = 12.sp)
                            SelectedRefs(draft.providers) { id -> draft = draft.copy(providers = draft.providers.filterNot { it.id == id }) }
                            Chip("Choose services") { picker = "Services" }
                            ToggleRow("Include rent and buy", "Off includes subscription, free and ad-supported availability.", draft.includeRentBuy) {
                                draft = draft.copy(includeRentBuy = !draft.includeRentBuy)
                            }
                            Text("Change the viewing country in Settings > Where to watch.", color = Mute, fontSize = 12.sp)
                        }
                        FilterGroup("Actor", expanded, { expanded = it }) {
                            SelectedRefs(draft.actors) { id -> draft = draft.copy(actors = draft.actors.filterNot { it.id == id }) }
                            Chip("Find actors") { picker = "Actors" }
                        }
                        FilterGroup("Director", expanded, { expanded = it }) {
                            Text("Matches the Director credit. For shows, at least one episode. Producers and creators are not treated as directors.", color = Mute, fontSize = 12.sp)
                            SelectedRefs(draft.directors) { id -> draft = draft.copy(directors = draft.directors.filterNot { it.id == id }) }
                            Chip("Find directors") { picker = "Directors" }
                        }
                        FilterGroup("Production company", expanded, { expanded = it }) {
                            Text("Choose exact studios, such as DreamWorks Animation or DreamWorks Pictures. Add both to include both companies.", color = Mute, fontSize = 12.sp)
                            SelectedRefs(draft.companies) { id -> draft = draft.copy(companies = draft.companies.filterNot { it.id == id }) }
                            Chip("Find studios") { picker = "Studios" }
                        }
                        FilterGroup("Awards", expanded, { expanded = it }) {
                            Text("Oscars are film awards. Verified records from Wikidata; its coverage may be incomplete. A title must have its own award record or be the work named in the recipient's record.", color = Mute, fontSize = 12.sp)
                            ButtonRow { OscarMode.entries.forEach { mode -> Chip(mode.label, draft.oscars == mode) {
                                draft = draft.copy(oscars = mode, kind = if (mode != OscarMode.ANY && draft.kind == ExploreKind.TV) ExploreKind.MOVIE else draft.kind)
                            } } }
                            if (draft.oscars != OscarMode.ANY) ToggleRow("Best Picture only", "Off includes any recorded Oscar category.", draft.bestPicture) {
                                draft = draft.copy(bestPicture = !draft.bestPicture)
                            }
                        }
                        if(searchTools!=null)FilterGroup("Search tools",expanded,{expanded=it}) {searchTools()}
                    }
                    error?.let { Text(it, color = Fg, fontSize = 13.sp) }
                    ButtonRow {
                        Chip("Apply filters") {
                            error = when {
                                listOf(years.first, years.second).any { it.isNotBlank() && it.trim().toIntOrNull() == null } -> "Enter valid whole years."
                                listOf(rating.first, rating.second).any { it.isNotBlank() && it.trim().toDoubleOrNull() == null } -> "Enter valid rating numbers."
                                listOf(votes.first, votes.second).any { it.toIntOrNull() == null } -> "Enter valid minimum vote counts."
                                else -> null
                            }
                            if (error == null) {
                                val value = draft.copy(fromYear = years.first.trim().toIntOrNull(), toYear = years.second.trim().toIntOrNull(),
                                    minRating = rating.first.trim().toDoubleOrNull(), maxRating = rating.second.trim().toDoubleOrNull(),
                                    minMovieVotes = votes.first.toInt(), minTvVotes = votes.second.toInt(),
                                    allGenres = draft.allGenres && draft.genres.size > 1, bestPicture = draft.bestPicture && draft.oscars != OscarMode.ANY)
                                error = value.error()
                                if (error == null) onApply(value)
                            }
                        }
                        Chip("Reset") { draft = ExploreQuery(); years = "" to ""; rating = "" to ""; votes = "500" to "200"; error = null }
                    }
                }
            }
        }
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    }
    picker?.let { type ->
        val selected = when (type) { "Services" -> draft.providers; "Actors" -> draft.actors; "Directors" -> draft.directors; else -> draft.companies }
        ExplorePicker(type, selected, repository, region, onSelect = { ref ->
            val next = if (selected.any { it.id == ref.id }) selected.filterNot { it.id == ref.id } else selected + ref
            draft = when (type) {
                "Services" -> draft.copy(providers = next.take(12))
                "Actors" -> draft.copy(actors = next.take(8))
                "Directors" -> draft.copy(directors = next.take(8))
                else -> draft.copy(companies = next.take(12))
            }
        }, onClose = { picker = null })
    }
}

@Composable
private fun FilterGroup(name: String, expanded: String, onExpanded: (String) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Chip((if (expanded == name) "− " else "+ ") + name, selected = expanded == name, modifier = Modifier.fillMaxWidth()) {
            onExpanded(if (expanded == name) "" else name)
        }
        if (expanded == name) content()
    }
}
@Composable
private fun SelectedRefs(refs: List<ExploreRef>, onRemove: (Long) -> Unit) {
    if (refs.isEmpty()) Text("Any", color = Mute, fontSize = 13.sp)
    else ButtonRow { refs.forEach { ref -> Chip("${ref.name} ×", true) { onRemove(ref.id) } } }
}

@Composable
private fun ExplorePicker(type: String, selected: List<ExploreRef>, repository: ExploreRepository, region: String,
    onSelect: (ExploreRef) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ExploreRef>>(emptyList()) }
    var services by remember { mutableStateOf<List<ExploreRef>?>(null) }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val first = remember { FocusRequester() }
    LaunchedEffect(text, retry, type, region) {
        busy = true; error = null
        try {
            if (type == "Services") {
                val all = services ?: repository.providers(region).also { services = it }
                results = all.filter { it.name.contains(text.trim(), ignoreCase = true) }
            } else {
                results = emptyList()
                if (text.trim().length >= 2) { delay(350); results = repository.search(text, type == "Studios") }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Could not load $type. Check the connection and TMDB key, then retry." }
        finally { busy = false }
    }
    TvDialog(onClose,width=520.dp,height=450.dp,resizeForKeyboard=true) {
        CompositionLocalProvider(LocalFocusScene provides null) {
            DialogHeading("Choose ${type.lowercase()}")
            TvTextField(text, { text = it.take(100) }, placeholder = if (type == "Services") "Filter services" else "Search names (2+ letters)", focusRequester = first)
            Text("${selected.size} selected · Up to ${if (type == "Services" || type == "Studios") 12 else 8} · Choose again to remove", color = Mute, fontSize = 12.sp)
            if (busy) Text("Searching…", color = Mute, fontSize = 13.sp)
            error?.let { Text(it, color = Mute, fontSize = 13.sp); Chip("Retry") { retry++ } }
            if (!busy && error == null && results.isEmpty()) Text(if (type != "Services" && text.trim().length < 2) "Enter a name to search." else "No matches.", color = Mute, fontSize = 13.sp)
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(5.dp)) {
                items(results, key = { it.id }) { ref ->
                    Focusable(onClick = { onSelect(ref) }, modifier = Modifier.fillMaxWidth(), radius = 14, container = if (selected.any { it.id == ref.id }) WashHi else Wash,
                        focusedContainer = PillColor, focusedContent = OnPillColor) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(ref.image, contentDescription = null, modifier = Modifier.size(40.dp), contentScale = ContentScale.Fit)
                            Text((if (selected.any { it.id == ref.id }) "✓ " else "") + ref.name, fontSize = 15.sp)
                        }
                    }
                }
            }
            Chip("Done", onClick = onClose)

        }
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    }
}
