package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.ember.tv.discover.*
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** #251, #252: the picture and colour each genre and award is drawn with. */
internal object FilterLook {
    fun genreIcon(key: String): ImageVector = when (key) {
        "action" -> Icons.Outlined.LocalFireDepartment
        "animation" -> Icons.Outlined.Animation
        "comedy" -> Icons.Outlined.SentimentVerySatisfied
        "crime" -> Icons.Outlined.Fingerprint
        "documentary" -> Icons.Outlined.Videocam
        "drama" -> Icons.Outlined.TheaterComedy
        "family" -> Icons.Outlined.FamilyRestroom
        "fantasy" -> Icons.Outlined.AutoAwesome
        "history" -> Icons.Outlined.HistoryEdu
        "horror" -> Icons.Outlined.DarkMode
        "music" -> Icons.Outlined.MusicNote
        "mystery" -> Icons.Outlined.QuestionMark
        "romance" -> Icons.Outlined.FavoriteBorder
        "thriller" -> Icons.Outlined.Visibility
        "war" -> Icons.Outlined.Shield
        "western" -> Icons.Outlined.Landscape
        "kids" -> Icons.Outlined.ChildCare
        "news" -> Icons.Outlined.Newspaper
        "reality" -> Icons.Outlined.CameraIndoor
        "soap" -> Icons.Outlined.Forum
        "talk" -> Icons.Outlined.Mic
        else -> Icons.Outlined.Category
    }

    fun genreColor(key: String): Color = Color(when (key) {
        "action" -> 0xFFF0643C; "animation" -> 0xFF4FC3F7; "comedy" -> 0xFFFFC94A; "crime" -> 0xFF9AA5B8
        "documentary" -> 0xFF63C59A; "drama" -> 0xFFD06BD8; "family" -> 0xFF7CCB6B; "fantasy" -> 0xFF8F7CF7
        "history" -> 0xFFC9A66B; "horror" -> 0xFFE2504A; "music" -> 0xFFF77FBE; "mystery" -> 0xFF6C8CF5
        "romance" -> 0xFFFF6F91; "thriller" -> 0xFFE58A3A; "war" -> 0xFF8DA36B; "western" -> 0xFFD9935A
        "kids" -> 0xFF5ED3C4; "news" -> 0xFF7FA7D9; "reality" -> 0xFFF2A65A; "soap" -> 0xFFE38FD0; "talk" -> 0xFF5FB4E8
        else -> 0xFF9AA5B8
    })

    fun awardIcon(key: String): ImageVector = when (key) {
        "oscars" -> Icons.Outlined.EmojiEvents
        "globes" -> Icons.Outlined.Public
        "bafta" -> Icons.Outlined.TheaterComedy
        "cannes" -> Icons.Outlined.Eco
        "venice" -> Icons.Outlined.Pets
        "berlin" -> Icons.Outlined.WorkspacePremium
        "emmys" -> Icons.Outlined.Tv
        "actors" -> Icons.Outlined.Face
        else -> Icons.Outlined.Block
    }

    fun awardColor(key: String): Color = Color(when (key) {
        "oscars" -> 0xFFE3B341; "globes" -> 0xFF4FA3E0; "bafta" -> 0xFFB8C0CC; "cannes" -> 0xFF3FB27F
        "venice" -> 0xFFE07A3F; "berlin" -> 0xFFD0544F; "emmys" -> 0xFF9A7BF0; "actors" -> 0xFFE85D9A
        else -> 0xFF8A919C
    })

    /** "Films", "TV" or "Films & TV": what an award is given to. */
    fun awardFor(body: AwardBody) = when { body.films && body.shows -> "Films & TV"; body.films -> "Films"; else -> "TV" }

    /** The query with [kind], dropping an award that is not given to that kind. */
    fun withKind(q: ExploreQuery, kind: ExploreKind): ExploreQuery {
        val body = AwardBodies.of(q.award)
        val fits = kind == ExploreKind.ALL || AwardBodies.kinds(body).contains(kind)
        return if (q.oscars == OscarMode.ANY || fits) q.copy(kind = kind) else q.copy(kind = kind, oscars = OscarMode.ANY, bestPicture = false, award = "oscars")
    }

    /** The query filtered by [key] (null for no award), moving to the one type it covers when needed. */
    fun withAward(q: ExploreQuery, key: String?): ExploreQuery {
        if (key == null) return q.copy(oscars = OscarMode.ANY, bestPicture = false, award = "oscars")
        val body = AwardBodies.of(key)
        val kinds = AwardBodies.kinds(body)
        val kind = if (q.kind == ExploreKind.ALL || q.kind in kinds) q.kind else kinds.first()
        return q.copy(kind = kind, award = body.key, oscars = if (q.oscars == OscarMode.ANY) OscarMode.WIN else q.oscars,
            bestPicture = q.bestPicture && body.best != null && body.key == q.award)
    }

    /** #251: how the year filter is set: any year, one year, or a range. */
    fun yearMode(q: ExploreQuery): Int = when { q.fromYear == null && q.toYear == null -> 0; q.fromYear != null && q.fromYear == q.toYear -> 1; else -> 2 }

    /** A typed year, when it is a whole year Discover can use. */
    fun typedYear(text: String, now: Int = java.time.LocalDate.now().year): Int? = text.trim().toIntOrNull()?.takeIf { text.trim().length == 4 && it in 1870..now }

    /** #253: people who mostly do the picker's job come first. */
    fun ranked(results: List<ExploreRef>, type: String): List<ExploreRef> {
        val want = when (type) { "Actors" -> "Acting"; "Directors" -> "Directing"; else -> return results }
        return results.sortedByDescending { it.note?.startsWith(want) == true }
    }

    fun initials(name: String) = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }
}

/** #251: genres as coloured picture tiles. */
@Composable
internal fun GenreTiles(draft: ExploreQuery, onToggle: (String) -> Unit) {
    ButtonRow(spacing = 10.dp) {
        ExploreGenres.all.filter { g -> g.key in draft.genres || draft.kind == ExploreKind.ALL || g.ids(draft.kind).isNotEmpty() }.forEach { g ->
            val on = g.key in draft.genres
            PictureTile(g.label, null, FilterLook.genreIcon(g.key), FilterLook.genreColor(g.key), on, Modifier.width(134.dp).height(92.dp).testTag("genre_${g.key}")) { onToggle(g.key) }
        }
    }
}

/** #252: each award as a tile with its emblem and what it is given to. */
@Composable
internal fun AwardTiles(draft: ExploreQuery, onPick: (String?) -> Unit) {
    ButtonRow(spacing = 10.dp) {
        PictureTile("No award filter", "Everything", Icons.Outlined.Block, FilterLook.awardColor(""), draft.oscars == OscarMode.ANY,
            Modifier.width(134.dp).height(112.dp).testTag("award_none")) { onPick(null) }
        AwardBodies.all.forEach { body ->
            PictureTile(body.short, FilterLook.awardFor(body), FilterLook.awardIcon(body.key), FilterLook.awardColor(body.key),
                draft.oscars != OscarMode.ANY && draft.award == body.key, Modifier.width(134.dp).height(112.dp).testTag("award_${body.key}")) { onPick(body.key) }
        }
    }
}

/** A tile with a coloured emblem, a name and an optional line under it; a tick when chosen. */
@Composable
private fun PictureTile(label: String, note: String?, icon: ImageVector, tint: Color, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Focusable(onClick = onClick, modifier = modifier.semantics { this.selected = on }
        .then(if (on) Modifier.border(2.dp, accent, RoundedCornerShape(TvTokens.Shape.Card.dp)) else Modifier),
        radius = TvTokens.Shape.Card, container = tint.copy(alpha = if (on) .30f else .14f), focusedContainer = PillColor, focusedContent = OnPillColor,
        outline = false, focusedScale = TvTokens.Focus.Card) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.size(34.dp).background(Brush.linearGradient(listOf(tint, tint.copy(alpha = .55f))), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                    MaterialGlyph(icon, Color(0xFF15161A), 20.dp)
                }
                Column {
                    Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, lineHeight = 16.sp, overflow = TextOverflow.Ellipsis)
                    note?.let { Text(it, fontSize = 12.sp, color = LocalContentColor.current.copy(alpha = .7f), maxLines = 1) }
                }
            }
            if (on) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(20.dp).background(accent, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                MaterialGlyph(Icons.Outlined.Check, MaterialTheme.colorScheme.onPrimary, 14.dp)
            }
        }
    }
}

/** A row of choices where one is filled, like the title type switch. */
@Composable
internal fun Segments(options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.background(Wash, RoundedCornerShape(50)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Focusable(onClick = { if (!on) onPick(i) }, modifier = Modifier.semantics { this.selected = on }, radius = TvTokens.Shape.Pill,
                container = if (on) MaterialTheme.colorScheme.primary else Wash, focusedContainer = PillColor, focusedContent = OnPillColor,
                outline = false, focusedScale = TvTokens.Focus.Control) {
                Text(label, Modifier.padding(horizontal = 18.dp, vertical = 8.dp), fontSize = 15.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else LocalContentColor.current)
            }
        }
    }
}

/** #251: the year as any year, one typed year, or a range between two sliders. */
@Composable
internal fun YearPane(draft: ExploreQuery, onChange: (ExploreQuery) -> Unit) {
    var mode by remember { mutableIntStateOf(FilterLook.yearMode(draft)) }
    var typed by remember { mutableStateOf(draft.fromYear?.takeIf { draft.fromYear == draft.toYear }?.toString().orEmpty()) }
    Segments(listOf("Any year", "One year", "Between years"), mode) { m ->
        mode = m
        when (m) {
            0 -> onChange(draft.copy(fromYear = null, toYear = null))
            1 -> { val y = FilterLook.typedYear(typed) ?: draft.fromYear; onChange(draft.copy(fromYear = y, toYear = y)); typed = y?.toString().orEmpty() }
            else -> if (draft.fromYear != null && draft.fromYear == draft.toYear) onChange(draft.copy(toYear = null))
        }
    }
    when (mode) {
        0 -> PaneHint("Titles from every year.")
        1 -> {
            Text("Year", color = Fg, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            TvTextField(typed, { text ->
                typed = text.filter(Char::isDigit).take(4)
                FilterLook.typedYear(typed)?.let { onChange(draft.copy(fromYear = it, toYear = it)) }
            }, Modifier.width(220.dp).testTag("year_typed"), placeholder = "e.g. 1994", keyboardType = KeyboardType.Number)
            PaneHint(if (typed.isNotEmpty() && FilterLook.typedYear(typed) == null) "Enter a year from 1870 to ${java.time.LocalDate.now().year}."
                else "Only titles released that year.")
        }
        else -> {
            StepSlider("Earliest year", FilterSteps.years, draft.fromYear ?: 0, { if (it == 0) "Any" else "$it" }, width = 520.dp) { y ->
                val (from, to) = FilterSteps.ordered(y.takeIf { it > 0 }, draft.toYear); onChange(draft.copy(fromYear = from, toYear = to))
            }
            StepSlider("Latest year", FilterSteps.years, draft.toYear ?: 0, { if (it == 0) "Now" else "$it" }, width = 520.dp) { y ->
                val (from, to) = FilterSteps.ordered(draft.fromYear, y.takeIf { it > 0 }); onChange(draft.copy(fromYear = from, toYear = to))
            }
            Text("Showing " + FilterSteps.yearSummary(draft).replaceFirstChar { it.lowercase() }, color = Fg, fontSize = 15.sp)
            PaneHint("Earliest is the first year to include and Latest the last. A movie's release year or a show's first year.")
        }
    }
}

@Composable
private fun PaneHint(text: String) = Text(text, color = Mute, fontSize = 12.sp)

/**
 * #253: finding actors, directors, studios or services in a large panel: a grid of photos and logos
 * that shows a dozen or more matches at once instead of three rows.
 */
@Composable
internal fun ExplorePicker(type: String, selected: List<ExploreRef>, repository: ExploreRepository, region: String,
    onSelect: (ExploreRef) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ExploreRef>>(emptyList()) }
    var services by remember { mutableStateOf<List<ExploreRef>?>(null) }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val first = remember { FocusRequester() }
    val people = type == "Actors" || type == "Directors"
    LaunchedEffect(text, retry, type, region) {
        busy = true; error = null
        try {
            if (type == "Services") {
                val all = services ?: repository.providers(region).also { services = it }
                results = all.filter { it.name.contains(text.trim(), ignoreCase = true) }
            } else {
                results = emptyList()
                if (text.trim().length >= 2) { delay(350); results = FilterLook.ranked(repository.search(text, type == "Studios"), type) }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Could not load ${type.lowercase()}. Check the connection and TMDB key, then retry." }
        finally { busy = false }
    }
    TvDialog(onClose, width = 1120.dp, height = 660.dp, resizeForKeyboard = true) {
        CompositionLocalProvider(LocalFocusScene provides null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DialogHeading("Choose ${type.lowercase()}")
                Spacer(Modifier.weight(1f))
                Text("${selected.size} selected · up to ${if (type == "Services" || type == "Studios") 12 else 8}", color = Mute, fontSize = 13.sp)
                PrimaryButton("Done", onClick = onClose)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TvTextField(text, { text = it.take(100) }, Modifier.width(460.dp),
                    placeholder = if (type == "Services") "Filter services" else "Search names (2+ letters)", focusRequester = first)
                when {
                    busy -> Text("Searching…", color = Mute, fontSize = 13.sp)
                    error != null -> { Text(error!!, color = Mute, fontSize = 13.sp); Chip("Retry") { retry++ } }
                    results.isEmpty() -> Text(if (type != "Services" && text.trim().length < 2) "Type a name to search." else "No matches.", color = Mute, fontSize = 13.sp)
                    else -> Text("Choose to add · choose again to remove", color = Mute, fontSize = 13.sp)
                }
            }
            LazyVerticalGrid(GridCells.Adaptive(if (people) 138.dp else 182.dp), Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(results, key = { it.id }) { ref ->
                    RefCard(ref, people, selected.any { it.id == ref.id }) { onSelect(ref.copy(note = null)) }
                }
            }
        }
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    }
}

/** One match: a photo for a person, a logo on a light plate for a studio or service. */
@Composable
private fun RefCard(ref: ExploreRef, person: Boolean, on: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Focusable(onClick = onClick, modifier = Modifier.fillMaxWidth().semantics { this.selected = on }
        .then(if (on) Modifier.border(2.dp, accent, RoundedCornerShape(TvTokens.Shape.Card.dp)) else Modifier),
        radius = TvTokens.Shape.Card, container = if (on) WashHi else Wash, focusedContainer = PillColor, focusedContent = OnPillColor,
        outline = false, focusedScale = TvTokens.Focus.Card) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().height(if (person) 150.dp else 84.dp).clip(RoundedCornerShape(TvTokens.Shape.Small.dp))
                .background(if (person) Wash else Color(0xFFF1F2F4)), contentAlignment = Alignment.Center) {
                if (ref.image == null) Text(FilterLook.initials(ref.name), fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
                    color = if (person) LocalContentColor.current.copy(alpha = .7f) else Color(0xFF3A3D44))
                else AsyncImage(ref.image, contentDescription = null,
                    modifier = if (person) Modifier.fillMaxSize() else Modifier.fillMaxSize().padding(12.dp),
                    contentScale = if (person) ContentScale.Crop else ContentScale.Fit, alignment = if (person) Alignment.TopCenter else Alignment.Center)
                if (on) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).background(accent, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                    MaterialGlyph(Icons.Outlined.Check, MaterialTheme.colorScheme.onPrimary, 15.dp)
                }
            }
            Text(ref.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, lineHeight = 17.sp, overflow = TextOverflow.Ellipsis)
            ref.note?.let { Text(it, fontSize = 12.sp, color = LocalContentColor.current.copy(alpha = .65f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}
