package app.ember.tv.ui

import androidx.compose.ui.res.stringResource
import app.ember.tv.R

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.data.*

/**
 * #254: the widget creator as a guided flow. Each step opens only when the one before it is done,
 * the source is loaded and checked before it can be used, and Save waits on the last step, where
 * the widget is shown as it will look on Home. Editing a widget keeps every step one press away.
 */
internal object WidgetSteps {
    const val SOURCE = "source"; const val STYLE = "style"; const val DETAILS = "details"; const val REVIEW = "review"
    val all = listOf(SOURCE to "Content", STYLE to "Look", DETAILS to "Name & size", REVIEW to "Review")
    private val ids = all.map { it.first }

    /** A new widget starts with what it shows; editing opens on the review, with every step one press away. */
    fun first(existing: Boolean) = if (existing) REVIEW else SOURCE
    fun next(step: String): String? = ids.getOrNull(ids.indexOf(step) + 1)
    fun previous(step: String): String? = ids.getOrNull(ids.indexOf(step) - 1)
    fun index(step: String) = ids.indexOf(step)
    fun name(step: String) = all.first { it.first == step }.second
    fun heading(step: String) = when (step) {
        SOURCE -> "What should it show?"; STYLE -> "How should it look?"; DETAILS -> "Name it and choose its size"; else -> "Check it and save"
    }
    fun intro(step: String) = when (step) {
        SOURCE -> "Pick where the titles come from. Ember loads it to make sure there is something to show."
        STYLE -> "Choose the shape of the widget on Home. You can change it later."
        DETAILS -> "The name is shown above the widget on Home."
        else -> "This is how the widget will look on Home."
    }

    /** What loading the source found: [titles] is null while it loads or before a source is chosen. */
    data class Check(val titles: Int? = null, val error: String? = null)

    /** Why the source cannot be used yet; null when it is ready. */
    fun sourceProblem(draft: HomeWidget, check: Check): String? {
        if (draft.source == CollectionSource()) return "Choose where the titles come from."
        HomeWidgets.error(draft.copy(title = draft.title.ifBlank { "x" }))?.let { return it }
        if (check.error != null) return "This source could not be loaded: ${check.error}. Try again or choose another."
        val titles = check.titles ?: return "Checking the source…"
        // Your own lists fill in as you watch and add titles; anything else with nothing in it is a mistake.
        if (titles == 0 && draft.source.provider != HomeWidgets.LIST) return "This source has no titles. Choose another or change the filters."
        return null
    }

    /** What stops a step from being done; null when it is. */
    fun problem(step: String, draft: HomeWidget, check: Check): String? = when (step) {
        SOURCE -> sourceProblem(draft, check)
        DETAILS -> if (draft.title.isBlank()) "Give the widget a name." else null
        else -> null
    }

    /** A step opens when every step before it is done; an existing widget's steps are always open. */
    fun open(step: String, draft: HomeWidget, check: Check, existing: Boolean) =
        existing || ids.takeWhile { it != step }.all { problem(it, draft, check) == null }

    /** The first step with something missing and what it is; null when the widget can be saved. */
    fun blocker(draft: HomeWidget, check: Check): Pair<String, String>? =
        ids.firstNotNullOfOrNull { id -> problem(id, draft, check)?.let { id to it } }

    /** Save shows on the review, or on any step while editing. */
    fun showsSave(step: String, existing: Boolean) = existing || step == REVIEW

    /** The kinds of source, as cards on the first step. */
    const val FILTERS = "filters"; const val LISTS = "lists"; const val CATALOG = "catalog"; const val TRAKT = "trakt"; const val MDB = "mdblist"
    val kinds = listOf(
        FILTERS to ("Filters" to "Genre, years, rating, people, services"),
        LISTS to ("Your lists" to "Watchlist, Continue watching, tracker lists"),
        CATALOG to ("Catalogs & searches" to "Addon catalogs, saved Discover searches, TMDB"),
        TRAKT to ("Trakt list" to "Popular, trending or your own lists"),
        MDB to ("MDBList" to "A list link, user/list-name or number"),
    )
    /** Which card a chosen source belongs to; null before one is chosen. */
    fun kindOf(source: CollectionSource): String? = when {
        source == CollectionSource() -> null
        source.provider == "ember" -> FILTERS
        source.provider == HomeWidgets.LIST -> LISTS
        source.provider == "trakt" -> TRAKT
        source.provider == HomeWidgets.MDBLIST -> MDB
        else -> CATALOG
    }

    /** "24 titles found", or what is wrong, under the chosen source. */
    fun status(draft: HomeWidget, check: Check): String = when {
        draft.source == CollectionSource() -> "Nothing chosen yet"
        check.error != null -> "Could not load: ${check.error}"
        check.titles == null -> "Checking…"
        check.titles == 0 && draft.source.provider == HomeWidgets.LIST -> "Empty for now. It appears on Home once the list has titles."
        check.titles == 0 -> "No titles found"
        else -> "${check.titles} title" + (if (check.titles == 1) "" else "s") + " found"
    }
}

@Composable
internal fun WidgetEditorDialog(vm: HomeViewModel, initial: HomeWidget, close: () -> Unit) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var overlay by remember { mutableStateOf<String?>(null) }
    var panel by remember(initial.id) { mutableStateOf(WidgetSteps.kindOf(initial.source).takeIf { it == WidgetSteps.LISTS || it == WidgetSteps.MDB }) }
    var message by remember { mutableStateOf<String?>(null) }
    val existing = remember(initial.id) { vm.widgets.list.any { it.id == initial.id } }
    var step by remember(initial.id) { mutableStateOf(WidgetSteps.first(existing)) }
    var check by remember { mutableStateOf(WidgetSteps.Check()) }
    var titles by remember { mutableStateOf<List<Meta>>(emptyList()) }
    var retry by remember { mutableIntStateOf(0) }
    val stepFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }
    var focusNext by remember { mutableStateOf(false) }

    // The source is loaded as soon as it is chosen, so an empty or broken one shows before moving on.
    LaunchedEffect(draft.source, retry) {
        check = WidgetSteps.Check(); titles = emptyList()
        if (draft.source == CollectionSource() || HomeWidgets.error(draft.copy(title = "x")) != null) return@LaunchedEffect
        if (draft.source.provider == HomeWidgets.LIST) { titles = vm.widgetItems(draft); check = WidgetSteps.Check(titles.size) }
        else vm.previewWidget(draft.source).fold({ titles = it; check = WidgetSteps.Check(it.size) },
            { check = WidgetSteps.Check(error = app.ember.tv.state.WidgetsState.reason(it)) })
    }

    fun chosen(source: CollectionSource, label: String? = null) {
        // A new source renames a widget still named after the old one.
        val auto = draft.title.isBlank() || draft.title == draft.source.title.orEmpty().take(100)
        draft = WidgetDrafts.withSource(if (auto) draft.copy(title = "") else draft, source, label); message = null; focusNext = true
    }
    fun go(to: String) {
        if (WidgetSteps.open(to, draft, check, existing)) { step = to; message = null }
        else message = WidgetSteps.blocker(draft, check)?.let { (id, why) -> "Finish ${WidgetSteps.name(id)} first: $why" }
    }
    fun save() {
        WidgetSteps.blocker(draft, check)?.let { (id, why) -> step = id; message = why; return }
        message = vm.widgets.save(draft)
        if (message == null) { vm.refreshWidgets(); close() }
    }

    when (overlay) {
        WidgetSteps.CATALOG -> { CollectionSourcePicker(vm, onChoose = { chosen(it); overlay = null }, close = { overlay = null }); return }
        WidgetSteps.TRAKT -> { TraktListPicker(vm, onChoose = { chosen(it); overlay = null }, close = { overlay = null }); return }
        // #181: Discover's own filter panel, opened in place.
        WidgetSteps.FILTERS -> {
            ExploreFilters(draft.source.exploreQuery ?: app.ember.tv.discover.ExploreQuery(), vm.watch.watchRegion, vm.exploreRepository,
                onApply = { query -> chosen(WidgetDrafts.filters(query, vm.profiles.profileId)); overlay = null }, onClose = { overlay = null })
            return
        }
    }

    MediaDialog(if (existing) "Edit widget" else "New widget", close, resizeForKeyboard = true, wide = true, scrollBody = false, initialFocus = stepFocus) {
        StepTracker(step, draft, check, existing, ::go)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberMenuScrollState()).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(WidgetSteps.heading(step), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Fg)
                Text(WidgetSteps.intro(step), fontSize = 13.sp, color = Mute)
            }
            when (step) {
                WidgetSteps.SOURCE -> SourceStep(vm, draft, check, titles, panel, stepFocus,
                    onKind = { kind -> message = null; if (kind == WidgetSteps.LISTS || kind == WidgetSteps.MDB) panel = kind else { panel = null; overlay = kind } },
                    onChoose = ::chosen, onRetry = { retry++ }, onProblem = { message = it })
                WidgetSteps.STYLE -> LookStep(draft, stepFocus) { draft = it }
                WidgetSteps.DETAILS -> {
                    Text(stringResource(R.string.tx_name), color = Mute, fontSize = 13.sp)
                    TvTextField(draft.title, { draft = draft.copy(title = it.take(100)); message = null }, Modifier.width(560.dp).testTag("widget_title"),
                        placeholder = "e.g. Weekend movies", focusRequester = stepFocus)
                    StepSlider("Titles to show", WidgetDrafts.limits, draft.limit, { "$it" }) { n -> draft = draft.copy(limit = n) }
                    Note("Home shows up to this many titles in the widget. The source has " + (check.titles?.toString() ?: "some") + ".")
                }
                else -> ReviewStep(vm, draft, check, titles, stepFocus, ::go)
            }
        }
        // The way on: what is missing, then Back and Next, or Save on the last step.
        val problem = WidgetSteps.problem(step, draft, check)
        val shown = message ?: problem?.takeIf { step != WidgetSteps.SOURCE || draft.source != CollectionSource() }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip(if (WidgetSteps.previous(step) == null || existing && step == WidgetSteps.REVIEW) "Cancel" else "Back", modifier = Modifier.testTag("widget_back")) {
                val back = WidgetSteps.previous(step)
                if (back == null || existing && step == WidgetSteps.REVIEW) close() else { step = back; message = null }
            }
            Text(shown.orEmpty(), Modifier.weight(1f), color = if (shown != null && shown != "Checking the source…") Color(0xFFFFB86B) else Mute,
                fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (WidgetSteps.showsSave(step, existing)) {
                if (existing && step != WidgetSteps.REVIEW) WizardButton("Next: " + WidgetSteps.name(WidgetSteps.next(step)!!), problem == null, Modifier.focusRequester(nextFocus)) {
                    if (problem == null) go(WidgetSteps.next(step)!!) else message = problem
                }
                WizardButton(if (existing) "Save changes" else "Save widget", WidgetSteps.blocker(draft, check) == null,
                    (if (existing && step != WidgetSteps.REVIEW) Modifier else Modifier.focusRequester(nextFocus)).testTag("widget_save")) { save() }
            } else {
                val next = WidgetSteps.next(step)!!
                WizardButton("Next: " + WidgetSteps.name(next), problem == null, Modifier.focusRequester(nextFocus).testTag("widget_next")) {
                    if (problem == null) go(next) else message = problem
                }
            }
        }
    }
    // A new step starts at its first control; a chosen source puts focus on the way on.
    LaunchedEffect(step) { withFrameNanos { }; runCatching { stepFocus.requestFocus() } }
    LaunchedEffect(focusNext) { if (focusNext) { withFrameNanos { }; runCatching { nextFocus.requestFocus() }; focusNext = false } }
}

/** The steps across the top: done ones ticked, the current one filled, ones not yet open dimmed. */
@Composable
private fun StepTracker(step: String, draft: HomeWidget, check: WidgetSteps.Check, existing: Boolean, go: (String) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        WidgetSteps.all.forEachIndexed { i, (id, name) ->
            val current = id == step
            val open = WidgetSteps.open(id, draft, check, existing)
            val done = !current && WidgetSteps.problem(id, draft, check) == null && (existing || i < WidgetSteps.index(step))
            if (i > 0) Box(Modifier.width(28.dp).height(2.dp).background(if (open) accent.copy(alpha = .6f) else Mute.copy(alpha = .3f)))
            Focusable(onClick = { go(id) }, modifier = Modifier.semantics { this.selected = current }.testTag("widget_step_$id"), radius = TvTokens.Shape.Pill,
                container = if (current) WashHi else Color.Transparent, focusedContainer = PillColor, focusedContent = OnPillColor, outline = false,
                focusedScale = TvTokens.Focus.Control) {
                Row(Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(26.dp).background(when { current || done -> accent; open -> Wash; else -> Color.Transparent }, RoundedCornerShape(50))
                        .then(if (!open) Modifier.border(1.dp, Mute.copy(alpha = .5f), RoundedCornerShape(50)) else Modifier), contentAlignment = Alignment.Center) {
                        if (done) MaterialGlyph(Icons.Outlined.Check, MaterialTheme.colorScheme.onPrimary, 16.dp)
                        else Text("${i + 1}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = if (current) MaterialTheme.colorScheme.onPrimary else LocalContentColor.current.copy(alpha = if (open) 1f else .5f))
                    }
                    Text(name, fontSize = 15.sp, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                        color = LocalContentColor.current.copy(alpha = if (open) 1f else .5f))
                }
            }
        }
    }
}

/** Next or Save: filled when it can be used; dimmed, and explaining why, when it cannot. */
@Composable
private fun WizardButton(label: String, ready: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Focusable(onClick = onClick, modifier = modifier, radius = TvTokens.Shape.Pill, container = if (ready) accent else Wash,
        focusedContainer = PillColor, focusedContent = OnPillColor, outline = false, focusedScale = TvTokens.Focus.Control) {
        Text(label, Modifier.padding(horizontal = 22.dp, vertical = 10.dp), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            color = if (ready) MaterialTheme.colorScheme.onPrimary else LocalContentColor.current.copy(alpha = .6f))
    }
}

@Composable
private fun SourceStep(vm: HomeViewModel, draft: HomeWidget, check: WidgetSteps.Check, titles: List<Meta>, panel: String?, first: FocusRequester,
    onKind: (String) -> Unit, onChoose: (CollectionSource, String?) -> Unit, onRetry: () -> Unit, onProblem: (String?) -> Unit) {
    val kind = WidgetSteps.kindOf(draft.source)
    ButtonRow(spacing = 10.dp) {
        WidgetSteps.kinds.forEachIndexed { i, (id, text) ->
            SourceKindCard(id, text.first, text.second, kind == id || panel == id && kind == null,
                (if (i == 0) Modifier.focusRequester(first) else Modifier).testTag(if (id == WidgetSteps.FILTERS) "widget_filters" else "widget_kind_$id")) { onKind(id) }
        }
    }
    when (panel) {
        WidgetSteps.LISTS -> {
            Text(stringResource(R.string.tx_choose_a_list), color = Mute, fontSize = 13.sp)
            ButtonRow { vm.widgetLists().take(18).forEach { (id, label) ->
                Chip(label, draft.source.provider == HomeWidgets.LIST && draft.source.catalogId == id, maxLines = 1) {
                    onChoose(CollectionSource(provider = HomeWidgets.LIST, catalogId = id, title = label), label)
                }
            } }
        }
        WidgetSteps.MDB -> {
            Text(stringResource(R.string.tx_mdblist_list), color = Mute, fontSize = 13.sp)
            var mdb by remember { mutableStateOf(if (draft.source.provider == HomeWidgets.MDBLIST) draft.source.catalogId.orEmpty() else "") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvTextField(mdb, { mdb = it.take(200) }, Modifier.width(520.dp), placeholder = "MDBList link, user/list-name or number")
                Chip("Use this list", draft.source.provider == HomeWidgets.MDBLIST) {
                    val (problem, source) = WidgetDrafts.mdblist(mdb, ApiKeys.get("MDBLIST_API_KEY").isNotBlank())
                    if (source != null) onChoose(source, null) else onProblem(problem)
                }
            }
        }
    }
    if (draft.source != CollectionSource()) ChosenSource(vm, draft, check, titles, onRetry)
}

/** One kind of source: an emblem, its name and what it covers. */
@Composable
private fun SourceKindCard(kind: String, name: String, about: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val icon: ImageVector = when (kind) {
        WidgetSteps.FILTERS -> Icons.Outlined.Tune; WidgetSteps.LISTS -> Icons.Outlined.Bookmarks
        WidgetSteps.CATALOG -> Icons.Outlined.Widgets; WidgetSteps.TRAKT -> Icons.Outlined.PlaylistPlay
        else -> Icons.Outlined.FormatListNumbered
    }
    Focusable(onClick = onClick, modifier = modifier.width(172.dp).height(128.dp).semantics { this.selected = on }
        .then(if (on) Modifier.border(2.dp, accent, RoundedCornerShape(TvTokens.Shape.Card.dp)) else Modifier),
        radius = TvTokens.Shape.Card, container = if (on) WashHi else PanelHi, focusedContainer = PillColor, focusedContent = OnPillColor,
        outline = false, focusedScale = TvTokens.Focus.Card) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(34.dp).background(accent.copy(alpha = .2f), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                    MaterialGlyph(icon, accent, 20.dp)
                }
                Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(about, fontSize = 12.sp, lineHeight = 15.sp, color = LocalContentColor.current.copy(alpha = .7f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (on) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(20.dp).background(accent, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                MaterialGlyph(Icons.Outlined.Check, MaterialTheme.colorScheme.onPrimary, 14.dp)
            }
        }
    }
}

/** The chosen source, what loading it found, and its first posters. */
@Composable
private fun ChosenSource(vm: HomeViewModel, draft: HomeWidget, check: WidgetSteps.Check, titles: List<Meta>, onRetry: () -> Unit) {
    val ok = WidgetSteps.sourceProblem(draft, check) == null
    val accent = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(TvTokens.Shape.Card.dp)).background(Wash).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(28.dp).background(if (ok) accent else Color(0x33FFB86B), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                MaterialGlyph(if (ok) Icons.Outlined.Check else if (check.titles == null && check.error == null) Icons.Outlined.HourglassEmpty else Icons.Outlined.ErrorOutline,
                    if (ok) MaterialTheme.colorScheme.onPrimary else Color(0xFFFFB86B), 16.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(HomeWidgets.sourceLabel(draft.source, vm.allCatalogs), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(WidgetSteps.status(draft, check), fontSize = 13.sp, color = Mute)
            }
            if (check.error != null) Chip("Try again") { onRetry() }
        }
        if (titles.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            titles.take(9).forEach { PosterImage(it, Modifier.width(62.dp).height(93.dp).clip(RoundedCornerShape(6.dp)).background(PanelHi)) }
        }
    }
}

@Composable
private fun LookStep(draft: HomeWidget, first: FocusRequester, change: (HomeWidget) -> Unit) {
    // #172: each style as a small picture of itself.
    ButtonRow { HomeWidgets.styles.forEachIndexed { i, (value, name) ->
        WidgetStyleTile(value, name, draft.style == value, if (i == 0) Modifier.focusRequester(first) else Modifier) { change(draft.copy(style = value)) }
    } }
    // #195: a row is posters or wide cards, shown as pictures. #196: a panel chooses its artwork.
    if (HomeWidgets.isPanel(draft.style)) {
        Text(stringResource(R.string.ui_artwork), color = Mute, fontSize = 13.sp)
        ButtonRow { HomeWidgets.arts.forEach { (value, name) -> Chip(name, draft.art == value) { change(draft.copy(art = value)) } } }
        Note(if (draft.art == HeroArtworkMode.FANART) "Prefers Fanart backgrounds and logos. Add TMDB and Fanart API keys in Settings > Accounts > API keys; missing images fall back to standard artwork."
            else "Standard TMDB and catalog backgrounds and logos.")
    } else {
        Text(stringResource(R.string.tx_card_shape), color = Mute, fontSize = 13.sp)
        ButtonRow { HomeWidgets.shapes.forEach { (value, name) -> CardShapeTile(value, name, draft.shape == value) { change(draft.copy(shape = value)) } } }
    }
}

/** The last step: every choice with a way back to it, and the widget drawn with its real titles. */
@Composable
private fun ReviewStep(vm: HomeViewModel, draft: HomeWidget, check: WidgetSteps.Check, titles: List<Meta>, first: FocusRequester, go: (String) -> Unit) {
    val look = HomeWidgets.styleName(draft.style) + if (HomeWidgets.isPanel(draft.style)) "" else " · " + (HomeWidgets.shapes.firstOrNull { it.first == draft.shape }?.second ?: draft.shape)
    listOf(
        Triple(WidgetSteps.SOURCE, "Content", HomeWidgets.sourceLabel(draft.source, vm.allCatalogs) + " · " + WidgetSteps.status(draft, check)),
        Triple(WidgetSteps.STYLE, "Look", look),
        Triple(WidgetSteps.DETAILS, "Name & size", draft.title.ifBlank { "No name yet" } + " · up to ${draft.limit} titles"),
    ).forEachIndexed { i, (id, label, value) ->
        Focusable(onClick = { go(id) }, modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier).testTag("widget_change_$id"),
            radius = TvTokens.Shape.Card, container = Wash, focusedContainer = PillColor, focusedContent = OnPillColor, outline = false, focusedScale = TvTokens.Focus.Inline) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, Modifier.width(130.dp), fontSize = 13.sp, color = LocalContentColor.current.copy(alpha = .7f))
                Text(value, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.tx_change), fontSize = 13.sp, color = LocalContentColor.current.copy(alpha = .7f))
            }
        }
    }
    Text(stringResource(R.string.tx_on_home), color = Mute, fontSize = 13.sp)
    WidgetPreview(draft, check, titles)
}

/**
 * The editor's preview (#182): the source's first titles drawn in the chosen style, under the
 * widget's name, so what lands on Home shows before saving. Nothing here takes focus.
 */
@Composable
private fun WidgetPreview(draft: HomeWidget, check: WidgetSteps.Check, titles: List<Meta>) {
    val shown = titles.take(draft.limit)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(TvTokens.Shape.Card.dp)).background(Brush.verticalGradient(listOf(PanelHi, Wash))).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(draft.title.ifBlank { "Widget name" }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Fg)
        when {
            check.error != null -> Text(stringResource(R.string.tx_could_not_load, check.error), fontSize = 13.sp)
            check.titles == null -> Text(stringResource(R.string.tx_loading), color = Mute, fontSize = 13.sp)
            shown.isEmpty() -> Text(WidgetSteps.status(draft, check), fontSize = 13.sp, color = Mute)
            else -> {
                val wide = draft.shape == "wide"
                val few = shown.take(WidgetDrafts.previewCount(draft.style, wide))
                when (draft.style) {
                    HomeWidgets.CAROUSEL, HomeWidgets.HERO -> Box(Modifier.width(420.dp).height(236.dp).clip(RoundedCornerShape(10.dp)).background(PanelHi).testTag("widget_preview")) {
                        LandscapeImage(few.first(), Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.5f to Color.Transparent, 1f to Color(0xE6000000))))
                        Text(few.first().name, Modifier.align(if (draft.style == HomeWidgets.HERO) Alignment.BottomStart else Alignment.BottomCenter).padding(12.dp),
                            fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    else -> Row(Modifier.testTag("widget_preview"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                        few.forEachIndexed { i, meta ->
                            if (draft.style == HomeWidgets.NUMBERED) Text("${i + 1}", fontSize = 30.sp, lineHeight = 30.sp, fontWeight = FontWeight.Black, color = Mute)
                            val shape = Modifier.clip(RoundedCornerShape(8.dp)).background(PanelHi)
                            if (wide) LandscapeImage(meta, Modifier.width(150.dp).height(84.dp).then(shape))
                            else PosterImage(meta, Modifier.width(80.dp).height(120.dp).then(shape))
                        }
                    }
                }
                Text(if (shown.size == 1) "1 title" else "${shown.size} titles, the first ${few.size} shown", color = Mute, fontSize = 12.sp)
            }
        }
    }
}
