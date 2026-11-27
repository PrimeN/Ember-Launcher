package app.ember.tv.ui

import androidx.compose.ui.res.stringResource
import app.ember.tv.R

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.data.*
import kotlinx.serialization.json.JsonPrimitive
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.platform.testTag

@Composable
internal fun CollectionSourceEditor(vm: HomeViewModel, initial: CollectionSource, close: () -> Unit, save: (CollectionSource) -> Unit) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var options by remember { mutableStateOf(false) }
    var replacement by remember { mutableStateOf(false) }
    var discover by remember { mutableStateOf(false) }
    val def = vm.collectionCatalogs.firstOrNull { Collections.matches(it, draft) }
    if (replacement) CollectionSourcePicker(vm, onChoose = { draft = it.copy(title = draft.title ?: it.title, extensionFields = initial.extensionFields + it.extensionFields); replacement = false }, close = { replacement = false })
    else if (discover && draft.exploreQuery != null) ExploreFilters(draft.exploreQuery!!, vm.watch.watchRegion, vm.exploreRepository,
        onApply = { draft = draft.copy(exploreQuery = it); discover = false }, onClose = { discover = false })
    else if (options && def != null) CatalogOptions(def, vm.collections.sourceArguments(draft).getOrDefault(emptyMap()) + listOfNotNull(draft.genre?.let { "genre" to it }).toMap(), { options = false }) { values ->
        draft = draft.copy(genre = values["genre"].orEmpty().ifBlank { null }, extraArgs = values, secretArgsRef = null); options = false
    } else MediaDialog("Edit source", close, resizeForKeyboard = true, wide = true, scrollBody = false) {
        // #468: headed parts, choices as cards, the editors' footer.
        fun check(): String? {
            var e = Collections.inspect(Collections.encode(listOf(MediaCollection("draft", "Draft", folders = listOf(CollectionFolder("f", "Folder", sources = listOf(draft))))))).error
            if (e == null && def != null) e = vm.collections.sourceArguments(draft).fold({ def.extraError(it + listOfNotNull(draft.genre?.let { "genre" to it }).toMap()) }, { "Restore private source settings or choose a replacement." })
            return e
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberMenuScrollState()).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            EditorHeading("Name", "Shown as the folder's tab for this source.")
            TvTextField(draft.title.orEmpty(), { draft = draft.copy(title = it.take(100)) }, Modifier.width(560.dp), placeholder = "Source name")
            EditorHeading("Source", "OK on it to choose another.")
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ChoiceCard(Collections.label(draft, vm.allCatalogs), when {
                    draft.provider == "addon" && def == null -> stringResource(R.string.tx_this_catalog_needs_an_installed_enabled)
                    draft.provider == "addon" -> def!!.addon.name
                    else -> CollectionEditing.sourceAbout(draft)
                }, false, width = 300.dp, icon = CollectionEditing.sourceIcon(draft)) { replacement = true }
                if (draft.provider == "addon" && def != null && def.extras.isNotEmpty()) ChoiceCard("Catalog filters", "Genre and the catalog's other options", false, width = 260.dp,
                    icon = Icons.Outlined.FilterList) {
                    val args = vm.collections.sourceArguments(draft); error = args.exceptionOrNull()?.let { "Private source settings are missing. Restore an Ember backup or choose a replacement." }
                    if (args.isSuccess) options = true
                }
                if (draft.provider == "ember") ChoiceCard("Discover filters", "What the saved search finds", false, width = 260.dp, icon = Icons.Outlined.Tune) { discover = true }
            }
            if (draft.provider == "tmdb" || draft.provider == "trakt") {
                val isTmdb = draft.provider == "tmdb"
                var id by remember { mutableStateOf((if (isTmdb) draft.tmdbId?.toLong() else draft.traktListId)?.toString().orEmpty()) }
                TvTextField(id, { id = it; val n = CollectionDrafts.numericId(it); draft = if (isTmdb) draft.copy(tmdbId = n?.takeIf { it <= Int.MAX_VALUE }?.toInt()) else draft.copy(traktListId = n) },
                    Modifier.width(560.dp), placeholder = "List, collection or person ID or link")
                if (isTmdb) {
                    EditorLabel("Kind")
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        (SourceKinds.tmdb + Triple("DISCOVER", "Discover", Icons.Outlined.Explore)).forEach { (kind, label, icon) ->
                            ChoiceCard(label, null, draft.tmdbSourceType == kind, width = 170.dp, icon = icon) {
                                draft = draft.copy(tmdbSourceType = kind, mediaType = if (kind == "COLLECTION") "MOVIE" else if (kind == "NETWORK") "TV" else draft.mediaType)
                            }
                        }
                    }
                }
                if (draft.tmdbSourceType !in setOf("COLLECTION", "NETWORK")) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChoiceCard("Movies", null, !draft.mediaType.equals("tv", true), width = 170.dp, icon = Icons.Outlined.Movie) { draft = draft.copy(mediaType = "MOVIE") }
                    ChoiceCard("Shows", null, draft.mediaType.equals("tv", true), width = 170.dp, icon = Icons.Outlined.Tv) { draft = draft.copy(mediaType = "TV") }
                }
                val sorts = if (!isTmdb) listOf("rank" to "List order", "title" to "Title", "added" to "Recently added")
                    else if (draft.tmdbSourceType !in setOf("LIST", "COLLECTION")) listOf("popularity.desc" to "Popular", "vote_average.desc" to "Highest rated", "primary_release_date.desc" to "Newest")
                    else emptyList()
                if (sorts.isNotEmpty()) {
                    EditorLabel("Order")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        sorts.forEach { (sort, label) -> ChoiceCard(label, null, draft.sortBy == sort, width = 190.dp, icon = Icons.Outlined.Sort) { draft = draft.copy(sortBy = sort) } }
                    }
                }
                if (isTmdb && draft.tmdbSourceType !in setOf("LIST", "COLLECTION")) {
                    EditorHeading(stringResource(R.string.search_filters_button), "Optional: narrow what TMDB returns.")
                    val kind = if (draft.mediaType.equals("tv", true)) app.ember.tv.discover.ExploreKind.TV else app.ember.tv.discover.ExploreKind.MOVIE
                    val genres = (draft.filters?.get("withGenres") as? JsonPrimitive)?.content.orEmpty().split(',', '|').mapNotNull { it.toIntOrNull() }.toSet()
                    ButtonRow(spacing = 8.dp) { app.ember.tv.discover.ExploreGenres.all.filter { it.ids(kind).isNotEmpty() }.forEach { genre ->
                        val ids = genre.ids(kind); val selected = ids.any { it in genres }
                        Chip(genre.label, selected) {
                            val changed = if (selected) genres - ids else genres + ids
                            draft = draft.copy(filters = draft.filters.orEmpty() + ("withGenres" to JsonPrimitive(changed.sorted().joinToString(","))))
                        }
                    } }
                    listOf("year" to "Release year", "voteAverageGte" to "Minimum rating (0–10)", "withOriginalLanguage" to "Original language (for example en)", "watchRegion" to "Streaming region (for example CA)").forEach { (key, label) ->
                        TvTextField(draft.filters?.get(key)?.let { (it as? JsonPrimitive)?.content }.orEmpty(), { input ->
                            val current = draft.filters.orEmpty(); draft = draft.copy(filters = if (input.isBlank()) current - key else current + (key to JsonPrimitive(input)))
                        }, Modifier.width(560.dp), placeholder = label)
                    }
                }
            }
        }
        EditorFooter("Cancel", close, error) {
            PrimaryAction("Save source", modifier = Modifier.testTag("source_save")) { error = check(); if (error == null) save(draft) }
        }
    }
}
