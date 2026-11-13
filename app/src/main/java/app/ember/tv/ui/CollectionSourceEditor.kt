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
    } else MediaDialog("Edit source", close, resizeForKeyboard = true, wide = true) {
        TvTextField(draft.title.orEmpty(), { draft = draft.copy(title = it.take(100)) }, Modifier.fillMaxWidth(), placeholder = "Source name")
        Chip("Choose replacement source") { replacement = true }
        if (draft.provider == "addon") {
            if (def == null) Text(stringResource(R.string.tx_this_catalog_needs_an_installed_enabled), color = Mute)
            else {
                Text(def.addon.name + " · " + def.label, color = Mute)
                if (def.extras.isNotEmpty()) Chip("Edit catalog filters") {
                    val args = vm.collections.sourceArguments(draft); error = args.exceptionOrNull()?.let { "Private source settings are missing. Restore an Ember backup or choose a replacement." }
                    if (args.isSuccess) options = true
                }
            }
        } else if (draft.provider == "ember") Chip("Edit Discover filters") { discover = true }
        else {
            val isTmdb = draft.provider == "tmdb"
            var id by remember { mutableStateOf((if (isTmdb) draft.tmdbId?.toLong() else draft.traktListId)?.toString().orEmpty()) }
            TvTextField(id, { id = it; val n = CollectionDrafts.numericId(it); draft = if (isTmdb) draft.copy(tmdbId = n?.takeIf { it <= Int.MAX_VALUE }?.toInt()) else draft.copy(traktListId = n) }, Modifier.fillMaxWidth(), placeholder = "List, collection or person ID / URL")
            if (isTmdb) ButtonRow { listOf("LIST", "COLLECTION", "COMPANY", "NETWORK", "PERSON", "DIRECTOR", "DISCOVER").forEach { kind ->
                Chip(kind.lowercase().replaceFirstChar { it.uppercase() }, draft.tmdbSourceType == kind) { draft = draft.copy(tmdbSourceType = kind, mediaType = if (kind == "COLLECTION") "MOVIE" else if (kind == "NETWORK") "TV" else draft.mediaType) }
            } }
            if (draft.tmdbSourceType !in setOf("COLLECTION", "NETWORK")) ButtonRow {
                Chip("Movies", !draft.mediaType.equals("tv", true)) { draft = draft.copy(mediaType = "MOVIE") }
                Chip("Shows", draft.mediaType.equals("tv", true)) { draft = draft.copy(mediaType = "TV") }
            }
            if (!isTmdb) ButtonRow { listOf("rank" to "List order", "title" to "Title", "added" to "Recently added").forEach { (sort, label) -> Chip(label, draft.sortBy == sort) { draft = draft.copy(sortBy = sort) } } }
            if (isTmdb && draft.tmdbSourceType !in setOf("LIST", "COLLECTION")) {
                Text(stringResource(R.string.search_filters_button), fontSize = 16.sp)
                ButtonRow { listOf("popularity.desc" to "Popular", "vote_average.desc" to "Highest rated", "primary_release_date.desc" to "Newest").forEach { (sort, label) ->
                    Chip(label, draft.sortBy == sort) { draft = draft.copy(sortBy = sort) }
                } }
                val kind = if (draft.mediaType.equals("tv", true)) app.ember.tv.discover.ExploreKind.TV else app.ember.tv.discover.ExploreKind.MOVIE
                val genres = (draft.filters?.get("withGenres") as? JsonPrimitive)?.content.orEmpty().split(',', '|').mapNotNull { it.toIntOrNull() }.toSet()
                ButtonRow { app.ember.tv.discover.ExploreGenres.all.filter { it.ids(kind).isNotEmpty() }.forEach { genre ->
                    val ids = genre.ids(kind); val selected = ids.any { it in genres }
                    Chip(genre.label, selected) {
                        val changed = if (selected) genres - ids else genres + ids
                        draft = draft.copy(filters = draft.filters.orEmpty() + ("withGenres" to JsonPrimitive(changed.sorted().joinToString(","))))
                    }
                } }

                listOf("year" to "Release year", "voteAverageGte" to "Minimum rating (0–10)", "withOriginalLanguage" to "Original language (e.g. en)", "watchRegion" to "Streaming region (e.g. CA)").forEach { (key, label) ->
                    TvTextField(draft.filters?.get(key)?.let { (it as? JsonPrimitive)?.content }.orEmpty(), { input ->
                        val current = draft.filters.orEmpty(); draft = draft.copy(filters = if (input.isBlank()) current - key else current + (key to JsonPrimitive(input)))
                    }, Modifier.fillMaxWidth(), placeholder = label)
                }
            }
        }
        error?.let { Text(it, color = Mute) }
        Chip("Save source") {
            error = Collections.inspect(Collections.encode(listOf(MediaCollection("draft", "Draft", folders = listOf(CollectionFolder("f", "Folder", sources = listOf(draft))))))).error
            if (error == null && def != null) error = vm.collections.sourceArguments(draft).fold({ def.extraError(it + listOfNotNull(draft.genre?.let { "genre" to it }).toMap()) }, { "Restore private source settings or choose a replacement." })
            if (error == null) save(draft)
        }
    }
}
