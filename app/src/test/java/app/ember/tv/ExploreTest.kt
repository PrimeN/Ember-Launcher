package app.ember.tv

import android.content.ContextWrapper
import android.content.SharedPreferences
import app.ember.tv.data.*
import app.ember.tv.discover.*
import app.ember.tv.state.ExploreState
import app.ember.tv.state.DiscoverSection
import app.ember.tv.tracker.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.LocalDate

class ExploreTest {
    @Test fun collectionModeSurvivesExplorePauseButCatalogRoutingAndProfileResetClearIt() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = ExploreState(scope, Source(), memoryPrefs(), { "one" }, { true }, { "CA" })
        try {
            state.section = DiscoverSection.COLLECTIONS
            state.pause()
            assertEquals(DiscoverSection.COLLECTIONS, state.section)
            state.addonMode = true
            assertEquals(DiscoverSection.CATALOGS, state.section)
            state.section = DiscoverSection.COLLECTIONS
            state.reset()
            assertEquals(DiscoverSection.EXPLORE, state.section)
        } finally { scope.cancel() }
    }

    private val today = LocalDate.of(2026, 10, 25)
    private fun title(id: Long, kind: ExploreKind = ExploreKind.MOVIE, rating: Double = 8.0,
        genres: Set<Int> = setOf(35), date: String? = "2020-01-01", votes: Int = 1000, adult: Boolean = false) =
        ExploreTitle(Meta(id = "tmdb:${kind.path}:$id", type = if (kind == ExploreKind.MOVIE) "movie" else "series", name = "Title $id", tmdbId = id),
            genres, date, 10000.0 - id, rating, votes, adult)

    private inner class Source : ExploreSource {
        override var configured = true
        var generated = false
        var pageFailure = false
        var pages = mutableMapOf<Pair<ExploreKind, Int>, ExplorePage>()
        var calls = mutableListOf<Triple<ExploreKind, Int, String>>()
        var seeds: List<ExploreTitle>? = null
        var awardIds = setOf<Long>()
        var awardFailure = false
        var extraIds = emptySet<Long>()
        var gate: (suspend () -> Unit)? = null
        override suspend fun page(query: ExploreQuery, kind: ExploreKind, region: String, page: Int): ExplorePage {
            calls += Triple(kind, page, region); gate?.invoke()
            if (pageFailure) error("offline")
            return if (generated) ExplorePage((1..20).map { title(((page - 1) * 20 + it).toLong(), kind) }, 10)
                else pages[kind to page] ?: ExplorePage(emptyList(), page)
        }
        override suspend fun people(query: ExploreQuery, kind: ExploreKind) = seeds
        override suspend fun matchesExtras(title: ExploreTitle, query: ExploreQuery, kind: ExploreKind, region: String) = title.tmdb in extraIds
        override suspend fun awards(ids: List<Long>, query: ExploreQuery): Set<Long> {
            if (awardFailure) error("unavailable")
            return ids.filter { it in awardIds }.toSet()
        }
    }

    @Test fun defaultMixWaitsForFiftyOfEachEvenWhenMoviePagesContainExtras() = runBlocking {
        val source = Source().apply { generated = true }
        val session = ExploreSession(ExploreQuery(), "CA", source, today = today)
        session.advance(budget = 5)
        assertEquals(100, session.results.size)
        assertFalse("60 movies plus 40 shows must not finish the balanced chart", session.complete)
        session.advance()
        assertTrue(session.complete)
        assertEquals(50, session.results.count { it.meta.type == "movie" })
        assertEquals(50, session.results.count { it.meta.type == "series" })
        assertEquals(listOf("movie", "series", "movie", "series"), session.results.take(4).map { it.meta.type })
    }

    @Test fun exhaustedTypeAllowsTheOtherTypeToFillTheHundred() = runBlocking {
        val source = Source()
        for (p in 1..5) source.pages[ExploreKind.MOVIE to p] = ExplorePage((1..20).map { title(((p - 1) * 20 + it).toLong()) }, 5)
        val session = ExploreSession(ExploreQuery(), "CA", source, today = today)
        session.advance()
        assertTrue(session.complete); assertEquals(100, session.results.size)
        assertTrue(session.results.all { it.meta.type == "movie" })
    }

    @Test fun sparseResultsStaySparseWithoutWeakeningTheCriteria() = runBlocking {
        val source = Source().apply { pages[ExploreKind.MOVIE to 1] = ExplorePage(listOf(title(1, rating = 9.0), title(2, rating = 6.0)), 1) }
        val q = ExploreQuery(kind = ExploreKind.MOVIE, minRating = 8.0)
        val session = ExploreSession(q, "CA", source, today = today)
        session.advance()
        assertTrue(session.complete); assertEquals(listOf(1L), session.results.map { it.tmdb }); assertEquals(q, session.query)
    }

    @Test fun filtersApplyAcrossPagesBeforeTheCapAndResumeWithoutDroppingMatches() = runBlocking {
        val source = Source()
        for (p in 1..8) source.pages[ExploreKind.MOVIE to p] = ExplorePage((1..20).map {
            title(((p - 1) * 20 + it).toLong(), rating = if (p < 3) 5.0 else 8.5)
        }, 8)
        val session = ExploreSession(ExploreQuery(kind = ExploreKind.MOVIE, minRating = 8.0), "CA", source, today = today)
        session.advance(budget = 2)
        assertTrue(session.results.isEmpty()); assertFalse(session.complete)
        session.advance()
        assertEquals((41L..140L).toList(), session.results.map { it.tmdb })
        assertTrue(session.complete)
    }

    @Test fun failedAwardVerificationDoesNotAdvanceOrDiscardARetryPage() = runBlocking {
        val source = Source().apply {
            pages[ExploreKind.MOVIE to 1] = ExplorePage(listOf(title(1), title(2)), 1)
            awardFailure = true; awardIds = setOf(2)
        }
        val session = ExploreSession(ExploreQuery(kind = ExploreKind.MOVIE, oscars = OscarMode.WIN), "CA", source, today = today)
        try { session.advance(); fail("must fail") } catch (_: IllegalStateException) { }
        assertEquals(0, session.pagesChecked); assertTrue(session.results.isEmpty())
        source.awardFailure = false
        session.advance()
        assertEquals(listOf(2L), session.results.map { it.tmdb })
        assertEquals(listOf(1, 1), source.calls.map { it.second })
    }

    @Test fun repeatedProviderPageDoesNotHideLaterUniqueTitles() = runBlocking {
        val source = Source().apply {
            pages[ExploreKind.MOVIE to 1] = ExplorePage(listOf(title(1)), 3)
            pages[ExploreKind.MOVIE to 2] = ExplorePage(listOf(title(1)), 3)
            pages[ExploreKind.MOVIE to 3] = ExplorePage(listOf(title(2)), 3)
        }
        val session = ExploreSession(ExploreQuery(kind = ExploreKind.MOVIE), "CA", source, today = today)
        session.advance()
        assertEquals(listOf(1L, 2L), session.results.map { it.tmdb }); assertTrue(session.complete)
    }

    @Test fun creditCandidatesStillRespectCompaniesAndStreamingFilters() = runBlocking {
        val source = Source().apply { seeds = listOf(title(1), title(2)); extraIds = setOf(2) }
        val session = ExploreSession(ExploreQuery(kind = ExploreKind.MOVIE, actors = listOf(ExploreRef(1, "Person")),
            companies = listOf(ExploreRef(2,"Studio"))), "CA", source, today = today)
        session.advance()
        assertEquals(listOf(2L), session.results.map { it.tmdb }); assertTrue(source.calls.isEmpty())
    }

    @Test fun groupedGenresSupportAnyAndAllWithoutMixingMovieAndTvIds() {
        val q = ExploreQuery(genres = setOf("action", "comedy"), allGenres = true)
        assertTrue(title(1, genres = setOf(12,35)).matches(q, ExploreKind.MOVIE, today))
        assertFalse(title(1, genres = setOf(28)).matches(q, ExploreKind.MOVIE, today))
        assertTrue(title(1, ExploreKind.TV, genres = setOf(10759,35)).matches(q, ExploreKind.TV, today))
        assertFalse(title(1, ExploreKind.TV, genres = setOf(28,35)).matches(q, ExploreKind.TV, today))
        assertTrue(title(1, genres = setOf(28)).matches(q.copy(allGenres = false), ExploreKind.MOVIE, today))
    }

    @Test fun futureAdultUndatedAndLowVoteTitlesCannotLeadTheChart() {
        val q = ExploreQuery(sort = ExploreSort.RATING)
        assertFalse(title(1, date = "2027-01-01").matches(q, ExploreKind.MOVIE, today))
        assertFalse(title(1, date = null).matches(q, ExploreKind.MOVIE, today))
        assertFalse(title(1, adult = true).matches(q, ExploreKind.MOVIE, today))
        assertFalse(title(1, votes = 499).matches(q, ExploreKind.MOVIE, today))
        assertTrue(title(1, ExploreKind.TV, votes = 200).matches(q, ExploreKind.TV, today))
        assertFalse(title(1, ExploreKind.TV, votes = 199).matches(q, ExploreKind.TV, today))
    }

    @Test fun invalidQueriesAreRejectedIncludingOscarTvAndNonFiniteRatings() {
        assertNotNull(ExploreQuery(fromYear=2020,toYear=2000).error(today))
        assertNotNull(ExploreQuery(minRating=Double.NaN).error(today))
        assertNotNull(ExploreQuery(minRating=9.0,maxRating=7.0).error(today))
        assertNotNull(ExploreQuery(minMovieVotes=-1).error(today))
        assertNotNull(ExploreQuery(kind=ExploreKind.TV,oscars=OscarMode.WIN).error(today))
        assertNotNull(ExploreQuery(actors=listOf(ExploreRef(-1,"Invalid"))).error(today))
        assertNotNull(ExploreQuery(genres=setOf("unknown")).error(today))
        assertNull(ExploreQuery().error(today))
    }

    @Test fun nativeQueriesUseTypeSpecificDatesRegionalServicesAndSafeGenreSupersets() {
        val q = ExploreQuery(sort=ExploreSort.RATING, genres=setOf("action","comedy"), allGenres=true,
            fromYear=1990,toYear=1999, providers=listOf(ExploreRef(8,"Netflix")), companies=listOf(ExploreRef(27,"Studio")))
        val movie = ExploreRepository.parameters(q,ExploreKind.MOVIE,"CA",2,today)
        assertEquals("1990-01-01",movie["primary_release_date.gte"])
        assertEquals("1999-12-31",movie["primary_release_date.lte"])
        assertEquals("28|12|35",movie["with_genres"])
        assertEquals("CA",movie["watch_region"]);assertEquals("8",movie["with_watch_providers"])
        assertEquals("flatrate|free|ads",movie["with_watch_monetization_types"])
        assertEquals("500",movie["vote_count.gte"]);assertEquals("false",movie["include_adult"])
        val tv = ExploreRepository.parameters(q.copy(sort=ExploreSort.NEWEST,toYear=null),ExploreKind.TV,"US",1,today)
        assertEquals("1990-01-01",tv["first_air_date.gte"]);assertEquals("2026-10-25",tv["first_air_date.lte"])
        assertEquals("first_air_date.desc",tv["sort_by"]);assertFalse(tv.containsKey("with_crew"))
    }

    @Test fun oscarQueriesLinkRecipientAwardsToTheSpecificWorkAndSeparateWinsFromNominations() {
        val win = ExploreRepository.awardQuery(listOf(1,2),ExploreQuery(oscars=OscarMode.WIN))
        assertTrue(win.contains("pq:P1686 ?film"));assertTrue(win.contains("wdt:P4947 ?tmdb"))
        assertFalse(win.contains("P1411"));assertTrue(win.contains("wd:Q19020"))
        val nominee = ExploreRepository.awardQuery(listOf(1),ExploreQuery(oscars=OscarMode.NOMINATED,bestPicture=true))
        assertTrue(nominee.contains("P1411"));assertTrue(nominee.contains("wd:Q102427"))
        assertThrows(IllegalArgumentException::class.java) { ExploreRepository.awardQuery(listOf(-1),ExploreQuery()) }
    }

    @Test fun directorSeedsExcludeProducerCreditsAndActorsMustIntersectTheDirectorSet() = runBlocking {
        val movie = { id:Int -> """{"id":$id,"title":"Film $id","release_date":"2020-01-01","genre_ids":[35],"vote_average":8,"vote_count":1000}""" }
        val source = ExploreRepository(key={"fixture"},get={url,_ ->
            Json.parseToJsonElement(if(url.contains("person/1/")) """{"cast":[${movie(10)},${movie(11)}],"crew":[]}"""
                else """{"cast":[],"crew":[${movie(10).dropLast(1)},"job":"Producer"},${movie(11).dropLast(1)},"job":"Director"}]}""")
        })
        val seeds=source.people(ExploreQuery(actors=listOf(ExploreRef(1,"Actor")),directors=listOf(ExploreRef(2,"Director"))),ExploreKind.MOVIE)!!
        assertEquals(listOf(11L),seeds.map{it.tmdb})
    }

    @Test fun missingOrWrongRegionOffersCannotSatisfyStreamingSelection() = runBlocking {
        val source=ExploreRepository(key={"fixture"},get={_,_->Json.parseToJsonElement("""{"results":{"US":{"flatrate":[{"provider_id":8}]},"CA":{"rent":[{"provider_id":8}]}}}""")})
        val q=ExploreQuery(providers=listOf(ExploreRef(8,"Netflix")))
        assertFalse(source.matchesExtras(title(1),q,ExploreKind.MOVIE,"CA"))
        assertTrue(source.matchesExtras(title(1),q.copy(includeRentBuy=true),ExploreKind.MOVIE,"CA"))
        assertFalse(source.matchesExtras(title(1),q,ExploreKind.MOVIE,"GB"))
    }

    @Test fun wikidataResultsCannotIntroduceTitlesOutsideTheRequestedBatch() = runBlocking {
        val source=ExploreRepository(key={"fixture"},get={_,headers->
            assertEquals("application/sparql-results+json",headers["Accept"])
            Json.parseToJsonElement("""{"results":{"bindings":[{"tmdb":{"value":"1"}},{"tmdb":{"value":"999"}}]}}""")
        })
        assertEquals(setOf(1L),source.awards(listOf(1,2),ExploreQuery(oscars=OscarMode.WIN)))
    }

    @Test fun replacingAnInFlightQueryCancelsOldPublicationAndStartsTheNewRequest() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val gate=CompletableDeferred<Unit>();val source=Source()
        source.pages[ExploreKind.MOVIE to 1]=ExplorePage(listOf(title(1)),1)
        source.gate={withContext(NonCancellable){gate.await()}}
        val state=ExploreState(scope,source,memoryPrefs(),{"adult"},{true},{"CA"})
        try{
            state.apply(ExploreQuery(kind=ExploreKind.MOVIE));assertTrue(state.loading)
            source.gate=null
            state.apply(ExploreQuery(kind=ExploreKind.TV));assertFalse(state.loading)
            gate.complete(Unit);yield()
            assertEquals(ExploreKind.TV,state.query.kind);assertTrue(state.results.isEmpty());assertTrue(state.complete)
        }finally{gate.complete(Unit);scope.cancel()}
    }

    @Test fun profileResetRejectsNonCooperativeLateResults() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val gate=CompletableDeferred<Unit>()
        var profile="adult";var allowed=true
        val source=Source().apply{generated=true;this.gate={withContext(NonCancellable){gate.await()}}}
        val state=ExploreState(scope,source,memoryPrefs(),{profile},{allowed},{"CA"})
        try{
            state.open();assertTrue(state.loading)
            profile="kids";allowed=false;state.reset();gate.complete(Unit);yield()
            assertFalse(state.loading);assertTrue(state.results.isEmpty());assertTrue(state.saved.isEmpty());assertTrue(state.homeRows.isEmpty())
        }finally{gate.complete(Unit);scope.cancel()}
    }

    @Test fun savedSearchesAreProfileScopedAndOverwritingKeepsThePinnedIdentity() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val prefs=memoryPrefs();var profile="one"
        val state=ExploreState(scope,Source(),prefs,{profile},{true},{"CA"})
        try{
            state.open();assertNull(state.save("Favourites"));val id=state.saved.single().id
            state.toggleHome(id);assertTrue(state.saved.single().home)
            assertNull(state.save("favourites"));assertEquals(id,state.saved.single().id);assertTrue(state.saved.single().home)
            profile="two";state.reset();state.open();assertTrue(state.saved.isEmpty())
            assertEquals(1,prefs.explorePresets("one").size);assertTrue(prefs.explorePresets("two").isEmpty())
            assertNotNull(state.save("  "))
        }finally{scope.cancel()}
    }

    @Test fun disabledPinnedRowsDoNotFetchAndTheirLayoutCanBeMovedIndependently() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val prefs=memoryPrefs();val source=Source()
        prefs.setExplorePresets("one",listOf(SavedExplore("a","A",ExploreQuery(kind=ExploreKind.MOVIE),home=true)))
        val state=ExploreState(scope,source,prefs,{"one"},{true},{"CA"})
        try{
            state.refreshHome{false};assertTrue(source.calls.isEmpty());assertTrue(state.homeRows.isEmpty())
            val sections=app.ember.tv.state.HomeSectionsState(prefs,{}, {},extraSections={state.saved.map{
                app.ember.tv.state.HomeSectionsState.SectionInfo(it.section,it.name,"") to it.home}})
            assertTrue(sections.sectionOn("explore:a"));sections.toggleSection("explore:a");assertFalse(sections.sectionOn("explore:a"))
            sections.moveSection("explore:a",-1);assertFalse(sections.sectionOn("explore:a"))
        }finally{scope.cancel()}
    }

    @Test fun failedSavedHomeRefreshKeepsCachedRowsAndCanRetryImmediately() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val prefs = memoryPrefs(); val source = Source().apply { generated = true }; var now = 1_000L
        prefs.setExplorePresets("one", listOf(
            SavedExplore("a", "Movies", ExploreQuery(kind = ExploreKind.MOVIE), home = true),
            SavedExplore("b", "Shows", ExploreQuery(kind = ExploreKind.TV), home = true)))
        val state = ExploreState(scope, source, prefs, { "one" }, { true }, { "CA" }, clock = { now })
        try {
            state.refreshHome { true }; assertEquals(2, state.homeRows.size)
            val cached = state.homeRows
            now += 900_001; source.pageFailure = true
            state.refreshHome { true }
            assertEquals(cached, state.homeRows); assertNotNull(state.homeError)
            val calls = source.calls.size; source.pageFailure = false
            state.refreshHome { true }
            assertTrue(source.calls.size > calls); assertNull(state.homeError)
            assertEquals(2, state.homeRows.size)
        } finally { scope.cancel() }
    }

    @Test fun refreshingOneSavedRowDoesNotRemoveAnotherRowStillLoading() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val prefs = memoryPrefs(); val source = Source().apply { generated = true }; var now = 1_000L
        val gate = CompletableDeferred<Unit>()
        prefs.setExplorePresets("one", listOf(
            SavedExplore("a", "Movies", ExploreQuery(kind = ExploreKind.MOVIE), home = true),
            SavedExplore("b", "Shows", ExploreQuery(kind = ExploreKind.TV), home = true)))
        val state = ExploreState(scope, source, prefs, { "one" }, { true }, { "CA" }, clock = { now })
        try {
            state.refreshHome { true }; assertEquals(2, state.homeRows.size)
            now += 900_001; var requests = 0
            source.gate = { if (++requests == 2) gate.await() }
            state.refreshHome { true }
            assertEquals(listOf("explore:a", "explore:b"), state.homeRows.map { it.catalogKey })
            gate.complete(Unit); yield()
        } finally { gate.complete(Unit); scope.cancel() }
    }

    @Test fun savedHomeCacheCannotLeakOldRegionalMatchesDuringNewRegionLoad() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val prefs = memoryPrefs(); val source = Source().apply { generated = true }; var region = "CA"
        val gate = CompletableDeferred<Unit>()
        prefs.setExplorePresets("one", listOf(SavedExplore("a", "Movies", ExploreQuery(kind = ExploreKind.MOVIE), home = true)))
        val state = ExploreState(scope, source, prefs, { "one" }, { true }, { region })
        try {
            state.refreshHome { true }; assertEquals(1, state.homeRows.size)
            source.gate = { gate.await() }; region = "US"
            state.refreshHome { true }; assertTrue(state.homeRows.isEmpty())
            gate.complete(Unit); yield()
            assertEquals("Saved Discover · US", state.homeRows.single().addonName)
        } finally { gate.complete(Unit); scope.cancel() }
    }

    @Test fun changedCountryStartsANewSearchRatherThanReusingOldAvailability() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val source=Source();var country="CA"
        val state=ExploreState(scope,source,memoryPrefs(),{"one"},{true},{country})
        try{state.open();country="US";state.open();assertTrue(source.calls.any{it.third=="CA"});assertTrue(source.calls.any{it.third=="US"})}
        finally{scope.cancel()}
    }

    @Test fun tmdbLookupUsesTheCredentialInsteadOfItsCacheIdentifierAndRetriesFailures() = runBlocking {
        val id=987654321L
        assertNull(Tmdb.lookupUsing(Kind.MOVIE,id,"fixture-key"){error("offline")})
        var called=false
        val result=Tmdb.lookupUsing(Kind.MOVIE,id,"fixture-key"){url->
            assertTrue(url.endsWith("api_key=fixture-key"));assertFalse(url.contains("api_key=movie:"));called=true
            Json.parseToJsonElement("""{"title":"Recovered","release_date":"2001-01-01","poster_path":"/p.jpg"}""")
        }
        assertTrue(called);assertEquals("Recovered",result?.title)
    }

    @Test fun unsupportedTypeGenreCombinationsAreRejectedInsteadOfScanningTheWholeCatalog() {
        assertNotNull(ExploreQuery(kind=ExploreKind.TV,genres=setOf("horror")).error(today))
        assertEquals(listOf(ExploreKind.MOVIE),ExploreQuery(genres=setOf("horror")).kinds())
        assertNotNull(ExploreQuery(genres=setOf("horror","kids"),allGenres=true).error(today))
    }

    @Test fun automaticSearchContinuesBeyondTheFirstWorkBatch() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val source=Source()
        for(p in 1..10) source.pages[ExploreKind.MOVIE to p]=ExplorePage(listOf(title(p.toLong(),rating=if(p==10)9.0 else 5.0)),10)
        val state=ExploreState(scope,source,memoryPrefs(),{"one"},{true},{"CA"})
        try {
            state.apply(ExploreQuery(kind=ExploreKind.MOVIE,minRating=8.0))
            withTimeout(3000) { while(state.loading) delay(10) }
            assertTrue(state.complete);assertEquals(listOf(10L),state.results.map{it.tmdb})
            assertEquals(10,state.pagesChecked)
        } finally {scope.cancel()}
    }

    @Test fun returningToDiscoverKeepsItsQueryAndResultsUntilExplicitRefresh() = runBlocking {
        var now=1000L;val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val source=Source()
        val state=ExploreState(scope,source,memoryPrefs(),{"one"},{true},{"CA"},clock={now})
        try {
            state.open();val revision=state.revision;val calls=source.calls.size
            now+=24*60*60_000L;state.open()
            assertEquals(revision,state.revision);assertEquals(calls,source.calls.size)
            state.apply(state.query);assertTrue(state.revision>revision)
        } finally {scope.cancel()}
    }

    @Test fun malformedAwardResponsesAreNotCachedAndCanBeRetriedImmediately() = runBlocking {
        var calls=0
        val source=ExploreRepository(key={"fixture"},get={_,_->
            calls++
            Json.parseToJsonElement(if(calls==1) "{}" else """{"results":{"bindings":[{"tmdb":{"value":"1"}}]}}""")
        })
        try{source.awards(listOf(1),ExploreQuery(oscars=OscarMode.WIN));fail("must reject invalid data")}
        catch(_:IllegalStateException){}
        assertEquals(setOf(1L),source.awards(listOf(1),ExploreQuery(oscars=OscarMode.WIN)))
        assertEquals(2,calls)
    }

    @Test fun explicitRefreshInvalidatesMemoryAndDiskWithoutStoringTheCredential() = runBlocking {
        val dir=java.nio.file.Files.createTempDirectory("explore-cache").toFile()
        var calls=0
        val source=ExploreRepository(cacheDir=dir,key={"fixture-secret"},get={_,_->
            calls++
            Json.parseToJsonElement("""{"results":[{"id":$calls,"title":"Film","release_date":"2020-01-01"}],"total_pages":1}""")
        })
        try {
            assertEquals(1L,source.page(ExploreQuery(),ExploreKind.MOVIE,"CA",1).titles.single().tmdb)
            source.page(ExploreQuery(),ExploreKind.MOVIE,"CA",1);assertEquals(1,calls)
            assertTrue(dir.listFiles()!!.all{!it.name.contains("fixture-secret")&&!it.readText().contains("fixture-secret")})
            source.invalidate()
            assertEquals(2L,source.page(ExploreQuery(),ExploreKind.MOVIE,"CA",1).titles.single().tmdb)
            assertEquals(2,calls)
        } finally { dir.deleteRecursively() }
    }

    @Test fun backgroundCancellationKeepsLoadedResultsAndCanResume() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val source=Source().apply { generated=true }
        val state=ExploreState(scope,source,memoryPrefs(),{"one"},{true},{"CA"})
        try {
            state.open()
            assertTrue(state.results.isNotEmpty());assertTrue(state.loading)
            state.background();val results=state.results;assertFalse(state.loading)
            delay(300);assertEquals(results,state.results)
            state.more();withTimeout(2000){while(state.loading)delay(10)}
            assertTrue(state.complete);assertEquals(100,state.results.size)
        }finally{scope.cancel()}
    }

    private fun memoryPrefs(): Prefs {
        val values=mutableMapOf<String,Any?>()
        val editor=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(SharedPreferences.Editor::class.java)){proxy,method,args->when{
            method.name.startsWith("put")->{values[args[0] as String]=args[1];proxy}
            method.name=="remove"->{values.remove(args[0]);proxy};method.name=="clear"->{values.clear();proxy};method.name=="commit"->true;else->null
        }} as SharedPreferences.Editor
        val sp=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(SharedPreferences::class.java)){_,method,args->when{
            method.name=="edit"->editor;method.name=="contains"->values.containsKey(args[0]);method.name=="getAll"->values.toMap()
            method.name.startsWith("get")->values[args[0]]?:args[1];else->null
        }} as SharedPreferences
        return Prefs(object:ContextWrapper(null){override fun getSharedPreferences(name:String,mode:Int)=sp})
    }
}
