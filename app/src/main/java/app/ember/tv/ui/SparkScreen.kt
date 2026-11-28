package app.ember.tv.ui

import app.ember.tv.unfinished
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.ember.tv.data.mediaKey
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.isSaved
import app.ember.tv.data.Meta

/** #435 Spark in Discover: what it has learned, then its named rows of picks, each with a reason. */
@Composable
internal fun SparkScreen(vm: HomeViewModel, onOpen: (Meta) -> Unit) {
    val spark = vm.spark
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = TvTokens.Space.Page.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item(key = "spark-head") {
            val learned = spark.summary
            Text(
                when {
                    !app.ember.tv.tracker.Tmdb.available -> ""
                    learned != null -> "Spark has noticed you like $learned" +
                        (if (spark.builtAt > 0) " · picked ${app.ember.tv.data.DisplayDates.dayTime(spark.builtAt)}" else "") +
                        (if (spark.loading) " · looking for more…" else "")
                    spark.loading -> "Spark is looking through what you've watched…"
                    else -> ""
                },
                color = Mute, fontSize = TvTokens.Type.Caption.sp, maxLines = 2,
            )
        }
        // #452–#454: moods, quick watch, couch mode, what Spark knows, and pick five for a new profile.
        if (app.ember.tv.tracker.Tmdb.available) item(key = "spark-tools") { SparkTools(vm) }
        if (spark.couchName != null) item(key = "spark-couch") {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Watching with ${spark.couchName}" + if (spark.couchLoading) " · finding picks for you both…" else "",
                    fontSize = TvTokens.Type.Caption.sp, color = Mute)
                spark.couchNote?.let { Text(it, color = Mute, fontSize = TvTokens.Type.Caption.sp, maxLines = 2) }
            }
        }
        items(spark.couchRows, key = { it.id }) { row ->
            ModernSection(row.title, "For you both") { MediaRow(vm.unfinished(row.items), wide = false, isSaved = { vm.isSaved(it) }, onOpen = onOpen) }
        }
        // #438: Ask Spark, with your own AI service.
        if (app.ember.tv.tracker.Tmdb.available) item(key = "spark-ask") { SparkAskPanel(vm, onOpen) }
        items(spark.asks, key = { "ask-" + it.id }) { a ->
            ModernSection(a.row.title, "Saved · asked again weekly") {
                MediaRow(vm.unfinished(a.row.items), wide = false, isSaved = { vm.isSaved(it) }, onOpen = onOpen)
            }
        }
        // #436: what Spark learns from, and bringing in your trackers' whole history.
        if (app.ember.tv.tracker.Tmdb.available) item(key = "spark-history") { SparkHistoryPanel(vm) }
        when {
            !app.ember.tv.tracker.Tmdb.available -> item(key = "spark-key") {
                RecoveryPanel("Spark needs a TMDB key", "Spark finds titles like the ones you watch through TMDB. Add a free TMDB key to turn it on.", "API keys") { vm.openSettingsPage("KEYS") }
            }
            spark.rows.isEmpty() && spark.loading -> item(key = "spark-loading") { LoadingCards(false) }
            spark.rows.isEmpty() && spark.error != null -> item(key = "spark-error") {
                RecoveryPanel("Spark couldn't make picks", spark.error.orEmpty(), "Try again") { spark.refresh() }
            }
            spark.rows.isEmpty() -> item(key = "spark-empty") {
                var picking by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                RecoveryPanel("Spark gets to know you as you watch",
                    "Play a few films or shows on this profile, connect Trakt or another tracker, or pick five titles you love, and Spark will pick titles for you here. It learns on this TV and keeps nothing anywhere else.",
                    "Pick five you love") { picking = true }
                if (picking) PickFiveDialog(vm) { picking = false }
            }
        }
        // #454: a mood or Quick watch narrows every row; a row left with too few titles steps aside.
        val shown = spark.rows.filter { !spark.quickOnly || it.id == "quick" }
            .map { r -> if (spark.mood == app.ember.tv.spark.SparkMood.ANY) r else r.copy(items = r.items.filter(spark.mood::matches)) }
            .filter { it.items.size >= (if (spark.mood == app.ember.tv.spark.SparkMood.ANY) 1 else 4) }
        if (spark.rows.isNotEmpty() && shown.isEmpty()) item(key = "spark-none") {
            Text(if (spark.quickOnly) "No quick watches yet. Spark finds them in your favourite kinds of film." else "Nothing ${spark.mood.label.lowercase()} in today's picks. Try another mood, or Fresh picks.",
                color = Mute, fontSize = TvTokens.Type.Caption.sp)
        }
        items(shown, key = { it.id }) { row ->
            ModernSection(row.title, row.subtitle) {
                MediaRow(vm.unfinished(row.items), wide = false, isSaved = { vm.isSaved(it) }, onOpen = onOpen)
            }
        }
    }
}

@Composable
internal fun SparkHistoryPanel(vm: HomeViewModel) {
    val spark = vm.spark
    val sources = androidx.compose.runtime.remember(vm.profiles.profileId) { if (vm.profiles.personalOn) vm.hub.historySources().map { it.name } else emptyList() }
    val got = spark.imported
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (got != null) "Spark learns from this TV and your history from ${got.sources.joinToString(" and ")}: " +
                "%,d titles".format(got.titles.size) + (if (got.rated > 0) ", %,d ratings".format(got.rated) else "") +
                (if (got.detailed > 0) ", full details kept" else "") +
                " · brought in ${app.ember.tv.data.DisplayDates.dayMonth(got.at)}" +
                (if (spark.autoRefresh != "off") " · refreshes ${spark.autoRefresh}" else "")
            else if (sources.isNotEmpty()) "Spark learns from what you play on this TV. Bring in your whole ${sources.joinToString(" and ")} history and ratings for better picks right away."
            else "Spark learns from what you play on this TV. Connect Trakt, Simkl or WeTrakr to give it your whole history.",
            color = Mute, fontSize = TvTokens.Type.Caption.sp, maxLines = 2,
        )
        ButtonRow(spacing = 10.dp) {
            if (sources.isNotEmpty()) Chip(when {
                spark.importing -> "Bringing in your history…"
                got != null -> "Update from ${sources.joinToString(" and ")}"
                else -> "Bring in my ${sources.joinToString(" and ")} history"
            }) { spark.importHistory() }
            else Chip("Connect a tracker") { vm.openSettingsPage("TRACKERS") }
            if (got != null) Chip("Forget it") { spark.forgetImported() }
        }
        spark.importNote?.let { Text(it, color = Mute, fontSize = TvTokens.Type.Caption.sp, maxLines = 2) }
    }
}

@Composable
private fun SparkAskPanel(vm: HomeViewModel, onOpen: (Meta) -> Unit) {
    val spark = vm.spark
    @Suppress("UNUSED_VARIABLE") val rev = vm.keysRev + app.ember.tv.data.AiSettings.revision
    val ready = app.ember.tv.data.AiSettings.ready()
    var question by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!ready) {
            Text("Ask Spark for anything, like \u201Cfilms like Inception, but without the dreaming\u201D. Choose an AI service first; several are free.",
                color = Mute, fontSize = TvTokens.Type.Caption.sp, maxLines = 2)
            Chip("Set up AI") { vm.openSettingsPage("DISCOVER") }
            return@Column
        }
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TvTextField(question, { question = it.take(300) }, Modifier.weight(1f), placeholder = "Ask Spark: films like Inception, but without the dreaming",
                onSubmit = { spark.ask(question) })
            Chip(if (spark.asking) "Asking…" else "Ask") { spark.ask(question) }
        }
        // You see what gets sent before anything goes.
        Text("Sends your question" + (spark.summary?.let { " and \u201C$it\u201D" } ?: "") + " to ${app.ember.tv.data.AiSettings.provider.label}. Titles are checked on TMDB; only real ones you haven't seen show.",
            color = Mute, fontSize = TvTokens.Type.Caption.sp, maxLines = 2)
        spark.askError?.let { Text(it, color = Mute, fontSize = TvTokens.Type.Caption.sp) }
        spark.answer?.takeIf { it.items.isNotEmpty() }?.let { row ->
            ModernSection(row.title, row.subtitle) { MediaRow(vm.unfinished(row.items), wide = false, isSaved = { vm.isSaved(it) }, onOpen = onOpen) }
            ButtonRow(spacing = 10.dp) {
                Chip("Save as a row") { spark.saveAnswer() }
                Chip("Clear") { spark.clearAnswer() }
            }
        }
        if (spark.asks.isNotEmpty()) ButtonRow(spacing = 10.dp) {
            spark.asks.forEach { a -> Chip("Remove \u201C${a.question.take(24)}\u201D") { spark.removeAsk(a.id) } }
        }
    }
}

/** #452–#454: moods, Quick watch, couch mode, what Spark knows, and pick five while Spark is still learning. */
@Composable
private fun SparkTools(vm: HomeViewModel) {
    val spark = vm.spark
    var couchMenu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var taste by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var picking by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val others = vm.profiles.all.filter { it.id != vm.profiles.profileId }
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ButtonRow(spacing = 10.dp) {
            app.ember.tv.spark.SparkMood.entries.forEach { m -> Chip(m.label, spark.mood == m) { spark.mood = m } }
            Chip("Quick watch", spark.quickOnly) { spark.quickOnly = !spark.quickOnly }
        }
        ButtonRow(spacing = 10.dp) {
            if (others.isNotEmpty()) Chip(spark.couchName?.let { "Watching with $it · Stop" } ?: "Watching with…", spark.couchName != null) {
                if (spark.couchName != null) spark.stopCouch() else couchMenu = true
            }
            Chip("What Spark knows") { taste = true }
            if (spark.votes.values.count { it.vote == app.ember.tv.spark.SparkVote.LOVE } < 5) Chip("Pick five you love") { picking = true }
        }
    }
    if (couchMenu) OptionMenu("Watching with", others.map { p -> MenuOption(p.name) { spark.startCouch(p.id, p.name) } }) { couchMenu = false }
    if (taste) SparkTasteDialog(vm) { taste = false }
    if (picking) PickFiveDialog(vm) { picking = false }
}

/** #452: well-known films and shows to choose five you love from, so a new profile gets picks right away. */
@Composable
internal fun PickFiveDialog(vm: HomeViewModel, close: () -> Unit) {
    val spark = vm.spark
    androidx.compose.runtime.LaunchedEffect(Unit) { spark.loadStarters() }
    val picked = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<String>() }
    TvDialog(close, width = 900.dp, height = 560.dp, scrollContent = true) {
        Text("Pick five you love", fontSize = 24.sp)
        Text("Choose at least five films or shows you love. Spark starts from these, and gets better as you watch. Chosen: ${picked.size}",
            color = Mute, fontSize = TvTokens.Type.Caption.sp, maxLines = 2)
        if (spark.startersLoading) LoadingCards(false)
        spark.starters.chunked(6).forEach { line ->
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                line.forEach { m ->
                    val on = m.mediaKey() in picked
                    Focusable(onClick = { if (on) picked.remove(m.mediaKey()) else picked.add(m.mediaKey()) },
                        modifier = Modifier.width(126.dp).semantics { contentDescription = m.name + if (on) ", chosen" else "" },
                        radius = TvTokens.Shape.Card, container = if (on) androidx.tv.material3.MaterialTheme.colorScheme.primary.copy(alpha = .35f) else Panel) {
                        androidx.compose.foundation.layout.Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            coil.compose.AsyncImage(m.poster, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.width(114.dp).height(171.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)))
                            Text((if (on) "\u2713 " else "") + m.name, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        ButtonRow(spacing = 10.dp) {
            Chip(if (picked.size >= 5) "Done" else "Done (${picked.size} of 5)") {
                if (picked.isNotEmpty()) spark.loveAll(spark.starters.filter { it.mediaKey() in picked })
                close()
            }
            Chip("Cancel", onClick = close)
        }
    }
}

/** #454: what Spark has learned, and the titles you voted on, each of which can be taken back. */
@Composable
private fun SparkTasteDialog(vm: HomeViewModel, close: () -> Unit) {
    val spark = vm.spark
    TvDialog(close, width = 760.dp, height = 560.dp, scrollContent = true) {
        Text("What Spark knows", fontSize = 24.sp)
        Text("From what ${vm.profile.name} plays on this TV, your trackers' history if you brought it in, and your votes. It stays on this TV.",
            color = Mute, fontSize = TvTokens.Type.Caption.sp, maxLines = 2)
        @Composable fun Bars(title: String, shares: List<Pair<String, Double>>) {
            if (shares.isEmpty()) return
            Text(title, fontSize = 17.sp, modifier = Modifier.padding(top = 8.dp))
            val top = shares.maxOf { it.second }.coerceAtLeast(.01)
            shares.forEach { (name, share) ->
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(name, fontSize = 14.sp, modifier = Modifier.width(180.dp), maxLines = 1)
                    androidx.compose.foundation.layout.Box(Modifier.width((360 * share / top).dp.coerceAtLeast(6.dp)).height(10.dp)
                        .background(androidx.tv.material3.MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.RoundedCornerShape(5.dp)))
                    Text("${(share * 100).toInt()}%", fontSize = 13.sp, color = Mute)
                }
            }
        }
        if (spark.tasteGenres.isEmpty()) Text("Nothing yet. Watch something, or pick five you love.", color = Mute, fontSize = 14.sp)
        Bars("Kinds of title", spark.tasteGenres.entries.sortedByDescending { it.value }.take(8).map { app.ember.tv.spark.SparkNames.genre(it.key) to it.value })
        Bars("Languages", spark.tasteLanguages.entries.sortedByDescending { it.value }.take(4).map { app.ember.tv.spark.SparkNames.language(it.key) to it.value })
        Bars("Decades", spark.tasteDecades.entries.sortedByDescending { it.value }.take(4).map { "${it.key}s" to it.value })
        listOf(app.ember.tv.spark.SparkVote.LOVE to "You love", app.ember.tv.spark.SparkVote.LIKE to "You like", app.ember.tv.spark.SparkVote.NOT to "Not for you").forEach { (v, title) ->
            val these = spark.votes.filterValues { it.vote == v }.entries.sortedByDescending { it.value.at }
            if (these.isNotEmpty()) {
                Text("$title · choose one to take it back", fontSize = 17.sp, modifier = Modifier.padding(top = 8.dp))
                ButtonRow(spacing = 8.dp) { these.forEach { (key, vote) -> Chip("${vote.name} \u00D7") { spark.unvote(key) } } }
            }
        }
        ButtonRow(spacing = 10.dp) {
            if (spark.votes.isNotEmpty()) Chip("Forget my votes") { spark.forgetVotes() }
            Chip("Close", onClick = close)
        }
    }
}
