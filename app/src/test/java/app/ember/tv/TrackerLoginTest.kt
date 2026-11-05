package app.ember.tv

import app.ember.tv.tracker.aniListLists
import app.ember.tv.tracker.linkPublicUsername
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TrackerLoginTest {
    @Test fun aniListGraphqlErrorsAndMissingCollectionsDoNotLink() = runBlocking {
        for (payload in listOf(
            """{"errors":[{"message":"User not found"}],"data":null}""",
            """{"data":{"MediaListCollection":null}}""",
            """{}""",
            """{"errors":[{"message":"Private"}],"data":{"MediaListCollection":{"lists":[]}}}""",
        )) {
            var saved = false
            assertFalse(linkPublicUsername("someone", {
                aniListLists(Json.parseToJsonElement(payload))
            }, { saved = true }))
            assertFalse(saved)
        }
    }

    @Test fun emptyPublicListIsValidAndUsernameIsTrimmed() = runBlocking {
        var saved: String? = null
        assertTrue(linkPublicUsername("  someone  ", {
            assertEquals("someone", it)
            assertTrue(aniListLists(Json.parseToJsonElement(
                """{"data":{"MediaListCollection":{"lists":[]}}}""",
            )).isEmpty())
        }, { saved = it }))
        assertEquals("someone", saved)
    }

    @Test fun failedValidationPreservesPreviousUsername() = runBlocking {
        var saved = "previous"
        assertFalse(linkPublicUsername("new", { throw java.io.IOException("offline") }, { saved = it }))
        assertEquals("previous", saved)
        assertFalse(linkPublicUsername("  ", { error("should not request") }, { saved = it }))
    }

    @Test fun cancellationIsPropagatedAndNeverSaves() = runBlocking {
        var saved = false
        try {
            linkPublicUsername("someone", { throw CancellationException("left screen") }, { saved = true })
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
        assertFalse(saved)
    }

    @Test fun cancellationBeforeValidationCompletesCannotSave() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var saved = false
        val job = launch {
            linkPublicUsername("someone", {
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
            }, { saved = true })
        }
        entered.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertFalse(saved)
    }
}
