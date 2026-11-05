package app.ember.tv

import android.content.ContextWrapper
import android.content.SharedPreferences
import app.ember.tv.data.*
import app.ember.tv.state.CollectionsState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

class CollectionPersistenceFixesTest {
    @get:Rule val temp = TemporaryFolder()
    private fun pack(vararg ids: String) = ids.map { id -> MediaCollection(id = id, title = id,
        folders = listOf(CollectionFolder(id = "folder", title = "Folder", sources = listOf(CollectionSource(provider = "tmdb", tmdbSourceType = "DISCOVER"))))) }
    private fun context() = object : ContextWrapper(null) {
        private val dir = temp.newFolder()
        private val values = mutableMapOf<String, Any?>()
        override fun getFilesDir() = dir
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when {
                    method.name.startsWith("put") -> { values[args[0] as String] = args[1]; proxy }
                    method.name == "remove" -> { values.remove(args[0]); proxy }
                    method.name == "commit" -> true
                    else -> null
                }
            } as SharedPreferences.Editor
            return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
                when {
                    method.name == "edit" -> editor
                    method.name == "contains" -> values.containsKey(args[0])
                    method.name == "getAll" -> values.toMap()
                    method.name.startsWith("get") -> values[args[0]] ?: args.getOrNull(1)
                    else -> null
                }
            } as SharedPreferences
        }
    }
    private suspend fun ready(predicate: () -> Boolean) { withTimeout(5000) { while (!predicate()) delay(5) } }

    @Test fun invalidEntriesRejectTheWholePackWithAFieldLocation() {
        val good = pack("good").single()
        val mixed = Collections.inspect(Collections.encode(listOf(good, good.copy(id = ""))))
        assertTrue(mixed.collections.isEmpty()); assertTrue(mixed.error!!.contains("Collection 2: id"))
        val folder = good.folders.single()
        val invalid = listOf(good.copy(folders = listOf(folder.copy(sources = emptyList()))))
        assertTrue(Collections.inspect(Collections.encode(invalid)).error!!.contains("folder 1"))
        assertNull(Collections.parse(Collections.encode(invalid)))
    }
    @Test fun duplicateCollectionAndFolderIdsAreNotSilentlySelected() {
        val good = pack("one").single()
        assertTrue(Collections.inspect(Collections.encode(listOf(good, good.copy(title = "Other")))).error!!.contains("duplicate collection id"))
        val duplicate = good.copy(folders = good.folders + good.folders)
        assertTrue(Collections.inspect(Collections.encode(listOf(duplicate))).error!!.contains("duplicate folder id"))
        assertNull(Collections.parse("[]"))
    }
    @Test fun malformedAndUnsupportedSourcesDoNotEchoTheirContentsInErrors() {
        assertNotNull(Collections.inspect("not json password=fixture-secret").error)
        assertFalse(Collections.inspect("not json password=fixture-secret").error!!.contains("fixture-secret"))
        val c = pack("one").single(); val f = c.folders.single()
        for (source in listOf(CollectionSource(), CollectionSource(provider = "trakt", traktListId = -1), CollectionSource(provider = "future"),
            CollectionSource(provider = "tmdb", tmdbSourceType = "NETWORK"))) {
            assertNotNull(Collections.inspect(Collections.encode(listOf(c.copy(folders = listOf(f.copy(sources = listOf(source))))))).error)
        }
    }
    @Test fun knownHeroMetadataAndExistingEmptySavedCollectionsSurviveRoundTrip() {
        val ctx = context(); val c = pack("one").single()
        val configured = c.copy(folders = listOf(c.folders.single().copy(heroVideoUrl = "https://example.com/clip?token=fixture")))
        assertNull(Collections.inspect(Collections.encode(listOf(configured))).error)
        Collections.save(ctx, listOf(configured, MediaCollection(id = "legacy", title = "Legacy empty"))).getOrThrow()
        assertEquals(listOf(configured, MediaCollection(id = "legacy", title = "Legacy empty")), Collections.load(ctx))
        Collections.save(ctx, emptyList()).getOrThrow(); assertTrue(Collections.load(ctx).isEmpty())
    }
    @Test fun interruptedReplacementKeepsTheLastCompleteFileAndCleansTemporaryFiles() {
        val dir = temp.newFolder(); val file = File(dir, "collections.json")
        AtomicFile.write(file, "previous complete pack").getOrThrow()
        val failure = AtomicFile.replace(file) { it.writeText("partial"); throw IOException("disk full") }
        assertTrue(failure.isFailure); assertEquals("previous complete pack", file.readText())
        assertEquals(listOf("collections.json"), dir.list()!!.toList())
        AtomicFile.write(file, "next complete pack").getOrThrow()
        assertEquals("next complete pack", file.readText())
    }
    @Test fun importSaveFailureKeepsMemoryDiskAndSuccessCallbackUnchanged() = runBlocking {
        val ctx = context(); val initial = pack("initial")
        Collections.save(ctx, initial).getOrThrow()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); var changed = 0
        val state = CollectionsState(ctx, Prefs(ctx), scope, AddonClient(), { emptyList() }, { changed++ })
        state.persistCollections = { Result.failure(IOException("fixture-secret")) }
        try {
            val error = state.importText(Collections.encode(pack("new")))
            assertNotNull(error); assertFalse(error!!.contains("fixture-secret"))
            assertEquals(initial, state.list); assertEquals(initial, Collections.load(ctx)); assertEquals(0, changed)
        } finally { scope.cancel() }
    }
    @Test fun rapidMovesApplyInOrderToTheLatestDurableState() = runBlocking {
        val ctx = context(); Collections.save(ctx, pack("a", "b", "c")).getOrThrow()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = CollectionsState(ctx, Prefs(ctx), scope, AddonClient(), { emptyList() }, {})
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val writes = CopyOnWriteArrayList<List<String>>()
        state.persistCollections = { list ->
            writes += list.map { it.id }
            if (writes.size == 1) { started.complete(Unit); release.await() }
            Collections.save(ctx, list)
        }
        try {
            state.move("a", 1); withTimeout(5000) { started.await() }
            assertEquals(listOf("a", "b", "c"), state.list.map { it.id })
            state.move("a", 1); release.complete(Unit)
            ready { state.list.map { it.id } == listOf("b", "c", "a") }
            assertEquals(listOf(listOf("b", "a", "c"), listOf("b", "c", "a")), writes.toList())
            assertEquals(state.list, Collections.load(ctx))
        } finally { release.complete(Unit); scope.cancel() }
    }
    @Test fun invalidImportCannotReplaceAnExistingPack() = runBlocking {
        val ctx = context(); val initial = pack("initial"); Collections.save(ctx, initial).getOrThrow()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = CollectionsState(ctx, Prefs(ctx), scope, AddonClient(), { emptyList() }, {})
        try {
            assertNotNull(state.importText(Collections.encode(listOf(pack("invalid").single().copy(title = "")))))
            assertEquals(initial, state.list); assertEquals(initial, Collections.load(ctx))
        } finally { scope.cancel() }
    }
    @Test fun cancellingACommittedImportCannotLeaveMemoryBehindItsDiskState() = runBlocking {
        val ctx = context(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = CollectionsState(ctx, Prefs(ctx), scope, AddonClient(), { emptyList() }, {})
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        state.persistCollections = { started.complete(Unit); release.await(); Collections.save(ctx, it) }
        val operation = scope.launch { state.importText(Collections.encode(pack("saved"))) }
        try {
            withTimeout(5000) { started.await() }; operation.cancel(); release.complete(Unit)
            withTimeout(5000) { operation.join() }
            assertEquals(pack("saved"), state.list); assertEquals(state.list, Collections.load(ctx))
        } finally { release.complete(Unit); scope.cancel() }
    }

    @Test fun editorUsesConflictChecksAndPreservesTheCollectionPosition() = runBlocking {
        val ctx = context(); Collections.save(ctx, pack("a", "b", "c")).getOrThrow()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = CollectionsState(ctx, Prefs(ctx), scope, AddonClient(), { emptyList() }, {})
        try {
            val original = state.list[1]; val edited = original.copy(title = "Edited")
            assertNull(state.saveCollection(edited, original)); assertEquals(listOf("a", "b", "c"), state.list.map { it.id })
            assertNotNull(state.saveCollection(original.copy(title = "Stale"), original))
            assertEquals("Edited", state.list[1].title)
        } finally { scope.cancel() }
    }
    @Test fun addingSourcesRetainsOrderAndRejectsDuplicateOrRemovedTargets() = runBlocking {
        val ctx = context(); Collections.save(ctx, pack("a")).getOrThrow()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = CollectionsState(ctx, Prefs(ctx), scope, AddonClient(), { emptyList() }, {})
        val source = CollectionSource(provider = "trakt", traktListId = 123)
        try {
            assertNull(state.addSource("a", "folder", "", "", source))
            assertEquals(source, state.list.single().folders.single().allSources.last())
            assertNotNull(state.addSource("a", "folder", "", "", source.copy(title = "Other label")))
            assertNotNull(state.addSource("removed", null, "Name", "Folder", source))
            assertNotNull(state.addSource("a", "removed", "", "", source))
            assertEquals(1L, state.revision)
        } finally { scope.cancel() }
    }
    @Test fun previewModesHandleConflictsAndRefuseStalePreviews() = runBlocking {
        val ctx = context(); Collections.save(ctx, pack("a", "b")).getOrThrow()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = CollectionsState(ctx, Prefs(ctx), scope, AddonClient(), { emptyList() }, {})
        try {
            val incoming = Collections.encode(pack("a"))
            assertNull(state.importText(incoming, "copy", 0))
            assertEquals(3, state.list.size); assertEquals(3, state.list.map { it.id }.toSet().size)
            assertNotEquals("folder", state.list.last().folders.single().id)
            assertNotNull(state.importText(incoming, "replace", 0)); assertEquals(3, state.list.size)
            assertNull(state.importText(incoming, "merge", state.revision)); assertEquals(3, state.list.size)
            assertNull(state.importText(incoming, "replace", state.revision)); assertEquals(listOf("a"), state.list.map { it.id })
        } finally { scope.cancel() }
    }
    @Test fun firstPageCacheIsBoundedBySourceContextAndExplicitRefresh() = runBlocking {
        val ctx = context(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); var owner = "one"
        val state = CollectionsState(ctx, Prefs(ctx), scope, AddonClient(), { emptyList() }, {}, cacheContext = { owner })
        val calls = java.util.concurrent.atomic.AtomicInteger()
        state.loadPage = { _, _, _ -> calls.incrementAndGet(); Result.success(CollectionPage(listOf(Meta("x", "movie", "X")), 20, true)) }
        val c = pack("a").single(); val f = c.folders.single()
        try {
            state.openFolder(c, f); ready { state.tabs.singleOrNull()?.loading == false }
            state.closeFolder(); state.openFolder(c, f); ready { state.tabs.singleOrNull()?.loading == false }; assertEquals(1, calls.get())
            state.refreshFolder(); ready { state.tabs.singleOrNull()?.loading == false }; assertEquals(2, calls.get())
            owner = "two"; state.openFolder(c, f); ready { state.tabs.singleOrNull()?.loading == false }; assertEquals(3, calls.get())
        } finally { scope.cancel() }
    }

    @Test fun legacyCredentialBearingReferencesMigrateAcrossPreferencesAndHomeCache() {
        val ctx = context(); val sp = ctx.getSharedPreferences("ember", 0)
        val url = "https://example.com/fixture-private/manifest.json?token=x"; val key = "$url|movie|top"
        val section = app.ember.tv.state.HomeSectionsState.catalogSection(key)
        sp.edit().putString("catalogOrder", key).putString("catalogHome", "{\"$key\":false}")
            .putString("addonNames", "{\"$url\":\"My provider\"}")
            .putString("profiles", "[{\"id\":\"kid\",\"name\":\"Kid\",\"catalogs\":[\"$key\"]}]")
            .putString("homeLayout", "$section:0").commit()
        val prefs = Prefs(ctx); val opaque = CatalogIdentity.migrate(key)
        assertEquals(listOf(opaque), prefs.catalogOrder); assertEquals(false, prefs.catalogHome[opaque])
        assertEquals("My provider", prefs.addonNames[AddonAccess.key(url)])
        assertEquals(setOf(opaque), prefs.profiles.single().catalogs)
        assertEquals(false, prefs.homeLayout.single().second)
        assertFalse(sp.all.toString().contains("fixture-private"))
        File(ctx.filesDir, "home-snapshot.json").writeText("""{"rows":[{"addonName":"Provider","title":"Top","items":[],"catalogKey":"$key"}]}""")
        assertEquals(opaque, HomeSnapshot.load(ctx)!!.rows.single().catalogKey)
        assertFalse(File(ctx.filesDir, "home-snapshot.json").readText().contains("fixture-private"))
    }
    @Test fun selectedSourceSurvivesReorderingAndIsIsolatedBetweenProfiles() {
        val ctx = context(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); var profile = "one"
        val state = CollectionsState(ctx, Prefs(ctx), scope, AddonClient(), { emptyList() }, {}, profileId = { profile })
        val sources = listOf(1L,2L,3L).map { CollectionSource(provider = "trakt", traktListId = it) }
        val folder = CollectionFolder("f", "Folder", sources = sources); val collection = MediaCollection("c", "Collection", showAllTab = false, folders = listOf(folder))
        try {
            state.saveTab(collection, folder, 1)
            assertEquals(0, state.savedTab(collection, folder.copy(sources = listOf(sources[1],sources[2],sources[0]))))
            profile = "two"; assertEquals(0, state.savedTab(collection, folder))
            profile = "one"; assertEquals(1, state.savedTab(collection, folder))
            assertEquals(0, state.savedTab(collection, folder.copy(sources = listOf(sources[0],sources[2]))))
        } finally { scope.cancel() }
    }

    @Test fun equalSourcesShareOneRequestAndClosingCancelsTheLastReader() = runBlocking {
        val ctx = context(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val state = CollectionsState(ctx, Prefs(ctx), scope, AddonClient(), { emptyList() }, {})
        val calls = java.util.concurrent.atomic.AtomicInteger(); val started = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        state.loadPage = { _,_,_ -> calls.incrementAndGet(); started.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } }
        val source = CollectionSource(provider = "trakt", traktListId = 1)
        val f = CollectionFolder("f", "Folder", sources = listOf(source, source.copy(title = "Other label")))
        val c = MediaCollection("c", "Collection", folders = listOf(f))
        try {
            withContext(scope.coroutineContext) { state.openFolder(c,f) }; withTimeout(5000) { started.await() }; delay(40)
            assertEquals(1,calls.get())
            withContext(scope.coroutineContext) { state.closeFolder() }; withTimeout(5000) { cancelled.await() }
            assertNull(state.open); assertTrue(state.tabs.isEmpty())
        } finally { scope.cancel() }
    }
    @Test fun selectedSourceBypassesQueuedBackgroundWorkWithFourRequestLimit() = runBlocking {
        val ctx = context(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val state = CollectionsState(ctx, Prefs(ctx), scope, AddonClient(), { emptyList() }, {})
        val entered = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>(); val release = CompletableDeferred<Unit>()
        val active = java.util.concurrent.atomic.AtomicInteger(); val peak = java.util.concurrent.atomic.AtomicInteger()
        state.loadPage = { source,_,_ -> val n = active.incrementAndGet(); peak.updateAndGet { maxOf(it,n) }; entered += source.traktListId!!
            try { release.await(); Result.success(CollectionPage(emptyList(),100,true)) } finally { active.decrementAndGet() } }
        val f = CollectionFolder("f", "Folder", sources = (1L..6L).map { CollectionSource(provider = "trakt", traktListId = it) })
        val c = MediaCollection("c", "Collection", folders = listOf(f))
        try {
            withContext(scope.coroutineContext) { state.openFolder(c,f) }; ready { entered.size == 3 }; assertFalse(6L in entered)
            withContext(scope.coroutineContext) { state.prioritize(5) }; ready { 6L in entered }; assertTrue(peak.get() <= 4)
            release.complete(Unit); ready { state.tabs.none { it.loading } }
        } finally { release.complete(Unit); scope.cancel() }
    }
    @Test fun refreshCannotCacheALateOldPage() = runBlocking {
        val ctx = context(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val state = CollectionsState(ctx, Prefs(ctx), scope, AddonClient(), { emptyList() }, {})
        val calls = java.util.concurrent.atomic.AtomicInteger(); val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        state.loadPage = { _,_,_ -> val n = calls.incrementAndGet(); if(n==1) { started.complete(Unit); withContext(NonCancellable) { release.await() } }
            Result.success(CollectionPage(listOf(Meta("$n","movie","$n")),20,true)) }
        val c = pack("a").single()
        try {
            withContext(scope.coroutineContext) { state.openFolder(c,c.folders.single()) }; withTimeout(5000) { started.await() }
            withContext(scope.coroutineContext) { state.refreshFolder() }; ready { calls.get()==2 && state.tabs.singleOrNull()?.loading==false }
            release.complete(Unit); delay(40)
            withContext(scope.coroutineContext) { state.closeFolder(); state.openFolder(c,c.folders.single()) }
            ready { state.tabs.singleOrNull()?.loading==false }; assertEquals("2",state.tabs.single().items.single().id); assertEquals(2,calls.get())
        } finally { release.complete(Unit); scope.cancel() }
    }
    @Test fun duplicationAndCollectionReorderingRetainDurableSourceConfiguration() = runBlocking {
        val ctx = context(); Collections.save(ctx,pack("a","b")).getOrThrow()
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val state = CollectionsState(ctx,Prefs(ctx),scope,AddonClient(),{ emptyList() },{})
        try {
            val a = state.list.first(); assertNull(state.saveCollection(a.copy(title="Edited"),a,1)); assertEquals(listOf("b","a"),state.list.map{it.id})
            assertNull(state.duplicateCollection("a")); assertEquals(3,state.list.size)
            assertEquals(a.folders.single().allSources,state.list.last().folders.single().allSources)
            assertNull(state.duplicateFolder("a","folder")); assertEquals(2,state.list.first{it.id=="a"}.folders.size)
            assertEquals(state.list,Collections.load(ctx))
        } finally { scope.cancel() }
    }
}
