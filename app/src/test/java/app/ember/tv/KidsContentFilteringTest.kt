package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.state.*
import app.ember.tv.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KidsContentFilteringTest {
    @get:Rule val temp=TemporaryFolder()
    private val child=Profile("kids","Kids",kids=true,personal=false,lockedGenre="Animation",maxTvRating="TV-PG")
    private val movie=Meta(id="tmdb:movie:1",type="movie",name="Film",genres=listOf("Animation"),ageRating="PG")
    private val show=movie.copy(id="tmdb:tv:2",type="series",ageRating="TV-PG")
    private val json=Json {ignoreUnknownKeys=true}
    private fun catalog()=CatalogDef(AddonInfo("https://fixture.example","https://fixture.example","Fixture",listOf("movie"),null,emptyList(),emptyList()),
        "movie","test","Test",emptyList(),false,true,false,false,supportsSkip=true)
    private fun document(value:String)=json.parseToJsonElement(value)

    @Test fun adultsAndKidsWithoutLocksKeepExistingCatalogBehavior() {
        assertTrue(KidsContentPolicy.allows(child.copy(kids=false),Meta()))
        assertTrue(KidsContentPolicy.allows(child.copy(lockedGenre=null,maxTvRating=null),Meta()))
        assertFalse(KidsContentPolicy.restricted(Profile("old","Old",kids=true)))
    }
    @Test fun genreRequiresMetadataRatherThanWordsInTheTitle() {
        val lock=child.copy(maxTvRating=null)
        assertTrue(KidsContentPolicy.allows(lock,movie))
        assertFalse(KidsContentPolicy.allows(lock,movie.copy(genres=listOf("Horror"))))
        assertFalse(KidsContentPolicy.allows(lock,movie.copy(name="Children's animation",genres=null)))
    }
    @Test fun combinedProviderGenreNamesAndSciFiAliasesAreSupported() {
        assertTrue(KidsContentPolicy.allows(child.copy(lockedGenre="Adventure"),movie.copy(genres=listOf("Action & Adventure"))))
        assertTrue(KidsContentPolicy.allows(child.copy(lockedGenre="Science Fiction"),movie.copy(genres=listOf("Sci-Fi & Fantasy"))))
        assertFalse(KidsContentPolicy.allows(child.copy(lockedGenre="Kids"),movie.copy(genres=listOf("Kidnapping"))))
    }
    @Test fun genreAndAgeMustBothMatchWhenConfigured() {
        assertTrue(KidsContentPolicy.allows(child,movie))
        assertFalse(KidsContentPolicy.allows(child,movie.copy(ageRating="R")))
        assertFalse(KidsContentPolicy.allows(child,movie.copy(genres=listOf("Comedy"))))
    }
    @Test fun ratingCeilingUsesRecognizedMovieAndTvCategories() {
        val lock=child.copy(lockedGenre=null,maxTvRating="TV-14")
        listOf("TV-Y","TV-Y7-FV","TV-G","TV-PG","TV-14","G","PG","PG-13"," US: TV-14 ").forEach {
            assertTrue(it,KidsContentPolicy.allows(lock,show.copy(ageRating=it)))
        }
        listOf("TV-MA","R","NC-17").forEach {assertFalse(it,KidsContentPolicy.allows(lock,show.copy(ageRating=it)))}
        assertFalse(KidsContentPolicy.allows(child.copy(maxTvRating="TV-Y"),show.copy(ageRating="TV-Y7")))
    }
    @Test fun reviewScoresUnratedAndUnknownCertificationsNeverPassAnAgeLock() {
        listOf(null,"","7.9","92%","NR","Unrated","12","TV-UNKNOWN").forEach {
            assertEquals(KidsContentPolicy.Decision.NEEDS_METADATA,KidsContentPolicy.decision(child,movie.copy(ageRating=it)))
            assertFalse(KidsContentPolicy.allows(child,movie.copy(ageRating=it)))
        }
    }
    @Test fun explicitAdultFlagInvalidSavedRulesAndNonMediaTypesFailClosed() {
        assertFalse(KidsContentPolicy.allows(child,movie.copy(adult=true)))
        assertFalse(KidsContentPolicy.allows(child.copy(lockedGenre="unknown"),movie))
        assertFalse(KidsContentPolicy.allows(child.copy(maxTvRating="unknown"),movie))
        assertFalse(KidsContentPolicy.allows(child,movie.copy(type="channel")))
    }
    @Test fun locksHideLiveWithoutChangingTheSavedLivePreference() {
        assertTrue(child.live)
        assertFalse(KidsContentPolicy.liveAllowed(child))
        assertTrue(KidsContentPolicy.liveAllowed(child.copy(lockedGenre=null,maxTvRating=null)))
    }
    @Test fun kidsToKidsSwitchingAlsoRequiresThePin() {
        assertTrue(KidsContentPolicy.switchNeedsPin(child,child.copy(id="other")))
        assertTrue(KidsContentPolicy.switchNeedsPin(child,Profile("parent","Parent")))
        assertFalse(KidsContentPolicy.switchNeedsPin(child,child))
        assertFalse(KidsContentPolicy.switchNeedsPin(Profile("parent","Parent"),child))
    }
    @Test fun profileStateDoesNotSwitchUntilThePinActionRuns() {
        val prefs=Prefs(PersistenceContext(temp.newFolder()))
        prefs.profiles=listOf(child,child.copy(id="other",lockedGenre=null,maxTvRating=null))
        prefs.currentProfile=child.id
        var approval:(()->Unit)?=null
        val state=ProfilesState(prefs,{emptyList()},{emptyList()},{_,action->approval=action},{},{})
        state.switchProfile("other")
        assertEquals(child.id,state.profileId)
        assertNotNull(approval)
        approval!!.invoke()
        assertEquals("other",state.profileId)
    }
    @Test fun playbackRejectsStaleProfilesMissingMetadataAndLiveInLockedProfiles() {
        assertTrue(KidsContentPolicy.playbackAllowed(child,child.id,movie))
        assertFalse(KidsContentPolicy.playbackAllowed(child,"parent",movie))
        assertFalse(KidsContentPolicy.playbackAllowed(child,null,null))
        assertFalse(KidsContentPolicy.playbackAllowed(child,child.id,movie,live=true))
        assertFalse(KidsContentPolicy.playbackAllowed(child,child.id,movie.copy(ageRating="R")))
        assertTrue(KidsContentPolicy.playbackAllowed(Profile("parent","Parent"),null,null))
    }
    @Test fun oldProfilesLoadWithLocksOff() {
        val old=json.decodeFromString<Profile>("""{"id":"old","name":"Old","kids":true}""")
        assertNull(old.lockedGenre);assertNull(old.maxTvRating)
    }
    @Test fun profileLocksAndPinRoundTripThroughBackupAndRestart() {
        val context=PersistenceContext(temp.newFolder());val prefs=Prefs(context)
        prefs.profiles=listOf(child);prefs.currentProfile=child.id;prefs.pinHash="fixture-hash"
        val payload=BackupPersistence(context,FixtureSecrets()).snapshot()
        val restored=PersistenceContext(temp.newFolder())
        BackupPersistence(restored,FixtureSecrets()).restore(payload)
        assertEquals(child,Prefs(restored).profiles.single())
        assertEquals("fixture-hash",Prefs(restored).pinHash)
        assertTrue(KidsContentPolicy.allows(Prefs(restored).profiles.single(),movie))
    }
    @Test fun contentLocksCannotBeEnabledWithoutAPinIncludingArchivedKidsRules() {
        val prefs=Prefs(PersistenceContext(temp.newFolder()))
        prefs.profiles=listOf(child.copy(kids=false));prefs.currentProfile=child.id
        val state=ProfilesState(prefs,{emptyList()},{emptyList()},{_,_->},{},{})
        state.updateProfile(child.id){it.copy(kids=true)}
        assertFalse(state.profile.kids)
        prefs.pinHash="fixture-hash"
        state.updateProfile(child.id){it.copy(kids=true)}
        assertTrue(state.profile.kids)
    }
    @Test fun pinRemovalIsBlockedUntilEveryKidsLockIsRemoved() {
        val prefs=Prefs(PersistenceContext(temp.newFolder()));prefs.profiles=listOf(child);prefs.pinHash="fixture-hash"
        val state=PinState(prefs,{})
        state.setPin(null);assertTrue(state.pinSet)
        prefs.profiles=listOf(child.copy(lockedGenre=null,maxTvRating=null))
        state.setPin(null);assertFalse(state.pinSet)
    }
    @Test fun restoringAnUnprotectedContentLockIsRejectedBeforeChangingSavedData() {
        val from=PersistenceContext(temp.newFolder());Prefs(from).profiles=listOf(child)
        try {BackupPersistence(from,FixtureSecrets()).snapshot();fail("Unprotected lock exported")}
        catch(_:Backup.BackupException) { }
        Prefs(from).pinHash="fixture-hash"
        val valid=BackupPersistence(from,FixtureSecrets()).snapshot()
        val payload=JsonObject(valid+("prefs.ember" to JsonObject((valid.getValue("prefs.ember") as JsonObject)-"pinHash")))
        val to=PersistenceContext(temp.newFolder());Prefs(to).theme="day"
        try {BackupPersistence(to,FixtureSecrets()).restore(payload);fail("Unprotected lock restored")}
        catch(_:Backup.BackupException) {assertEquals("day",Prefs(to).theme)}
    }
    @Test fun addonAgeFieldsAreSeparateFromImdbReviewScores() {
        assertEquals("TV-14",addonAgeRating(document("""{"contentRating":"TV-14","imdbRating":"8.8"}""")))
        assertEquals("R",addonAgeRating(document("""{"certification":"G","mpaa":"R"}""")))
        assertNull(addonAgeRating(document("""{"imdbRating":"8.8","rating":"7.9"}""")))
    }
    @Test fun cachedAdultRowsCannotFlashThroughAChildsNewLock() {
        val def=catalog();val locked=child.copy(catalogs=setOf(def.key))
        val row=CatalogRow("Fixture","Saved",listOf(movie,movie.copy(id="adult",ageRating="R"),movie.copy(id="unknown",ageRating=null)),catalogKey=def.key)
        assertEquals(listOf(movie),visibleCatalogRows(listOf(row),locked,listOf(def.addon.url),emptyMap()).single().items)
        assertEquals(row.items,visibleCatalogRows(listOf(row),locked.copy(kids=false),listOf(def.addon.url),emptyMap()).single().items)
    }
    @Test fun switchingToALockedProfileCanClearOldScreensaverTitleArt() {
        val context=PersistenceContext(temp.newFolder());val feed=app.ember.tv.screensaver.ScreensaverFeed(context)
        feed.update(listOf(movie.copy(background="https://fixture.example/poster.jpg")))
        assertEquals(1,feed.homeSlides().size)
        feed.update(emptyList(),clearEmpty=true)
        assertTrue(feed.homeSlides().isEmpty())
    }
    @Test fun fullyKnownMetadataAndUnlockedProfilesNeedNoExtraRequests()=runBlocking {
        var requests=0
        val repository=KidsMetadata({"fixture"},{requests++;error("Must not fetch")})
        assertEquals(listOf(movie),repository.filter(child,listOf(movie)))
        val raw=movie.copy(genres=null,ageRating=null)
        assertEquals(listOf(raw),repository.filter(child.copy(kids=false),listOf(raw)))
        assertEquals(0,requests)
    }
    @Test fun missingKeysAndUnverifiedIdsHideUnknownTitlesWithoutNetwork()=runBlocking {
        var requests=0
        val withoutKey=KidsMetadata({""},{requests++;error("Must not fetch")})
        assertTrue(withoutKey.filter(child,listOf(movie.copy(ageRating=null))).isEmpty())
        val opaque=KidsMetadata({"fixture"},{requests++;error("Must not fetch")})
        assertTrue(opaque.filter(child,listOf(movie.copy(id="provider:unknown",ageRating=null))).isEmpty())
        assertEquals(0,requests)
    }
    @Test fun tvMetadataUsesOnlyUsCertificationsAndPreservesTheOriginalTitle()=runBlocking {
        var requests=0
        val repository=KidsMetadata({"fixture"},{url->
            requests++;assertTrue(url.contains("/tv/2?"));assertTrue(url.contains("content_ratings"))
            document("""{"genres":[{"name":"Animation"}],"adult":false,"content_ratings":{"results":[{"iso_3166_1":"DE","rating":"TV-MA"},{"iso_3166_1":"US","rating":"TV-PG"}]}}""")
        })
        val unknown=show.copy(genres=null,ageRating=null)
        val full=repository.filter(child,listOf(unknown)).single()
        assertEquals("TV-PG",full.ageRating);assertEquals(unknown.name,full.name)
        assertEquals(full,repository.filter(child,listOf(unknown)).single());assertEquals(1,requests)
    }
    @Test fun multipleMovieCertificationsChooseTheMostRestrictiveKnownCut()=runBlocking {
        val repository=KidsMetadata({"fixture"},{document("""{"genres":[{"name":"Animation"}],"release_dates":{"results":[{"iso_3166_1":"US","release_dates":[{"certification":"G"},{"certification":"R"}]}]}}""")})
        assertTrue(repository.filter(child,listOf(movie.copy(ageRating=null))).isEmpty())
    }
    @Test fun metadataFailuresAreHiddenAndDoNotRepeatOnEveryRead()=runBlocking {
        var requests=0
        val repository=KidsMetadata({"fixture"},{requests++;error("offline")})
        repeat(2){assertTrue(repository.filter(child,listOf(movie.copy(ageRating=null))).isEmpty())}
        assertEquals(1,requests)
    }
    @Test fun metadataNeverDowngradesExplicitAdultEvidence()=runBlocking {
        val repository=KidsMetadata({"fixture"},{error("Must not fetch")})
        assertTrue(repository.filter(child,listOf(movie.copy(adult=true))).isEmpty())
    }
    @Test fun metadataConcurrencyIsBoundedAndResultsKeepInputOrder()=runBlocking {
        var active=0;var maximum=0
        val repository=KidsMetadata({"fixture"},{
            active++;maximum=maxOf(maximum,active);delay(10);active--
            document("""{"genres":[{"name":"Animation"}],"release_dates":{"results":[{"iso_3166_1":"US","release_dates":[{"certification":"PG"}]}]}}""")
        })
        val input=(1..6).map {movie.copy(id="tmdb:movie:$it",ageRating=null)}
        assertEquals(input.map {it.id},repository.filter(child,input).map {it.id})
        assertEquals(2,maximum)
    }
    @Test fun callerCancellationIsNeverSwallowedByTheMetadataFallback()=runBlocking {
        val started=CompletableDeferred<Unit>()
        val repository=KidsMetadata({"fixture"},{started.complete(Unit);awaitCancellation()})
        val job=launch {repository.filter(child,listOf(movie.copy(ageRating=null)))}
        started.await();job.cancelAndJoin();assertTrue(job.isCancelled)
    }
    @Test fun filteredEmptyCatalogPageDoesNotEndPaginationOrSkipRawOffsets()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val def=catalog();val offsets=mutableListOf<Int>()
            val state=DiscoverState(scope,AddonClient()){listOf(def)}.apply {filterItems={items->items.filter {KidsContentPolicy.allows(child,it)}}}
            state.browse={_,_,skip->offsets+=skip;when(skip){0->listOf(movie.copy(id="bad1",ageRating="R"),movie.copy(id="bad2",ageRating="R"));2->listOf(movie);else->emptyList()}}
            state.openDiscover();yield()
            assertTrue(state.discoverItems.isEmpty());assertFalse(state.discoverEnd)
            state.loadMoreDiscover();yield()
            assertEquals(listOf(0,2),offsets);assertEquals(listOf(movie),state.discoverItems)
            state.loadMoreDiscover();yield();assertTrue(state.discoverEnd)
        } finally {scope.cancel()}
    }
    @Test fun aWorkingSearchFilteredToZeroDoesNotBecomeSourceUnavailable()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val state=SearchState(scope,AddonClient(),{emptyList()},{emptyList()}){listOf(catalog())}.apply {filterItems={items->items.filter {KidsContentPolicy.allows(child,it)}}}
            state.browse={_,_->listOf(movie.copy(ageRating="R"))}
            state.setQuery("Film",immediate=true);yield()
            assertTrue(state.searchRows.isEmpty());assertFalse(state.sourceUnavailable)
            assertTrue(state.searchNote!!.contains("No matches"))
        } finally {scope.cancel()}
    }
    @Test fun pillTextHasAccessibleContrastAcrossAllAccentModeCombinations() {
        for(accent in 0..ColorThemes.MODERN_EMBER)for(day in listOf(false,true))for(oled in listOf(false,true)) {
            val palette=emberColorScheme(accent,day,oled)
            listOf(palette.primary,Color(0xFFF1F3F4),Color(0xFF202124),ModernEmberColors.Focus,ShadowEmberColors.Focus).forEach {surface->
                val text=readablePillText(surface)
                val a=surface.luminance();val b=text.luminance()
                assertTrue("$accent/$day/$oled/$surface",(maxOf(a,b)+.05f)/(minOf(a,b)+.05f)>=4.5f)
            }
        }
    }
    @Test fun oneFocusEdgeProvidesContrastOnEverySampledLuminance() {
        for(step in 0..100) {
            val value=step/100f
            val background=Color(value,value,value)
            val edge=ControlStateCue.edge(background).luminance();val back=background.luminance()
            assertTrue((maxOf(edge,back)+.05f)/(minOf(edge,back)+.05f)>=3f)
        }
    }
}
