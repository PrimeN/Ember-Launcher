package app.ember.tv.phone

import app.ember.tv.addAddon
import app.ember.tv.addonLabel
import app.ember.tv.pauseAddon
import app.ember.tv.saveApiKeyAndWait

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import app.ember.tv.HomeViewModel
import app.ember.tv.data.AddonAccess
import app.ember.tv.data.Backup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings > Phone setup on the TV side: what the phone may change, the log of what arrived, and
 * content changes waiting for approval on the TV. Split out of HomeViewModel; the handler reads
 * and changes the view model's state (`with(vm)`), always on Main and only while the session's
 * lease and profile are still the ones that started it.
 */
internal class PhoneSetupController(private val vm: HomeViewModel) {
    /** What arrived from the phone while the page is open, newest first. */
    var phoneLog by mutableStateOf<List<String>>(emptyList()); private set

    private fun phoneNote(line: String) {
        phoneLog = (listOf(line) + phoneLog).take(12)
    }

    fun clearPhoneLog() {
        phoneLog = emptyList()
    }

    private val contentApproval = ContentApproval()
    val pendingContent get() = contentApproval.pending
    val approvingContent get() = contentApproval.busy
    fun clearPendingContent() = contentApproval.clear()
    fun approveContent() {
        if (!approvingContent) vm.viewModelScope.launch { phoneNote(contentApproval.approve()) }
    }

    /** Recheck the lease and profile on Main before a queued phone request changes settings. */
    fun handler(access: PhoneSetupAccess): PhoneSetup.Handler {
      with(vm) {
        val owner = profiles.profileId
        fun <T> onPhoneMain(block: suspend () -> T): T = kotlinx.coroutines.runBlocking(Dispatchers.Main) {
            access.requireActive()
            check(profiles.profileId == owner) { "The active profile has changed. Start a new Phone setup session." }
            block()
        }
        fun sources(): List<Pair<String, app.ember.tv.data.CollectionSource>> = collectionCatalogs
            .map { def -> AddonAccess.key(def.key) to app.ember.tv.data.CollectionDrafts.catalog(def) } + collectionSearches.map { search ->
                AddonAccess.key("search:${search.id}:$owner") to app.ember.tv.data.CollectionDrafts.discover(search.name, search.query, owner)
            }
        fun targetKey(collection: String, folder: String? = null) = (if (folder == null) "c-" else "f-") + AddonAccess.key("${collection.length}:$collection${folder.orEmpty().length}:${folder.orEmpty()}")
        fun folderSources() = collections.list.flatMap { c -> c.folders.flatMap { f -> f.allSources.mapIndexed { index, s ->
            val id = AddonAccess.key(targetKey(c.id, f.id) + ":$index:" + collections.sourceKey(s))
            id to Triple(c, f, index)
        } } }
        fun configuredSource(id: String, fields: Map<String,String>): app.ember.tv.data.CollectionSource? {
            val source = sources().firstOrNull { it.first == id }?.second ?: return null
            val def = collectionCatalogs.firstOrNull { AddonAccess.key(it.key) == id } ?: return source
            val prefix = "extra:$id:"
            val supplied = fields.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }.filterKeys { name -> def.extras.any { it.name == name } }
            val values = def.requestExtras + supplied
            require(def.extraError(values) == null) { "Complete the required catalog filters." }
            return source.copy(extraArgs = values, genre = values["genre"]?.ifBlank { null })
        }
        fun widgetKey(id: String) = "w-" + AddonAccess.key("widget:$id")
        /** #240: any Home widget, by an opaque key. */
        fun homeKey(section: String) = "h-" + AddonAccess.key("home:$section")
        fun homeSections(): Pair<List<String>, List<String>> =
            app.ember.tv.ui.WidgetDrafts.split(sections.layout, sections.sectionInfo.map { it.id }.toSet())
        fun ownWidget(section: String) = if (app.ember.tv.data.HomeWidgets.isSection(section)) widgets.list.firstOrNull { app.ember.tv.data.HomeWidgets.section(it.id) == section } else null
        fun homeTitle(section: String) = ownWidget(section)?.title ?: sections.sectionInfo.firstOrNull { it.id == section }?.label ?: "Widget"
        fun homeEntries(): Pair<List<app.ember.tv.phone.PhoneHomeEntry>, List<app.ember.tv.phone.PhoneHomeEntry>> {
            val info = sections.sectionInfo.associateBy { it.id }
            fun entry(section: String): app.ember.tv.phone.PhoneHomeEntry {
                val w = ownWidget(section)
                val kind = when { w != null -> "yours"; app.ember.tv.state.HomeSectionsState.isCatalogSection(section) -> "catalog"; else -> "built" }
                val note = if (w != null) app.ember.tv.data.HomeWidgets.styleName(w.style) + " · " + app.ember.tv.data.HomeWidgets.sourceLabel(w.source, collectionCatalogs) else info[section]?.note.orEmpty()
                return app.ember.tv.phone.PhoneHomeEntry(homeKey(section), homeTitle(section), kind, note, app.ember.tv.ui.WidgetDrafts.picture(section, w?.style), w?.let { widgetKey(it.id) })
            }
            val (on, off) = homeSections()
            return on.map(::entry) to off.map(::entry)
        }
        fun stage(summary: String, apply: suspend () -> String?): String =
            contentApproval.stage(summary, { access.active && profiles.profileId == owner }, apply)
        val registry = PhoneSettings(vm)
        fun adultOnly() = check(!profiles.profile.kids) { "Switch to an adult profile on the TV to change settings from a phone." }
        return object : app.ember.tv.phone.PhoneSetup.Handler {
            override fun status(): PhoneStatus = onPhoneMain {
                PhoneStatus("${app.ember.tv.BuildConfig.VERSION_NAME} (build ${app.ember.tv.BuildConfig.VERSION_CODE})", listOf(
                    "Metadata key (TMDB)" to app.ember.tv.data.ApiKeys.get("TMDB_API_KEY").isNotBlank(),
                    "Catalogs and playback addons" to addons.isNotEmpty(),
                    "Personal media servers" to (plexConnected || serverAccounts.isNotEmpty()),
                    "Watch history accounts" to hub.trackers.any { it.isConnected() },
                ))
            }
            override fun settings(): List<PhoneSettingView> = onPhoneMain { adultOnly(); registry.all() }
            override fun changeSetting(id: String, value: String): PhoneSettingResult = onPhoneMain {
                runCatching { adultOnly(); registry.change(id, value) }.fold(
                    { now ->
                        val shown = now.options.firstOrNull { it.first == now.value }?.second
                            ?: when (now.value) { "true" -> "On"; "false" -> "Off"; else -> now.value }
                        phoneNote("${now.label}: $shown")
                        PhoneSettingResult(true, "Saved: $shown", now)
                    },
                    { PhoneSettingResult(false, it.message ?: "Could not change that setting.") },
                )
            }
            override fun crashLog(): List<String> {
                access.requireActive()
                return app.ember.tv.data.CrashLog.entries().map(app.ember.tv.data.CrashLog::redact)
            }
            override fun clearCrashLog(): String {
                access.requireActive()
                app.ember.tv.data.CrashLog.clear()
                onPhoneMain { phoneNote("Crash log cleared from your phone") }
                return "Crash log cleared."
            }
            override fun importPresentation(name:String,bytes:ByteArray):String = onPhoneMain {
                check(!profiles.profile.kids){"Switch to an adult profile to add presentation media."}
                require(bytes.size<=app.ember.tv.home.PresentationMedia.MAX_UPLOAD)
                require(launcherExtras.options.slides.size<40){"Maximum 40 presentation items"}
                stage("Add presentation item: ${name.take(80)}") {
                    val item=withContext(Dispatchers.IO){app.ember.tv.home.PresentationMedia.upload(getApplication<Application>(),name,bytes)}
                    try {access.requireActive();check(profiles.profileId==owner);launcherExtras.addSlide(item)}
                    catch(error:Exception){withContext(kotlinx.coroutines.NonCancellable+Dispatchers.IO){app.ember.tv.home.PresentationMedia.removeUpload(getApplication<Application>(),item)};throw error}
                    "Added presentation item."
                }
            }
            override fun addAddon(url: String): String = onPhoneMain {
                val err = vm.addAddon(url)
                (if (err == null) "Added addon: $url" else "Not added ($url): $err").also { phoneNote(it) }
            }

            override fun saveKey(id: String, value: String): String = onPhoneMain {
                val error=saveApiKeyAndWait(id,value)
                (error ?: "Saved $id").also {phoneNote(it)}
            }

            override fun addIptv(kind: String, name: String, url: String, username: String, password: String, epg: String): String = onPhoneMain {
                if (url.isBlank()) return@onPhoneMain "Enter a playlist link or server address."
                if (kind == "xtream" && (username.isBlank() || password.isBlank())) return@onPhoneMain "Xtream Codes needs a username and password."
                iptvSetup.addIptvSource(kind, name.ifBlank { if (kind == "xtream") "Xtream" else "IPTV" }, url.trim(), username.trim(), password, epg.trim(), "", "ts") {}
                "Adding the IPTV source. The TV shows when the channels are loaded.".also { phoneNote("IPTV: adding ${name.ifBlank { url }}") }
            }

            override fun log(line: String) {
                onPhoneMain { phoneNote(line) }
            }

            override fun exportBackup(passphrase: String): ByteArray {
                access.requireActive()
                val bytes = Backup.export(getApplication<Application>(), passphrase)
                onPhoneMain { phoneNote("Backup saved to your phone" + if (passphrase.isBlank()) " (not encrypted)" else " (encrypted)") }
                return bytes
            }

            override fun importCollections(text: String): String = onPhoneMain {
                val check = app.ember.tv.data.Collections.inspect(text)
                check.error ?: run {
                    val revision = collections.revision
                    stage("Import ${check.collections.size} collections and ${check.collections.sumOf { it.folders.size }} folders; matching IDs will update.") {
                        collections.importText(text, expectedRevision = revision)
                    }
                }
            }
            override fun exportCollection(id: String, passphrase: String): ByteArray = onPhoneMain {
                val everything = id == ContentPhone.EXPORT_ALL
                val collection = if (everything) null else collections.list.firstOrNull { targetKey(it.id) == id } ?: error("Choose a collection.")
                val data = withContext(Dispatchers.IO) { (if (collection == null) collections.exportAll(passphrase) else collections.exportPack(collection.id, passphrase)).toByteArray() }
                access.requireActive(); check(profiles.profileId == owner) { "The active profile changed." }; data
            }
            override fun content(): app.ember.tv.phone.PhoneContent = onPhoneMain {
                app.ember.tv.phone.PhoneContent(sources().map { (id, s) -> id to (s.title ?: "Catalog") },
                    collections.list.flatMap { c -> listOf(targetKey(c.id) to (c.title + " / New folder")) + c.folders.map { f -> targetKey(c.id, f.id) to (c.title + " / " + f.title) } },
                    addons.map { AddonAccess.key(it) to (addonLabel(it) + if (addonAvailable(it)) "" else " · Paused") },
                    catalogExtras = collectionCatalogs.filter { it.extras.isNotEmpty() }.map { app.ember.tv.phone.PhoneCatalogExtras(AddonAccess.key(it.key), it.label, it.extras.map { e -> if (app.ember.tv.data.CollectionSecrets.sensitive(e.name)) e.copy(default = null, options = emptyList()) else e }) },
                    collections = collections.list.map { targetKey(it.id) to it.title },
                    folderSources = folderSources().map { (id, target) -> id to (target.first.title + " / " + target.second.title + " / " + app.ember.tv.data.Collections.label(target.second.allSources[target.third], collectionCatalogs)) },
                    lists = widgetLists(),
                    home = homeEntries().first, homeOff = homeEntries().second,
                    widgets = run {
                        // #161: in Home order, which is the order of their sections in the Home layout.
                        val order = sections.layout.map { it.first }
                        val shown = widgets.list.sortedBy { w -> order.indexOf(app.ember.tv.data.HomeWidgets.section(w.id)).let { if (it < 0) Int.MAX_VALUE else it } }
                        shown.map { w ->
                            val section = app.ember.tv.data.HomeWidgets.section(w.id)
                            val at = order.indexOf(section)
                            val count = widgetItems(w).size
                            app.ember.tv.phone.PhoneWidget(widgetKey(w.id), w.title, w.style, w.shape, w.limit, app.ember.tv.data.HomeWidgets.sourceLabel(w.source, collectionCatalogs),
                                when {
                                    !sections.sectionOn(section) -> "Off on Home"
                                    count > 0 -> "$count titles"
                                    w.id in widgets.loading -> "Loading"
                                    w.id in widgets.failed -> "Could not load"
                                    else -> "Nothing to show yet"
                                }, canMoveUp = at > 0, canMoveDown = at in 0 until order.lastIndex, art = w.art)
                        }
                    },
                    library = folderSources().let { ids -> collections.list.map { c -> app.ember.tv.phone.PhoneCollection(targetKey(c.id), c.title, c.folders.map { f ->
                        app.ember.tv.phone.PhoneFolder(targetKey(c.id, f.id), f.title, f.shape, f.hideTitle, f.coverImageUrl,
                            ids.filter { it.second.first.id == c.id && it.second.second.id == f.id }.sortedBy { it.second.third }
                                .map { it.first to app.ember.tv.data.Collections.label(f.allSources[it.second.third], collectionCatalogs) })
                    }, c.backdropImageUrl) } },
                    existingExtras = folderSources().mapNotNull { (id, target) ->
                        val source = target.second.allSources[target.third]
                        collectionCatalogs.firstOrNull { app.ember.tv.data.Collections.matches(it, source) }?.let { def ->
                            val args = collections.sourceArguments(source).getOrDefault(emptyMap())
                            app.ember.tv.phone.PhoneCatalogExtras(id, source.title ?: def.label, def.extras.map { e ->
                                if (app.ember.tv.data.CollectionSecrets.sensitive(e.name)) e.copy(default = null, options = emptyList()) else e.copy(default = args[e.name] ?: e.default)
                            })
                        }
                    })
            }
            override fun exportWidgets(): ByteArray? = onPhoneMain {
                check(!profiles.profile.kids) { "Switch to an adult profile." }
                widgets.list.takeIf { it.isNotEmpty() }?.let { app.ember.tv.data.HomeWidgets.pack(it).toByteArray() }
            }
            override fun importWidgets(text: String): String = onPhoneMain {
                check(!profiles.profile.kids) { "Switch to an adult profile." }
                app.ember.tv.data.HomeWidgets.unpack(text, owner).fold({ found ->
                    stage("Import ${found.size} Home widget(s): ${found.take(5).joinToString(", ") { it.title }}${if (found.size > 5) "…" else ""}. Widgets with the same id are updated.") {
                        vm.importWidgets(text)
                    }
                }, { it.message ?: "This is not an Ember widget pack." })
            }
            override fun lookup(kind: String, tab: String, query: String): List<app.ember.tv.phone.PhoneLookup> = onPhoneMain {
                check(!profiles.profile.kids) { "Switch to an adult profile." }
                val q = query.trim()
                when (kind) {
                    "person", "company" -> exploreRepository.search(q, company = kind == "company").map { app.ember.tv.phone.PhoneLookup(it.id.toString(), it.name) }
                    "provider" -> exploreRepository.providers(watch.watchRegion).filter { it.name.contains(q, true) }.take(20).map { app.ember.tv.phone.PhoneLookup(it.id.toString(), it.name) }
                    "trakt" -> traktLists(tab, q).map { l -> app.ember.tv.phone.PhoneLookup(l.id.toString(), l.name,
                        listOfNotNull(l.by.takeIf { it.isNotBlank() }?.let { "by @$it" }, "${l.items} titles", "${l.likes} likes", l.description.takeIf { it.isNotBlank() }).joinToString(" · ")) }
                    else -> emptyList()
                }
            }
            override fun proposeContent(fields: Map<String, String>): String = onPhoneMain {
                when (fields["action"]) {
                    "add-source" -> {
                        val source = runCatching { configuredSource(fields["source"].orEmpty(), fields) }.getOrNull() ?: return@onPhoneMain "Choose an available source and complete its required filters."
                        val c = collections.list.firstOrNull { c -> targetKey(c.id) == fields["target"] || c.folders.any { targetKey(c.id, it.id) == fields["target"] } }
                        val f = c?.folders?.firstOrNull { targetKey(c.id, it.id) == fields["target"] }
                        if (fields["target"] != "new" && c == null) return@onPhoneMain "That destination no longer exists. Reload the page."
                        val cn = fields["collection"].orEmpty().trim().take(100); val fn = fields["folder"].orEmpty().trim().take(100)
                        if (c == null && cn.isBlank() || f == null && fn.isBlank()) return@onPhoneMain "Enter names for the new collection/folder."
                        stage("Add ${source.title ?: "catalog"} to ${c?.title ?: cn} / ${f?.title ?: fn}.") { collections.addSource(c?.id, f?.id, cn, fn, source) }
                    }
                    "folder" -> {
                        val c = collections.list.firstOrNull { c -> c.folders.any { targetKey(c.id, it.id) == fields["target"] } } ?: return@onPhoneMain "Choose an existing folder."
                        val f = c.folders.first { targetKey(c.id, it.id) == fields["target"] }
                        val name = fields["name"].orEmpty().trim().take(100)
                        if (name.isBlank()) return@onPhoneMain "Enter a folder name."
                        val cover = if (fields["clearCover"] == "on") null else fields["cover"].orEmpty().trim().take(4000).ifBlank { f.coverImageUrl }
                        val changed = c.copy(folders = c.folders.map { if (it.id == f.id) it.copy(title = name, coverImageUrl = cover) else it })
                        stage("Edit ${c.title} / ${f.title}: rename to $name" + if (cover != null) " and update the cover." else ".") { collections.saveCollection(changed, c) }
                    }
                    "collection" -> {
                        val c = collections.list.firstOrNull { targetKey(it.id) == fields["collectionId"] } ?: return@onPhoneMain "Choose a collection."
                        val revision = collections.revision
                        val operation = fields["operation"].orEmpty()
                        val name = fields["name"].orEmpty().trim().take(100).ifBlank { c.title }
                        val layout = fields["viewMode"].orEmpty().takeIf { it in setOf("TABBED_GRID", "ROWS", "FOLLOW_LAYOUT") } ?: c.viewMode
                        val updated = c.copy(title = name, viewMode = layout)
                        stage("${operation.ifBlank { "Edit" }} collection ${c.title}; name: $name; layout: $layout.") {
                            if (collections.revision != revision) "Collections changed. Reload the phone page."
                            else if (operation == "duplicate") collections.duplicateCollection(c.id)
                            else collections.saveCollection(updated, c, if (operation == "up") -1 else if (operation == "down") 1 else 0)
                        }
                    }
                    "source" -> {
                        val (_, target) = folderSources().firstOrNull { it.first == fields["sourceId"] } ?: return@onPhoneMain "Choose an existing source."
                        val (c, f, index) = target
                        val old = f.allSources[index]
                        val replacement = fields["replacement"].orEmpty()
                        var source = if (replacement.isBlank()) old else runCatching { configuredSource(replacement, fields) }.getOrNull() ?: return@onPhoneMain "The replacement needs filters. Add it using the catalog form first."
                        source = source.copy(title = fields["name"].orEmpty().trim().take(100).ifBlank { source.title }, extensionFields = old.extensionFields + source.extensionFields)
                        if (source.provider == "addon" && replacement.isBlank()) {
                            val def = collectionCatalogs.firstOrNull { app.ember.tv.data.Collections.matches(it, source) }
                            val args = collections.sourceArguments(source).getOrNull() ?: return@onPhoneMain "Restore missing private source settings before editing."
                            val prefix = "edit-extra:${fields["sourceId"]}:"
                            val changes = fields.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
                                .filter { (name, value) -> def?.extras?.any { it.name == name } == true && (!app.ember.tv.data.CollectionSecrets.sensitive(name) || value.isNotBlank()) }
                            val values = args + changes
                            def?.extraError(values)?.let { return@onPhoneMain it }
                            source = source.copy(extraArgs = values, secretArgsRef = null, genre = values["genre"]?.ifBlank { null } ?: if ("genre" !in values) source.genre else null)
                        }
                        val year = fields["year"].orEmpty().trim(); val rating = fields["rating"].orEmpty().trim()
                        if (year.isNotBlank() && year.toIntOrNull()?.let { it in 1870..java.time.LocalDate.now().year } != true) return@onPhoneMain "Enter a valid release year."
                        if (rating.isNotBlank() && rating.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..10.0 } != true) return@onPhoneMain "Enter a rating from 0 to 10."
                        if (source.provider == "ember") source = source.copy(exploreQuery = source.exploreQuery?.copy(fromYear = year.toIntOrNull() ?: source.exploreQuery?.fromYear, toYear = year.toIntOrNull() ?: source.exploreQuery?.toYear, minRating = rating.toDoubleOrNull() ?: source.exploreQuery?.minRating))
                        else if (source.provider == "tmdb" && source.tmdbSourceType !in setOf("LIST", "COLLECTION")) source = source.copy(filters = source.filters.orEmpty() + buildMap {
                            if (year.isNotBlank()) put("year", kotlinx.serialization.json.JsonPrimitive(year))
                            if (rating.isNotBlank()) put("voteAverageGte", kotlinx.serialization.json.JsonPrimitive(rating))
                        })
                        else if (year.isNotBlank() || rating.isNotBlank()) return@onPhoneMain "Year and rating edits apply to TMDB or saved Discover sources."
                        if (f.allSources.filterIndexed { i, _ -> i != index }.any { collections.sourceKey(it) == collections.sourceKey(source) }) return@onPhoneMain "That source already exists in this folder."
                        val operation = fields["operation"]
                        val ordered = app.ember.tv.data.CollectionDrafts.move(f.allSources.mapIndexed { i, s -> if (i == index) source else s }, index, if (operation == "up") -1 else if (operation == "down") 1 else 0)
                        val updated = c.copy(folders = c.folders.map { if (it.id == f.id) it.copy(sources = ordered, catalogSources = emptyList()) else it })
                        app.ember.tv.data.Collections.inspect(app.ember.tv.data.Collections.encode(listOf(updated))).error?.let { return@onPhoneMain it }
                        stage("Edit source ${old.title ?: old.catalogId ?: "source"} in ${c.title} / ${f.title}; action: ${operation ?: "edit"}" + if (year.isNotBlank() || rating.isNotBlank()) "; year: $year, minimum rating: $rating." else ".") { collections.saveCollection(updated, c) }
                    }
                    "save-widget" -> {
                        // #161: the widget editor's one Save.
                        val target = fields["target"].orEmpty()
                        val old = widgets.list.firstOrNull { widgetKey(it.id) == target }
                        if (target != "new" && old == null) return@onPhoneMain "That widget no longer exists. Reload the page."
                        val title = fields["title"].orEmpty().trim().take(100)
                        if (title.isBlank()) return@onPhoneMain "Enter a widget title."
                        fun year(name: String): Int? = fields[name].orEmpty().trim().takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: -1 }
                        val media = { name: String -> fields[name].takeIf { it == "TV" } ?: "MOVIE" }
                        // People, studios and services chosen in the editor: "ref:<kind>:<TMDB id>" = name.
                        fun refs(kind: String) = fields.filterKeys { it.startsWith("ref:$kind:") }.mapNotNull { (key, name) ->
                            key.substringAfterLast(':').toLongOrNull()?.takeIf { it > 0 }?.let { app.ember.tv.discover.ExploreRef(it, name.trim().take(80).ifBlank { "#$it" }) }
                        }
                        val source = when (fields["kind"] ?: "keep") {
                            "keep" -> old?.source ?: return@onPhoneMain "Choose where the titles come from."
                            "catalog" -> runCatching { configuredSource(fields["source"].orEmpty(), fields) }.getOrNull()
                                ?: return@onPhoneMain "Choose an available catalog and complete its required filters."
                            "filters" -> {
                                val rating = fields["minRating"].orEmpty().trim().takeIf { it.isNotEmpty() }?.let { it.toDoubleOrNull() ?: -1.0 }
                                val query = app.ember.tv.discover.ExploreQuery(
                                    kind = app.ember.tv.discover.ExploreKind.entries.firstOrNull { it.name == fields["fkind"] } ?: app.ember.tv.discover.ExploreKind.ALL,
                                    sort = app.ember.tv.discover.ExploreSort.entries.firstOrNull { it.name == fields["fsort"] } ?: app.ember.tv.discover.ExploreSort.POPULAR,
                                    genres = app.ember.tv.discover.ExploreGenres.all.map { it.key }.filter { fields["genre:$it"] == "on" }.toSet(),
                                    fromYear = year("fromYear"), toYear = year("toYear"), minRating = rating,
                                    actors = refs("actor"), directors = refs("director"), companies = refs("company"), providers = refs("provider"))
                                query.error()?.let { return@onPhoneMain it }
                                app.ember.tv.data.CollectionDrafts.discover(title, query, owner)
                            }
                            "trakt" -> app.ember.tv.data.CollectionSource(provider = "trakt", title = title, mediaType = media("traktMedia"),
                                traktListId = app.ember.tv.data.CollectionDrafts.numericId(fields["trakt"].orEmpty()) ?: return@onPhoneMain "Enter a Trakt list link that contains its number, or the number.")
                            "tmdb" -> app.ember.tv.data.CollectionSource(provider = "tmdb", title = title, tmdbSourceType = "LIST", mediaType = media("tmdbMedia"),
                                tmdbId = app.ember.tv.data.CollectionDrafts.numericId(fields["tmdb"].orEmpty())?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return@onPhoneMain "Enter a TMDB list link or number.")
                            "mdblist" -> {
                                if (app.ember.tv.data.ApiKeys.get("MDBLIST_API_KEY").isBlank()) return@onPhoneMain "Add your MDBList API key under Accounts & APIs first."
                                app.ember.tv.data.CollectionSource(provider = app.ember.tv.data.HomeWidgets.MDBLIST, title = title, mediaType = fields["mdblistMedia"]?.takeIf { it == "MOVIE" || it == "TV" },
                                    catalogId = app.ember.tv.data.HomeWidgets.mdblistPath(fields["mdblist"].orEmpty()) ?: return@onPhoneMain "Enter an MDBList list link, user/list-name or number.")
                            }
                            "list" -> {
                                val chosen = widgetLists().firstOrNull { it.first == fields["list"] } ?: return@onPhoneMain "That list is not available right now. Reload the page."
                                app.ember.tv.data.CollectionSource(provider = app.ember.tv.data.HomeWidgets.LIST, catalogId = chosen.first, title = chosen.second)
                            }
                            else -> return@onPhoneMain "Choose where the titles come from."
                        }
                        val widget = app.ember.tv.data.HomeWidgets.normalize(app.ember.tv.data.HomeWidget(
                            id = old?.id ?: java.util.UUID.randomUUID().toString(), title = title,
                            style = fields["style"] ?: old?.style ?: app.ember.tv.data.HomeWidgets.ROW, shape = fields["shape"] ?: old?.shape ?: "default",
                            source = source, limit = fields["limit"]?.trim()?.toIntOrNull() ?: old?.limit ?: app.ember.tv.data.HomeWidgets.DEFAULT_LIMIT,
                            art = fields["art"] ?: old?.art ?: app.ember.tv.data.HeroArtworkMode.STANDARD))
                        app.ember.tv.data.HomeWidgets.error(widget)?.let { return@onPhoneMain it }
                        if (widget.source.provider != app.ember.tv.data.HomeWidgets.LIST && !collectionSourceAllowed(widget.source)) return@onPhoneMain "That source is unavailable for this profile."
                        stage((if (old == null) "Add the Home widget " else "Save the Home widget ") + "$title: ${app.ember.tv.data.HomeWidgets.styleName(widget.style)}, " +
                            "${app.ember.tv.data.HomeWidgets.sourceLabel(widget.source, collectionCatalogs)}, up to ${widget.limit} titles.") {
                            widgets.save(widget).also { if (it == null) refreshWidgets() }
                        }
                    }
                    "move-widget" -> {
                        val w = widgets.list.firstOrNull { widgetKey(it.id) == fields["target"] } ?: return@onPhoneMain "That widget no longer exists. Reload the page."
                        val up = fields["dir"] == "up"
                        stage("Move the Home widget ${w.title} ${if (up) "up" else "down"} one place on Home.") {
                            sections.moveSection(app.ember.tv.data.HomeWidgets.section(w.id), if (up) -1 else 1); null
                        }
                    }
                    "order-widgets" -> {
                        // #186: the whole order in one approval. The page sends opaque row ids; anything unknown is refused.
                        val byKey = widgets.list.associateBy { widgetKey(it.id) }
                        val keys = fields["order"].orEmpty().split(',').filter { it.isNotBlank() }
                        val ordered = keys.mapNotNull { byKey[it] }
                        if (ordered.size != keys.size || ordered.size != byKey.size || ordered.toSet().size != ordered.size) return@onPhoneMain "The widgets changed. Reload the page and try again."
                        stage("Put your ${ordered.size} Home widgets in this order: ${ordered.joinToString(", ") { it.title }.take(200)}.") {
                            sections.arrange(ordered.map { app.ember.tv.data.HomeWidgets.section(it.id) }); null
                        }
                    }
                    // #240: the whole Home: order, one-place moves and on/off, each approved on the TV.
                    "order-home" -> {
                        val (on, _) = homeSections()
                        val byKey = on.associateBy(::homeKey)
                        val keys = fields["order"].orEmpty().split(',').filter { it.isNotBlank() }
                        val ordered = keys.mapNotNull { byKey[it] }
                        if (ordered.size != keys.size || ordered.toSet() != on.toSet() || ordered.size != on.size) return@onPhoneMain "Home changed. Reload the page and try again."
                        stage("Put Home in this order: ${ordered.joinToString(", ") { homeTitle(it) }.take(240)}.") { sections.arrange(ordered); null }
                    }
                    "move-home" -> {
                        val (on, _) = homeSections()
                        val section = on.firstOrNull { homeKey(it) == fields["target"] } ?: return@onPhoneMain "That widget is not on Home now. Reload the page."
                        val up = fields["dir"] == "up"
                        val next = app.ember.tv.ui.WidgetDrafts.reorder(on, section, if (up) -1 else 1) ?: return@onPhoneMain "It is already ${if (up) "first" else "last"}."
                        stage("Move ${homeTitle(section)} ${if (up) "up" else "down"} one place on Home.") { sections.arrange(next); null }
                    }
                    "home-on", "home-off" -> {
                        val turnOn = fields["action"] == "home-on"
                        val (on, off) = homeSections()
                        val section = (if (turnOn) off else on).firstOrNull { homeKey(it) == fields["target"] } ?: return@onPhoneMain "That widget changed. Reload the page."
                        stage("Turn ${homeTitle(section)} ${if (turnOn) "on" else "off"} on Home.") { if (sections.sectionOn(section) != turnOn) sections.toggleSection(section); null }
                    }
                    "delete-widget" -> {
                        val w = widgets.list.firstOrNull { widgetKey(it.id) == fields["target"] } ?: return@onPhoneMain "That widget no longer exists. Reload the page."
                        stage("Delete the Home widget ${w.title}. This can't be undone.") { deleteWidget(w.id); null }
                    }
                    "save-folder" -> {
                        // #160: the folder editor's one Save: title, shape, hide title, cover and sources together.
                        val target = fields["target"].orEmpty(); val into = fields["in"].orEmpty()
                        val creating = target == "new"
                        val c = if (creating) collections.list.firstOrNull { targetKey(it.id) == into }
                            else collections.list.firstOrNull { c -> c.folders.any { targetKey(c.id, it.id) == target } } ?: return@onPhoneMain "That folder no longer exists. Reload the page."
                        if (creating && c == null && into != "new" && into.isNotBlank()) return@onPhoneMain "That collection no longer exists. Reload the page."
                        val old = if (creating || c == null) null else c.folders.firstOrNull { targetKey(c.id, it.id) == target }
                        val name = fields["name"].orEmpty().trim().take(100)
                        if (name.isBlank()) return@onPhoneMain "Enter a folder title."
                        val collectionName = fields["collection"].orEmpty().trim().take(100)
                        if (c == null && collectionName.isBlank()) return@onPhoneMain "Enter a collection title."
                        val shape = fields["shape"]?.takeIf { it in setOf("POSTER", "LANDSCAPE", "SQUARE") } ?: old?.tileShape ?: "SQUARE"
                        val cover = fields["cover"].orEmpty().trim().take(4000).ifBlank { null }
                        if (cover != null && !cover.startsWith("https://", true) && !cover.startsWith("http://", true)) return@onPhoneMain "The cover image must be an http or https link."
                        val ids = if (c == null || old == null) emptyMap<Int, String>() else folderSources().filter { it.second.first.id == c.id && it.second.second.id == old.id }.associate { it.second.third to it.first }
                        val kept = old?.allSources.orEmpty().filterIndexed { index, _ -> fields["remove:" + ids[index]] != "on" }
                        val added = fields["source"].orEmpty().takeIf { it.isNotBlank() }?.let { id ->
                            runCatching { configuredSource(id, fields) }.getOrNull() ?: return@onPhoneMain "Choose an available source and complete its required filters."
                        }
                        if (added != null && kept.any { collections.sourceKey(it) == collections.sourceKey(added) }) return@onPhoneMain "That source is already in this folder."
                        val sources = kept + listOfNotNull(added)
                        if (sources.isEmpty()) return@onPhoneMain "A folder needs at least one source. Choose one, or delete the folder instead."
                        val folder = (old ?: app.ember.tv.data.CollectionFolder(id = java.util.UUID.randomUUID().toString()))
                            .copy(title = name, tileShape = shape, hideTitle = fields["hideTitle"] == "on", coverImageUrl = cover, sources = sources, catalogSources = emptyList())
                        val updated = if (c == null) app.ember.tv.data.MediaCollection(id = java.util.UUID.randomUUID().toString(), title = collectionName, folders = listOf(folder))
                            else c.copy(folders = if (old == null) c.folders + folder else c.folders.map { if (it.id == old.id) folder else it })
                        app.ember.tv.data.Collections.inspect(app.ember.tv.data.Collections.encode(listOf(updated))).error?.let { return@onPhoneMain it }
                        val changes = listOfNotNull("${sources.size} source(s)", "shape ${shape.lowercase()}", if (cover != old?.coverImageUrl) "cover changed" else null, if (folder.hideTitle) "title hidden" else null).joinToString(", ")
                        stage((if (old == null) "Create the folder " else "Save the folder ") + "${updated.title} / $name: $changes.") { collections.saveCollection(updated, c) }
                    }
                    "move-folder" -> {
                        val target = fields["target"].orEmpty()
                        val c = collections.list.firstOrNull { c -> c.folders.any { targetKey(c.id, it.id) == target } } ?: return@onPhoneMain "That folder no longer exists. Reload the page."
                        val from = c.folders.indexOfFirst { targetKey(c.id, it.id) == target }
                        val to = from + if (fields["dir"] == "up") -1 else 1
                        if (to !in c.folders.indices) return@onPhoneMain "That folder is already at the ${if (fields["dir"] == "up") "start" else "end"}."
                        val moved = c.folders.toMutableList().apply { add(to, removeAt(from)) }
                        stage("Move the folder ${c.title} / ${c.folders[from].title} ${if (fields["dir"] == "up") "up" else "down"}.") { collections.saveCollection(c.copy(folders = moved), c) }
                    }
                    "delete" -> {
                        // #158: a whole collection ("c-…") or one folder; approved on the TV like every phone change.
                        val target = fields["target"].orEmpty()
                        val whole = collections.list.firstOrNull { targetKey(it.id) == target }
                        val c = whole ?: collections.list.firstOrNull { c -> c.folders.any { targetKey(c.id, it.id) == target } } ?: return@onPhoneMain "That collection or folder no longer exists. Reload the page."
                        val f = if (whole != null) null else c.folders.first { targetKey(c.id, it.id) == target }
                        val revision = collections.revision
                        stage(if (f == null) "Delete the collection ${c.title} and its ${c.folders.size} folder(s). This can't be undone."
                            else "Delete the folder ${c.title} / ${f.title}" + (if (c.folders.size == 1) ", which also deletes the collection" else "") + ". This can't be undone.") {
                            if (collections.revision != revision) "Collections changed. Reload the phone page."
                            else if (f == null) collections.deleteCollection(c.id) else collections.deleteFolder(c.id, f.id)
                        }
                    }
                    "addon" -> {
                        val url = addons.firstOrNull { AddonAccess.key(it) == fields["addon"] } ?: return@onPhoneMain "Choose an installed add-on."
                        val enabled = fields["enabled"] == "on"
                        stage((if (enabled) "Enable " else "Pause ") + addonLabel(url)) { if (url !in addons) "That add-on was removed. Reload the phone page." else { pauseAddon(url, !enabled); null } }
                    }
                    else -> "Unknown content action."
                }
            }

            // #236: badge editing from the phone. Like badge packs and settings, edits apply at once.
            override fun badges(): PhoneBadges? = onPhoneMain {
                PhoneBadges(sourceFormat.badgeMode, app.ember.tv.data.Badges.customPack(getApplication<Application>())?.filters.orEmpty())
            }

            override fun badgeIcon(id: String): Pair<ByteArray, String>? = onPhoneMain {
                val context = getApplication<Application>()
                val f = app.ember.tv.data.Badges.customPack(context)?.filters?.firstOrNull { it.id == id }
                withContext(Dispatchers.IO) { app.ember.tv.data.Badges.iconBytes(context, f?.imageURL) }
            }

            override fun changeBadge(fields: Map<String, String>, picture: ByteArray?): String {
                val context = getApplication<Application>()
                val action = fields["action"].orEmpty()
                val id = fields["id"].orEmpty()
                val result = onPhoneMain {
                    val f = sourceFormat
                    val editor = app.ember.tv.data.BadgeEditor
                    when (action) {
                        "add" -> {
                            val pattern = fields["pattern"].orEmpty()
                            editor.check(pattern)?.let { return@onPhoneMain it }
                            f.editBadges { editor.add(it, fields["name"].orEmpty(), pattern) } ?: "Added ${fields["name"].orEmpty().ifBlank { "the badge" }}."
                        }
                        "copy" -> if (fields["confirm"] != "on") "Tick the box to confirm." else {
                            val err = app.ember.tv.data.Badges.copyDetailed(context)
                            if (err == null) { f.readCustomBadges().join(); f.updateBadgeMode(app.ember.tv.data.Badges.CUSTOM) }
                            err ?: "Your badges are now a copy of the Detailed badges."
                        }
                        "up", "down" -> f.editBadges { editor.move(it, id, if (action == "up") -1 else 1) } ?: "Moved."
                        "delete" -> f.editBadges { editor.delete(it, id) } ?: "Badge deleted."
                        "save" -> {
                            val pattern = fields["pattern"].orEmpty()
                            editor.check(pattern)?.let { return@onPhoneMain it }
                            val icon = if (picture == null) null else {
                                if (picture.size > editor.MAX_ICON_BYTES) return@onPhoneMain "That picture is too large (at most ${editor.MAX_ICON_BYTES / 1000} KB)."
                                val type = editor.imageType(picture) ?: return@onPhoneMain "Send a PNG, JPEG, WebP or GIF picture."
                                app.ember.tv.data.Badges.saveIcon(context, picture, type).getOrElse { return@onPhoneMain "Could not keep that picture on the TV." }
                            }
                            val link = fields["image"].orEmpty().trim()
                            if (link.isNotEmpty() && !(link.startsWith("https://", true) || link.startsWith("http://", true))) return@onPhoneMain "A picture link starts with https://."
                            fun colour(name: String) = fields[name]?.takeIf { app.ember.tv.data.Badges.color(it) != null }
                            val width = fields["borderWidth"]?.toIntOrNull()?.coerceIn(0, 6) ?: 0
                            f.editBadges {
                                editor.update(it, id) { b ->
                                    val tag = if (fields["tagNone"] == "on") null else colour("tag")?.let { c -> editor.withOpacity(c, fields["opacity"]?.toIntOrNull() ?: 100) }
                                    editor.border(b.copy(
                                        name = fields["name"].orEmpty().trim().take(60).ifBlank { b.name },
                                        pattern = pattern,
                                        isEnabled = fields["enabled"] == "true",
                                        tagColor = tag,
                                        textColor = if (fields["textNone"] == "on") null else colour("text"),
                                        imageTint = if (fields["tintNone"] == "on") null else colour("tint"),
                                        borderColor = if (width > 0) colour("border") ?: b.borderColor else b.borderColor,
                                        borderRadius = fields["radius"]?.toIntOrNull()?.coerceIn(0, 12)?.toFloat() ?: b.borderRadius,
                                        imageURL = when { fields["imageNone"] == "on" -> null; icon != null -> icon; link.isNotEmpty() -> link; else -> b.imageURL },
                                    ), width)
                                }
                            } ?: "Saved ${fields["name"].orEmpty().ifBlank { "the badge" }}."
                        }
                        else -> "That badge change is not available."
                    }
                }
                onPhoneMain { phoneNote("Badges: $result") }
                return result
            }

            override fun importBadges(text: String): String {
                val err = onPhoneMain { sourceFormat.importBadgesText(text) }
                onPhoneMain { phoneNote(if (err == null) "Badge pack received" else "Badge pack not used: $err") }
                return err ?: "Badges loaded on the TV (Settings > Stremio > Badges: Custom)."
            }

            override fun importBackup(bytes: ByteArray, passphrase: String, apply: Boolean): String {
                access.requireActive()
                val (payload, summary) = try {
                    Backup.read(bytes, passphrase)
                } catch (e: Backup.BackupException) {
                    return e.message ?: "This is not an Ember backup file."
                } catch (e: Exception) {
                    return "Could not read the file: " + (e.message ?: e.javaClass.simpleName)
                }
                val what = listOfNotNull(
                    summary.created?.let { "made $it" },
                    "${summary.addons} addon(s)",
                    "${summary.signIns} sign-in(s)",
                    "${summary.keys} key(s)",
                ).joinToString(", ")
                if (!apply) return "Backup checked: $what. Tick the box and send it again to restore it on the TV."
                // Replacing every setting, sign-in and key is approved on the TV, like smaller phone
                // changes and the TV's own restore (#52).
                val sent = onPhoneMain {
                    stage("Restore a backup from your phone ($what). This replaces every setting, sign-in and key, then restarts Ember.") {
                        phoneNote("Restoring a backup from your phone ($what)")
                        backup.restoreNow(payload)
                        "Restoring the backup. Ember restarts in a moment."
                    }
                }
                return if (sent.startsWith("Sent to the TV")) "Backup checked: $what. Approve the restore on the TV to replace its settings; Ember then restarts." else sent
            }
        }
      }
    }
}
