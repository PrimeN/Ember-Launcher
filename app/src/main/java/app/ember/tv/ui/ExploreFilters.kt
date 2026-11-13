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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
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

/** #244: the values the filter sliders step through, and each category's one-line summary. */
internal object FilterSteps {
    private val thisYear = java.time.LocalDate.now().year
    /** 0 means any year. */
    val years: List<Int> = (listOf(0) + (1920..2020 step 10) + listOf(2025, thisYear)).distinct().sorted()
    /** Tenths of a TMDB score; 0 means any. */
    val minScore = listOf(0, 50, 55, 60, 65, 70, 75, 80, 85, 90)
    /** Tenths; 100 means no upper limit. */
    val maxScore = listOf(50, 60, 70, 80, 85, 90, 95, 100)
    val movieVotes = listOf(0, 50, 100, 200, 500, 1000, 2000, 5000, 10000)
    val showVotes = listOf(0, 50, 100, 200, 500, 1000, 2000)

    fun yearLabel(y: Int) = if (y == 0) "Any" else "$y"
    fun minLabel(t: Int) = if (t == 0) "Any" else "${t / 10}.${t % 10}+"
    fun maxLabel(t: Int) = if (t >= 100) "Any" else "Up to ${t / 10}.${t % 10}"
    fun votesLabel(v: Int) = if (v == 0) "Any" else "$v+"
    fun tenths(score: Double?) = score?.let { (it * 10).toInt() }

    /** The years in order, whichever end was set first. */
    fun ordered(from: Int?, to: Int?): Pair<Int?, Int?> = if (from != null && to != null && from > to) to to from else from to to

    fun yearSummary(q: ExploreQuery) = when {
        q.fromYear == null && q.toYear == null -> "Any year"
        q.toYear == null -> "${q.fromYear} onward"
        q.fromYear == null -> "Up to ${q.toYear}"
        else -> "${q.fromYear}–${q.toYear}"
    }
    fun scoreSummary(q: ExploreQuery) = when {
        q.minRating == null && q.maxRating == null -> "Any score"
        q.maxRating == null -> "${q.minRating}+"
        else -> "${q.minRating ?: 0.0}–${q.maxRating}"
    }
    fun names(refs: List<ExploreRef>, none: String) = if (refs.isEmpty()) none else refs.joinToString(", ") { it.name }
}

/**
 * Discover's filters (#244): categories on the left with what each is set to, the chosen category's
 * controls on the right, and one Show results button. Years, scores and votes are sliders.
 */
@Composable
internal fun ExploreFilters(initial: ExploreQuery, region: String, repository: ExploreRepository,
    onApply: (ExploreQuery) -> Unit, onClose: () -> Unit,
    searchTools: (@Composable ColumnScope.() -> Unit)? = null) {
    var draft by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var current by remember { mutableStateOf("Genre") }
    var picker by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    val categories = buildList {
        add(Triple("Genre", androidx.compose.material.icons.Icons.Outlined.Category, ExploreGenres.selected(draft).joinToString(", ") { it.label }.ifEmpty { "Any genre" }))
        add(Triple("Year", androidx.compose.material.icons.Icons.Outlined.CalendarMonth, FilterSteps.yearSummary(draft)))
        add(Triple("Score", androidx.compose.material.icons.Icons.Outlined.StarOutline, FilterSteps.scoreSummary(draft)))
        add(Triple("Streaming", androidx.compose.material.icons.Icons.Outlined.LiveTv, FilterSteps.names(draft.providers, "Any service")))
        add(Triple("Actors", androidx.compose.material.icons.Icons.Outlined.Person, FilterSteps.names(draft.actors, "Anyone")))
        add(Triple("Directors", androidx.compose.material.icons.Icons.Outlined.Movie, FilterSteps.names(draft.directors, "Anyone")))
        add(Triple("Studios", androidx.compose.material.icons.Icons.Outlined.Business, FilterSteps.names(draft.companies, "Any studio")))
        add(Triple("Awards", androidx.compose.material.icons.Icons.Outlined.EmojiEvents, draft.oscars.label + if (draft.bestPicture) " · Best Picture" else ""))
        if (searchTools != null) add(Triple("Search tools", androidx.compose.material.icons.Icons.Outlined.Tune, "Save, load, refresh"))
    }
    val active = exploreTags(draft).size
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        DialogMotion()
        KeyboardResizesDialog()
        CompositionLocalProvider(LocalFloating provides true, LocalFocusScene provides null) {
            BoxWithConstraints(Modifier.fillMaxSize().guardOpeningPress()) {
                Column(Modifier.align(Alignment.CenterEnd).padding(12.dp).width(minOf(960.dp, maxWidth)).fillMaxHeight()
                    .floatingPanelSurface().padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Filters", color = Fg, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                        KindSegments(draft.kind) { k ->
                            draft = draft.copy(kind = k, oscars = if (k == ExploreKind.TV) OscarMode.ANY else draft.oscars,
                                bestPicture = if (k == ExploreKind.TV) false else draft.bestPicture)
                        }
                        Spacer(Modifier.weight(1f))
                        DialogClose(onClose)
                    }
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        // Moving down the list shows each category's controls; right goes into them.
                        Column(Modifier.width(290.dp).fillMaxHeight().verticalScroll(rememberMenuScrollState()).padding(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            categories.forEachIndexed { i, (name, icon, summary) ->
                                FilterRailItem(name, summary, icon, selected = current == name,
                                    set = name != "Search tools" && exploreTags(draft).isNotEmpty() && summary !in FilterRail.unset,
                                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier, onFocus = { current = name }) { current = name }
                            }
                        }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(Fg.copy(alpha = .08f)))
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberMenuScrollState()).padding(4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text(current, color = Fg, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                            when (current) {
                                "Genre" -> {
                                    ButtonRow { ExploreGenres.all.filter { g -> g.key in draft.genres || draft.kind == ExploreKind.ALL || g.ids(draft.kind).isNotEmpty() }.forEach { g ->
                                        Chip(g.label, g.key in draft.genres) { draft = draft.copy(genres = if (g.key in draft.genres) draft.genres - g.key else draft.genres + g.key) }
                                    } }
                                    if (draft.genres.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("Titles must have", color = Mute, fontSize = 14.sp)
                                        Chip("Any of these", !draft.allGenres) { draft = draft.copy(allGenres = false) }
                                        Chip("All of these", draft.allGenres) { draft = draft.copy(allGenres = true) }
                                    }
                                    if (draft.genres.isNotEmpty()) Chip("Clear genres") { draft = draft.copy(genres = emptySet(), allGenres = false) }
                                    Hint("Genres follow the movie or show catalog; some exist for only one type.")
                                }
                                "Year" -> {
                                    ButtonRow {
                                        listOf("Any" to (null to null), "1980s" to (1980 to 1989), "1990s" to (1990 to 1999), "2000s" to (2000 to 2009), "2010s" to (2010 to 2019), "2020 onward" to (2020 to null))
                                            .forEach { (label, range) -> Chip(label, draft.fromYear == range.first && draft.toYear == range.second) { draft = draft.copy(fromYear = range.first, toYear = range.second) } }
                                    }
                                    StepSlider("From", FilterSteps.years, draft.fromYear ?: 0, FilterSteps::yearLabel, width = 520.dp) { y ->
                                        val (from, to) = FilterSteps.ordered(y.takeIf { it > 0 }, draft.toYear); draft = draft.copy(fromYear = from, toYear = to)
                                    }
                                    StepSlider("To", FilterSteps.years, draft.toYear ?: 0, { if (it == 0) "Now" else "$it" }, width = 520.dp) { y ->
                                        val (from, to) = FilterSteps.ordered(draft.fromYear, y.takeIf { it > 0 }); draft = draft.copy(fromYear = from, toYear = to)
                                    }
                                    Hint("A movie's release year or a show's first year.")
                                }
                                "Score" -> {
                                    ButtonRow {
                                        Chip("Any", draft.minRating == null && draft.maxRating == null) { draft = draft.copy(minRating = null, maxRating = null) }
                                        listOf(6.0, 7.0, 7.5, 8.0).forEach { m -> Chip("$m+", draft.minRating == m && draft.maxRating == null) { draft = draft.copy(minRating = m, maxRating = null) } }
                                    }
                                    StepSlider("At least", FilterSteps.minScore, FilterSteps.tenths(draft.minRating) ?: 0, FilterSteps::minLabel, width = 520.dp) { t ->
                                        draft = draft.copy(minRating = t.takeIf { it > 0 }?.div(10.0))
                                    }
                                    StepSlider("At most", FilterSteps.maxScore, FilterSteps.tenths(draft.maxRating) ?: 100, FilterSteps::maxLabel, width = 520.dp) { t ->
                                        draft = draft.copy(maxRating = t.takeIf { it < 100 }?.div(10.0))
                                    }
                                    Text("Votes needed for Highest rated", color = Fg, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                    StepSlider("Movies", FilterSteps.movieVotes, draft.minMovieVotes, FilterSteps::votesLabel, width = 520.dp) { draft = draft.copy(minMovieVotes = it) }
                                    StepSlider("Shows", FilterSteps.showVotes, draft.minTvVotes, FilterSteps::votesLabel, width = 520.dp) { draft = draft.copy(minTvVotes = it) }
                                    Hint("TMDB audience score out of 10. Vote minimums keep tiny samples from leading Highest rated.")
                                }
                                "Streaming" -> {
                                    SelectedRefs(draft.providers) { id -> draft = draft.copy(providers = draft.providers.filterNot { it.id == id }) }
                                    Chip("Choose services") { picker = "Services" }
                                    ToggleRow("Include rent and buy", "Off: subscription, free and ad-supported only.", draft.includeRentBuy) {
                                        draft = draft.copy(includeRentBuy = !draft.includeRentBuy)
                                    }
                                    Hint("Availability in $region from JustWatch via TMDB. Change the country in Settings › Where to watch.")
                                }
                                "Actors" -> {
                                    SelectedRefs(draft.actors) { id -> draft = draft.copy(actors = draft.actors.filterNot { it.id == id }) }
                                    Chip("Find actors") { picker = "Actors" }
                                    Hint("Several actors match a title with any of them.")
                                }
                                "Directors" -> {
                                    SelectedRefs(draft.directors) { id -> draft = draft.copy(directors = draft.directors.filterNot { it.id == id }) }
                                    Chip("Find directors") { picker = "Directors" }
                                    Hint("Matches the Director credit; for shows, at least one episode.")
                                }
                                "Studios" -> {
                                    SelectedRefs(draft.companies) { id -> draft = draft.copy(companies = draft.companies.filterNot { it.id == id }) }
                                    Chip("Find studios") { picker = "Studios" }
                                    Hint("Choose exact companies, such as DreamWorks Animation or DreamWorks Pictures.")
                                }
                                "Awards" -> {
                                    ButtonRow { OscarMode.entries.forEach { mode -> Chip(mode.label, draft.oscars == mode) {
                                        draft = draft.copy(oscars = mode, kind = if (mode != OscarMode.ANY && draft.kind == ExploreKind.TV) ExploreKind.MOVIE else draft.kind)
                                    } } }
                                    if (draft.oscars != OscarMode.ANY) ToggleRow("Best Picture only", "Off includes any Oscar category.", draft.bestPicture) {
                                        draft = draft.copy(bestPicture = !draft.bestPicture)
                                    }
                                    Hint("Oscars are film awards, from Wikidata records; coverage may be incomplete.")
                                }
                                "Search tools" -> searchTools?.invoke(this)
                            }
                        }
                    }
                    error?.let { Text(it, color = Fg, fontSize = 13.sp) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        PrimaryButton(if (active == 0) "Show results" else "Show results · $active ${if (active == 1) "filter" else "filters"}", Modifier.testTag("filters_apply")) {
                            val (from, to) = FilterSteps.ordered(draft.fromYear, draft.toYear)
                            val value = draft.copy(fromYear = from, toYear = to,
                                allGenres = draft.allGenres && draft.genres.size > 1, bestPicture = draft.bestPicture && draft.oscars != OscarMode.ANY)
                            error = value.error()
                            if (error == null) onApply(value)
                        }
                        Chip("Reset all") { draft = ExploreQuery(kind = draft.kind, sort = draft.sort); error = null }
                        Spacer(Modifier.weight(1f))
                        Text("Every category must match; within one, any choice can.", color = Mute, fontSize = 12.sp)
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
private fun Hint(text: String) = Text(text, color = Mute, fontSize = 12.sp)

/** One category on the left: its icon, name and current value; a dot when it filters. */
@Composable
private fun FilterRailItem(name: String, summary: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, set: Boolean,
    modifier: Modifier = Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val accent = androidx.tv.material3.MaterialTheme.colorScheme.primary
    Focusable(onClick = onClick, modifier = modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() },
        radius = TvTokens.Shape.Control, container = if (selected) WashHi else Color.Transparent, focusedContainer = PillColor, focusedContent = OnPillColor,
        outline = false, focusedScale = TvTokens.Focus.Inline) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MaterialGlyph(icon, if (set) accent else androidx.tv.material3.LocalContentColor.current.copy(alpha = .8f), 20.dp)
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(summary, fontSize = 12.sp, color = androidx.tv.material3.LocalContentColor.current.copy(alpha = .65f), maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            if (set) Box(Modifier.size(8.dp).background(accent, RoundedCornerShape(50)))
        }
    }
}

/** What a category's summary says when it does not filter. */
internal object FilterRail { val unset = setOf("Any genre", "Any year", "Any score", "Any service", "Anyone", "Any studio", "Any awards") }

/** The panel's main action: filled with the accent colour. */
@Composable
internal fun PrimaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accent = androidx.tv.material3.MaterialTheme.colorScheme.primary
    Focusable(onClick = onClick, modifier = modifier, radius = TvTokens.Shape.Pill, container = accent, focusedContainer = PillColor, focusedContent = OnPillColor,
        outline = false, focusedScale = TvTokens.Focus.Control) {
        Text(label, Modifier.padding(horizontal = 22.dp, vertical = 10.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            color = if (androidx.tv.material3.LocalContentColor.current == OnPillColor) OnPillColor else androidx.tv.material3.MaterialTheme.colorScheme.onPrimary)
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
                    Focusable(onClick = { onSelect(ref) }, modifier = Modifier.fillMaxWidth(), radius = TvTokens.Shape.Control, container = if (selected.any { it.id == ref.id }) WashHi else Wash,
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
