package app.ember.tv.phone

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
                val collection = collections.list.firstOrNull { targetKey(it.id) == id } ?: error("Choose a collection.")
                val data = withContext(Dispatchers.IO) { collections.exportPack(collection.id, passphrase).toByteArray() }
                access.requireActive(); check(profiles.profileId == owner) { "The active profile changed." }; data
            }
            override fun content(): app.ember.tv.phone.PhoneContent = onPhoneMain {
                app.ember.tv.phone.PhoneContent(sources().map { (id, s) -> id to (s.title ?: "Catalog") },
                    collections.list.flatMap { c -> listOf(targetKey(c.id) to (c.title + " / New folder")) + c.folders.map { f -> targetKey(c.id, f.id) to (c.title + " / " + f.title) } },
                    addons.map { AddonAccess.key(it) to (addonLabel(it) + if (addonAvailable(it)) "" else " · Paused") },
                    catalogExtras = collectionCatalogs.filter { it.extras.isNotEmpty() }.map { app.ember.tv.phone.PhoneCatalogExtras(AddonAccess.key(it.key), it.label, it.extras.map { e -> if (app.ember.tv.data.CollectionSecrets.sensitive(e.name)) e.copy(default = null, options = emptyList()) else e }) },
                    collections = collections.list.map { targetKey(it.id) to it.title },
                    folderSources = folderSources().map { (id, target) -> id to (target.first.title + " / " + target.second.title + " / " + app.ember.tv.data.Collections.label(target.second.allSources[target.third], collectionCatalogs)) },
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
                    "addon" -> {
                        val url = addons.firstOrNull { AddonAccess.key(it) == fields["addon"] } ?: return@onPhoneMain "Choose an installed add-on."
                        val enabled = fields["enabled"] == "on"
                        stage((if (enabled) "Enable " else "Pause ") + addonLabel(url)) { if (url !in addons) "That add-on was removed. Reload the phone page." else { pauseAddon(url, !enabled); null } }
                    }
                    else -> "Unknown content action."
                }
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
