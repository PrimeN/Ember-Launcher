package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.state.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InputValidationAndSearchRecoveryTest {
    @get:Rule val temp=TemporaryFolder()
    private fun catalog(id:String="one")=CatalogDef(
        AddonInfo("https://$id.example","https://$id.example",id,listOf("movie"),null,emptyList(),emptyList()),"movie",id,id,emptyList(),false,true,false,false)
    @Test fun restoredMarginsAreAlwaysFiniteAndLeaveTheInterfaceUsable() {
        for(value in listOf(null,"","bad","NaN","Infinity","-Infinity","1e30","-1e30","-10","100")) {
            assertTrue(ScreenMargins.fraction(value.orEmpty()).isFinite())
            assertTrue(ScreenMargins.fraction(value.orEmpty()) in 0f..0.05f)
        }
        assertEquals("0",ScreenMargins.normalize("NaN"));assertEquals("5",ScreenMargins.normalize("900"))
        assertEquals("0",ScreenMargins.normalize("-1"));assertEquals("2.5",ScreenMargins.normalize("2.500"))
        assertEquals(.025f,ScreenMargins.fraction("2.5"),0f)
    }
    @Test fun validMarginOptionsRetainCanonicalSavedValues() {
        val p=Prefs(PersistenceContext(temp.newFolder()))
        for(value in listOf("0","2.5","5")) {p.screenMargin=value;assertEquals(value,p.screenMargin);assertTrue(ScreenMargins.valid(value))}
    }
    @Test fun oldInvalidStoredMarginCanLoadWithoutNeedingSettingsToRepairIt() {
        val context=PersistenceContext(temp.newFolder());val store=context.getSharedPreferences("ember",0)
        store.edit().putString("screenMargin","NaN").commit();assertEquals("0",Prefs(context).screenMargin)
        assertEquals("0",store.getString("screenMargin",null))
        store.edit().putString("screenMargin","500").commit();assertEquals("5",Prefs(context).screenMargin)
    }
    @Test fun invalidMarginBackupIsRejectedBeforeAnyDataIsWritten() {
        val context=PersistenceContext(temp.newFolder());val backup=BackupPersistence(context,FixtureSecrets());val before=backup.snapshot()
        val saved=before.getValue("prefs.ember").jsonObject
        for(value in listOf("NaN","Infinity","-1","99","bad")) {
            val payload=JsonObject(before+("prefs.ember" to JsonObject(saved+("screenMargin" to buildJsonObject {put("t","s");put("v",value)}))))
            assertTrue(runCatching {backup.restore(payload)}.exceptionOrNull() is Backup.BackupException)
            assertEquals(before,backup.snapshot())
        }
    }
    @Test fun displayProgressDropsNonFiniteValuesAndClampsFiniteOutliers() {
        for(value in listOf(Float.NaN,Float.NEGATIVE_INFINITY,Float.POSITIVE_INFINITY))assertNull(Meta(progress=value).safeProgress)
        assertNull(Meta().safeProgress)
        assertEquals(0f,Meta(progress=-10f).safeProgress!!,0f)
        assertEquals(1f,Meta(progress=10f).safeProgress!!,0f)
        assertEquals(.4f,Meta(progress=.4f).safeProgress!!,0f)
    }
    @Test fun safeDisplayProgressDoesNotChangeTheSavedMetadataSchema() {
        val encoded=Json.encodeToString(Meta(id="tt1",progress=.4f))
        assertFalse(encoded.contains("safeProgress"));assertTrue(encoded.contains("progress"))
    }
    @Test fun nonFinitePlaybackUpdatesDoNotOverwriteHealthySavedProgress() {
        LocalLibrary.resetForTest()
        try {
            LocalLibrary.started("tt1",Meta(id="tt1",name="Film",type="movie"))
            LocalLibrary.progress("tt1",.4f)
            for(value in listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY)) {
                LocalLibrary.progress("tt1",value)
                assertEquals(.4f,LocalLibrary.inProgress.single().progress!!,0f)
            }
        } finally {LocalLibrary.resetForTest()}
    }
    @Test fun repairedLegacyMarginCanBeExportedAndRestored() {
        val context=PersistenceContext(temp.newFolder())
        context.getSharedPreferences("ember",0).edit().putString("screenMargin","NaN").commit()
        assertEquals("0",Prefs(context).screenMargin)
        val payload=BackupPersistence(context,FixtureSecrets()).snapshot()
        val target=PersistenceContext(temp.newFolder());BackupPersistence(target,FixtureSecrets()).restore(payload)
        assertEquals("0",Prefs(target).screenMargin)
    }
    private suspend fun searching(defs:List<CatalogDef>,run:suspend(SearchState,MutableList<Throwable>)->Unit) {
        val errors=mutableListOf<Throwable>()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined+CoroutineExceptionHandler {_,error->errors+=error})
        try {run(SearchState(scope,AddonClient(),{emptyList()},{emptyList()}){defs},errors)} finally {scope.cancel()}
    }
    @Test fun failedSearchFilteringStopsLoadingAndDoesNotPublishUnfilteredTitles()=runBlocking {
        searching(listOf(catalog())) {state,errors->
            state.browse={_,_->listOf(Meta(id="restricted",name="Film"))}
            state.filterItems={error("fixture processing failure")}
            state.setQuery("film",immediate=true);yield()
            assertFalse(state.searching);assertTrue(state.searchRows.isEmpty());assertTrue(state.sourceUnavailable)
            assertTrue(state.health.entries.getValue(catalog().key).failed);assertTrue(errors.isEmpty())
        }
    }
    @Test fun oneFailingSearchFilterDoesNotCancelAnotherHealthySource()=runBlocking {
        searching(listOf(catalog("good"),catalog("bad"))) {state,errors->
            state.browse={def,_->listOf(Meta(id=def.id,name="Film"))}
            state.filterItems={items->if(items.single().id=="bad")error("fixture") else items}
            state.setQuery("film",immediate=true);yield()
            assertEquals("good",state.searchRows.single().items.single().id)
            assertFalse(state.searching);assertFalse(state.sourceUnavailable);assertNull(state.searchNote);assertTrue(errors.isEmpty())
        }
    }
    @Test fun sourceDiscoveryCallbackFailureRecoversWithoutAStuckSearch()=runBlocking {
        searching(emptyList()) {state,errors->
            state.providers={error("fixture source discovery")}
            state.setQuery("film",immediate=true);yield()
            assertFalse(state.searching);assertTrue(state.sourceUnavailable);assertTrue(state.searchNote!!.contains("Retry"));assertTrue(errors.isEmpty())
            state.providers={listOf(SearchProvider("manifest:good","Good"){listOf(catalog())})}
            state.browse={_,_->listOf(Meta(id="tt1",name="Film"))}
            state.setQuery("film",immediate=true);yield()
            assertFalse(state.searching);assertEquals("tt1",state.searchRows.single().items.single().id)
        }
    }
    @Test fun canceledSearchCannotClearTheNewQueriesLoadingState()=runBlocking {
        searching(listOf(catalog())) {state,_->
            val gate=CompletableDeferred<Unit>()
            state.browse={_,_->gate.await();emptyList()}
            state.setQuery("first",immediate=true);yield();assertTrue(state.searching)
            state.setQuery("second",immediate=true);yield();assertTrue(state.searching)
            gate.complete(Unit);yield();assertFalse(state.searching);assertEquals("second",state.searchQuery)
        }
    }
    @Test fun failedCatalogFilteringKeepsPaginationCursorForRetry()=runBlocking {
        val errors=mutableListOf<Throwable>()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined+CoroutineExceptionHandler {_,error->errors+=error})
        try {
            val state=DiscoverState(scope,AddonClient()){listOf(catalog())}
            val offsets=mutableListOf<Int>()
            state.browse={_,_,skip->offsets+=skip;listOf(Meta(id="tt1"))}
            state.filterItems={error("fixture processing")}
            state.openDiscover();yield()
            assertFalse(state.discoverLoading);assertTrue(state.failed);assertTrue(state.discoverItems.isEmpty());assertTrue(errors.isEmpty())
            state.filterItems={it};state.retry();yield()
            assertEquals(listOf(0,0),offsets);assertEquals("tt1",state.discoverItems.single().id);assertFalse(state.failed)
        } finally {scope.cancel()}
    }
}
