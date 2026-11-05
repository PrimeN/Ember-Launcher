package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.discover.*
import app.ember.tv.state.WhereToWatchState
import app.ember.tv.streaming.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

class OptionalApisTest {
    @get:Rule val temp = TemporaryFolder()
    private fun json(text: String) = Json.parseToJsonElement(text)
    private val date = LocalDate.of(2026, 10, 27)
    private fun completion(content: String, finish: String = "stop") = buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject {
            put("finish_reason", finish); put("message", buildJsonObject { put("content", content) })
        }) })
    }
    private fun show(options: String = "[]", region: String = "ca", imdb: String = "tt1234567") =
        json("""{"imdbId":"$imdb","streamingOptions":{"$region":$options}}""")
    private fun option(type: String = "subscription", quality: String = "hd", extra: String = "") =
        """{"service":{"id":"netflix","name":"Netflix"},"type":"$type","quality":"$quality","link":"https://www.netflix.com/title/123"$extra}"""

    @Test fun missingStreamingKeySkipsAllRequests() = runBlocking {
        var calls = 0
        val client = StreamingAvailabilityClient(key = { "" }, get = { _, _ -> calls++; error("must not call") })
        assertFalse(client.available)
        assertTrue(client.services("CA").isEmpty()); assertTrue(client.offers("tt1234567", "CA").isEmpty())
        assertEquals(0, calls)
    }
    @Test fun streamingAuthIsInHeadersAndRegionIsNormalized() = runBlocking {
        val client = StreamingAvailabilityClient(key = { "fixture-rapid" }, get = { url, headers ->
            assertFalse(url.contains("fixture-rapid")); assertTrue(url.contains("countries/ca"))
            assertEquals("fixture-rapid", headers["X-RapidAPI-Key"])
            assertEquals(StreamingAvailabilityClient.HOST, headers["X-RapidAPI-Host"])
            assertEquals("no-store", headers["Cache-Control"])
            json("""{"services":[{"id":"netflix","name":"Netflix"}]}""")
        })
        assertEquals("netflix", client.services("CA").single().externalId)
    }
    @Test fun streamingNeverGuessesFromAMalformedTitleId() = runBlocking {
        val client = StreamingAvailabilityClient(key = { "fixture" }, get = { _, _ -> error("must not call") })
        assertTrue(client.offers("../country", "CA").isEmpty())
        assertTrue(runCatching { client.offers("tt1234567", "../us") }.isFailure)
    }
    @Test fun countryServicesRetainDistinctAddonSubscriptions() {
        val services = StreamingAvailabilityClient.parseServices(json("""{"services":[
            {"id":"prime","name":"Prime Video","addons":[{"id":"starz","name":"Starz"}]},
            {"id":"netflix","name":"Netflix"}]}"""))
        assertEquals(listOf("prime", "prime:starz", "netflix"), services.map { it.externalId })
        assertEquals("addon", services[1].type); assertEquals("Starz on Prime Video", services[1].name)
    }
    @Test fun subscriptionOffersPreferBestQualityOverRental() {
        val all = "[${option("rent", "uhd", ",\"price\":{\"amount\":\"2.99\",\"currency\":\"CAD\"}")},${option()},${option(quality="uhd")}]"
        val offer = StreamingAvailabilityClient.parseOffers(show(all), "CA", "tt1234567").single()
        assertEquals("Subscription", offer.label); assertEquals("4K", offer.quality); assertNull(offer.price)
    }
    @Test fun regionalPricesAreNotAssumedToBeDollars() {
        val raw = "[${option("rent", extra = ",\"price\":{\"amount\":\"3.99\",\"currency\":\"EUR\"}")}]"
        val offer = StreamingAvailabilityClient.parseOffers(show(raw), "ca", "tt1234567").single()
        assertEquals("EUR", offer.currency); assertEquals(3.99, offer.price!!, 0.0)
        assertFalse(offer.label.contains("$")); assertTrue(offer.label.startsWith("Rent "))
    }
    @Test fun addonOfferCannotBeMistakenForIncludedBaseSubscription() {
        val raw = "[${option("addon", extra=",\"addon\":{\"id\":\"starz\",\"name\":\"Starz\"}")}]"
        val offer = StreamingAvailabilityClient.parseOffers(show(raw), "ca", "tt1234567").single()
        assertEquals("netflix:starz", offer.externalId); assertEquals("Channel add-on", offer.label)
        assertEquals("Starz on Netflix", offer.name)
    }
    @Test fun unsafeLinksMissingAddonAndUnknownOfferTypesAreSkipped() {
        val entries = listOf(option().replace("https://www.netflix.com/title/123", "javascript:alert(1)"),
            option().replace("https://www.netflix.com/title/123", "https://user:pass@site.test/"), option("addon"), option("unknown"))
        assertTrue(StreamingAvailabilityClient.parseOffers(show(entries.joinToString(",", "[", "]")), "ca", "tt1234567").isEmpty())
    }
    @Test fun absentRegionIsEmptyButWrongTitleAndMalformedPayloadAreFailures() {
        assertTrue(StreamingAvailabilityClient.parseOffers(show(), "us", "tt1234567").isEmpty())
        assertTrue(runCatching { StreamingAvailabilityClient.parseOffers(show(), "ca", "tt7654321") }.isFailure)
        assertTrue(runCatching { StreamingAvailabilityClient.parseOffers(json("{}"), "ca", "tt1234567") }.isFailure)
        assertTrue(runCatching { StreamingAvailabilityClient.parseServices(json("{}")) }.isFailure)
    }
    @Test fun streamingCacheIncludesKeyCountryTitleAndExpiry() = runBlocking {
        var credential = "fixture-one"; var now = 100L; var calls = 0
        val client = StreamingAvailabilityClient(key = { credential }, clock = { now }, get = { _, _ -> calls++; show() })
        client.offers("tt1234567", "CA"); client.offers("tt1234567", "ca"); assertEquals(1, calls)
        client.offers("tt1234567", "US"); assertEquals(2, calls)
        credential = "fixture-two"; client.offers("tt1234567", "CA"); assertEquals(3, calls)
        now += 6 * 60 * 60_000L; client.offers("tt1234567", "CA"); assertEquals(4, calls)
    }
    @Test fun streamingFailuresAreNotCached() = runBlocking {
        var calls = 0
        val client = StreamingAvailabilityClient(key = { "fixture" }, get = { _, _ -> if (calls++ == 0) error("fixture") else show() })
        assertTrue(runCatching { client.offers("tt1234567", "CA") }.isFailure)
        assertTrue(client.offers("tt1234567", "CA").isEmpty()); assertEquals(2, calls)
    }
    @Test fun cancellingAnOfferRequestRejectsLateReplyAndDoesNotCacheIt() = runBlocking {
        val pending = CompletableDeferred<JsonElement>(); var calls = 0
        val client = StreamingAvailabilityClient(key = { "fixture" }, get = { _, _ ->
            if (calls++ == 0) withContext(NonCancellable) { pending.await() } else show()
        })
        val request = launch(start = CoroutineStart.UNDISPATCHED) { client.offers("tt1234567", "CA"); fail("published cancelled offer") }
        request.cancel(); pending.complete(show()); request.join()
        client.offers("tt1234567", "CA"); assertEquals(2, calls)
    }
    @Test fun replacingAStreamingKeyRejectsTheOldResponse() = runBlocking {
        var credential = "fixture-one"
        val client = StreamingAvailabilityClient(key = { credential }, get = { _, _ -> credential = "fixture-two"; show() })
        assertTrue(runCatching { client.offers("tt1234567", "CA") }.isFailure)
    }
    @Test fun stringServiceIdsDoNotCrossProviderPreferencesOrHashCollisions() {
        val prefs = Prefs(PersistenceContext(temp.newFolder())); prefs.watchProvider = "streaming"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = WhereToWatchState(prefs, scope, { emptyList() }, {})
        try {
            val first = StreamingService(7, "One", "sub", externalId = "one")
            val second = StreamingService(7, "Two", "sub", externalId = "two")
            state.toggleService(first); assertTrue(state.isServiceOn(first)); assertFalse(state.isServiceOn(second))
            assertEquals(setOf("streaming:one"), prefs.servicesOn)
            prefs.watchProvider = "tmdb"
            assertFalse(WhereToWatchState(prefs, scope, { emptyList() }, {}).isServiceOn(first))
        } finally { scope.cancel() }
    }

    @Test fun missingMistralOrTmdbKeyMakesNoAiRequest() = runBlocking {
        var calls = 0
        val post: suspend (String, JsonObject, Map<String,String>) -> JsonElement = { _,_,_ -> calls++; error("must not call") }
        assertTrue(runCatching { AiDiscovery(key={ "" }, post=post).suggest("comedy", "CA", true, { _,_->emptyList() }, { emptyList() }) }.isFailure)
        assertTrue(runCatching { AiDiscovery(key={ "fixture" }, post=post).suggest("comedy", "CA", false, { _,_->emptyList() }, { emptyList() }) }.isFailure)
        assertEquals(0, calls)
    }
    @Test fun aiSendsOnlyTheTypedPromptWithSchemaAndReturnsAReviewableDraft() = runBlocking {
        val client = AiDiscovery(key={ "fixture" }, today={ date }, post={ url, body, headers ->
            assertEquals("https://api.mistral.ai/v1/chat/completions", url)
            assertEquals("Bearer fixture", headers["Authorization"]); assertEquals("no-store", headers["Cache-Control"])
            assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            val messages = body["messages"]!!.jsonArray
            assertEquals(2, messages.size); assertEquals("funny movies", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
            assertFalse(body.toString().contains("fixture"))
            completion("""{"kind":"movie","genres":["comedy"],"fromYear":2010,"minRating":7.0}""")
        })
        val suggestion = client.suggest("funny movies", "CA", true, { _,_->emptyList() }, { emptyList() })
        assertEquals(ExploreKind.MOVIE, suggestion.query.kind); assertEquals(setOf("comedy"), suggestion.query.genres)
        assertEquals(2010, suggestion.query.fromYear); assertTrue(suggestion.warnings.isEmpty())
    }
    @Test fun aiNamesAreResolvedOnlyWhenExactlyOneRealEntryMatches() = runBlocking {
        val client = AiDiscovery(key={ "fixture" }, today={ date }, post={ _,_,_->
            completion("""{"actors":["Tom Hanks","Alex Smith"],"companies":["DreamWorks"],"providers":["Netflix"]}""")
        })
        val suggestion = client.suggest("a movie description", "CA", true, { name, company ->
            when(name) {
                "Tom Hanks" -> listOf(ExploreRef(1, "Tom Hanks"), ExploreRef(9, "Tom Hank"))
                "Alex Smith" -> listOf(ExploreRef(2, "Alex Smith"), ExploreRef(3, "Alex Smith"))
                else -> { assertTrue(company); listOf(ExploreRef(4, "DreamWorks")) }
            }
        }, { listOf(ExploreRef(8, "Netflix")) })
        assertEquals(listOf(1L), suggestion.query.actors.map { it.id }); assertEquals(4L, suggestion.query.companies.single().id)
        assertEquals(8L, suggestion.query.providers.single().id); assertTrue(suggestion.warnings.single().contains("Alex Smith"))
    }
    @Test fun unresolvedPeopleProviderOutagesAndUnsupportedCriteriaAreVisibleWarnings() = runBlocking {
        val client = AiDiscovery(key={ "fixture" }, today={ date }, post={ _,_,_->
            completion("""{"actors":["Missing Actor"],"providers":["Unavailable Service"],"unsupported":"Rotten Tomatoes rating"}""")
        })
        val suggestion = client.suggest("some request", "CA", true, { _,_->error("fixture with private body") }, { error("fixture") })
        assertEquals(3, suggestion.warnings.size); assertTrue(suggestion.query.actors.isEmpty()); assertTrue(suggestion.query.providers.isEmpty())
        assertFalse(suggestion.warnings.joinToString().contains("private body"))
    }
    @Test fun aiRejectsInventedIdsUnknownFieldsInvalidEnumsAndOutOfRangeFilters() {
        val bad = listOf("""{"actors":[{"id":99,"name":"Fake"}]}""", """{"url":"https://example.test"}""",
            """{"kind":"video"}""", """{"sort":"random"}""", """{"oscars":"bafta"}""",
            """{"genres":["invented"]}""", """{"fromYear":2030}""", """{"minRating":11}""",
            """{"fromYear":2020,"toYear":2000}""", """{"kind":"tv","oscars":"win"}""",
            """{"bestPicture":true}""", """{"actors":["One","Two","Three","Four"]}""")
        bad.forEach { assertTrue(it, runCatching { AiDiscovery.parse(completion(it), date) }.isFailure) }
    }
    @Test fun aiRejectsTruncatedRefusedEmptyAndOversizedReplies() {
        val replies = listOf(completion("{}", "length"), completion("{}", "tool_calls"), completion(""),
            completion("x".repeat(12_001)), json("""{"choices":[]}"""), completion("not json"))
        replies.forEach { assertTrue(runCatching { AiDiscovery.parse(it, date) }.isFailure) }
    }
    @Test fun cancellingAiDoesNotReturnALateSuggestion() = runBlocking {
        val pending = CompletableDeferred<JsonElement>()
        val client = AiDiscovery(key={ "fixture" }, post={ _,_,_-> withContext(NonCancellable) { pending.await() } })
        val request = launch(start=CoroutineStart.UNDISPATCHED) {
            client.suggest("comedy movies", "CA", true, { _,_->emptyList() }, { emptyList() }); fail("published cancelled AI")
        }
        request.cancel(); pending.complete(completion("{}")); request.join()
        assertTrue(request.isCancelled)
    }
    @Test fun aiRejectsReplyAfterKeyChange() = runBlocking {
        var key = "fixture-one"
        val client = AiDiscovery(key={ key }, post={ _,_,_-> key="fixture-two"; completion("{}") })
        assertTrue(runCatching { client.suggest("comedy movies", "CA", true, { _,_->emptyList() }, { emptyList() }) }.isFailure)
    }
    @Test fun invalidPromptsDoNotSpendAnAiRequest() = runBlocking {
        val client = AiDiscovery(key={ "fixture" }, post={ _,_,_-> error("must not call") })
        listOf("", "xx", "x".repeat(1001)).forEach { prompt ->
            assertTrue(runCatching { client.suggest(prompt, "CA", true, { _,_->emptyList() }, { emptyList() }) }.isFailure)
        }
    }
    @Test fun optionalSettingsAndEncryptedKeysSurviveBackupRestore() {
        val source = PersistenceContext(temp.newFolder()); val prefs = Prefs(source)
        assertFalse(prefs.aiDiscoveryEnabled); assertEquals("off", prefs.watchProvider)
        prefs.aiDiscoveryEnabled=true; prefs.watchProvider="streaming"; prefs.servicesOn=setOf("streaming:netflix")
        val keys = mapOf("apikey.MISTRAL_API_KEY" to "fixture-ai", "apikey.STREAMING_AVAILABILITY_API_KEY" to "fixture-rapid")
        val payload = BackupPersistence(source, FixtureSecrets(keys)).snapshot()
        val target = PersistenceContext(temp.newFolder()); val targetKeys=FixtureSecrets()
        BackupPersistence(target, targetKeys).restore(payload)
        val restored=Prefs(target)
        assertTrue(restored.aiDiscoveryEnabled); assertEquals("streaming", restored.watchProvider)
        assertEquals(setOf("streaming:netflix"), restored.servicesOn); assertEquals(keys, targetKeys.read())
        assertTrue(ApiKeys.ALL_IDS.containsAll(listOf("MISTRAL_API_KEY", "STREAMING_AVAILABILITY_API_KEY")))
    }
    @Test fun existingWatchProvidersRemainUnchanged() {
        val context = PersistenceContext(temp.newFolder()); val prefs=Prefs(context)
        listOf("off", "tmdb", "watchmode").forEach { provider ->
            prefs.watchProvider=provider; assertEquals(provider, Prefs(context).watchProvider)
        }
    }
    @Test fun serviceTimeoutShowsRecoveryAndStopsLoading() = runBlocking {
        val prefs=Prefs(PersistenceContext(temp.newFolder())); prefs.watchProvider="streaming"
        val scope=CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state=WhereToWatchState(prefs, scope, {emptyList()}, {}).apply {
            providerReady={true}; serviceLoader={_,_->withTimeout(1) { awaitCancellation() }}
        }
        try {
            state.refreshStreamingServices(true); state.awaitRefresh()
            assertFalse(state.servicesLoading); assertTrue(state.servicesMessage!!.contains("Streaming Availability"))
        } finally {scope.cancel()}
    }
    @Test fun aValidEmptyServiceListReplacesObsoleteSavedServicesAndIsCached() = runBlocking {
        val prefs=Prefs(PersistenceContext(temp.newFolder())); prefs.watchProvider="streaming"
        prefs.servicesSource="streaming:${prefs.watchRegion}"
        prefs.servicesJson="""[{"id":1,"name":"Old Service","type":"sub","externalId":"old"}]"""
        val scope=CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); var calls=0
        val state=WhereToWatchState(prefs, scope, {emptyList()}, {}).apply {
            providerReady={true}; serviceLoader={_,_->calls++; emptyList()}
        }
        try {
            state.refreshStreamingServices(true); state.awaitRefresh()
            assertTrue(state.streamingServices.isEmpty()); assertEquals("[]", prefs.servicesJson)
            state.refreshStreamingServices(); state.awaitRefresh(); assertEquals(1,calls); assertFalse(state.servicesLoading)
        } finally {scope.cancel()}
    }
    @Test fun aLateServiceReplyCannotUndoProviderChangeOrRestartLoading() = runBlocking {
        val prefs=Prefs(PersistenceContext(temp.newFolder())); prefs.watchProvider="streaming"
        val scope=CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val pending=CompletableDeferred<List<StreamingService>>()
        val state=WhereToWatchState(prefs, scope, {emptyList()}, {}).apply {
            providerReady={it!="off"}; serviceLoader={_,_->withContext(NonCancellable) {pending.await()}}
        }
        try {
            state.refreshStreamingServices(true); assertTrue(state.servicesLoading)
            state.updateWatchProvider("off"); assertFalse(state.servicesLoading)
            pending.complete(listOf(StreamingService(1,"Old","sub"))); yield()
            assertTrue(state.streamingServices.isEmpty()); assertFalse(state.servicesLoading); assertNull(state.servicesMessage)
        } finally {scope.cancel()}
    }
}
