package app.ember.tv

import app.ember.tv.phone.PhoneSetup
import app.ember.tv.phone.PhoneSetupAccess
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class PhoneSetupTest {
    private class Handler(private val access: PhoneSetupAccess, val calls: AtomicInteger) : PhoneSetup.Handler {
        private fun received(): String { access.requireActive(); calls.incrementAndGet(); return "Received" }
        override fun addAddon(url: String) = received()
        override fun saveKey(id: String, value: String) = received()
        override fun addIptv(kind: String, name: String, url: String, username: String, password: String, epg: String) = received()
        override fun log(line: String) { received() }
        override fun exportBackup(passphrase: String) = received().toByteArray()
        override fun importBackup(bytes: ByteArray, passphrase: String, apply: Boolean) = received()
        override fun importBadges(text: String) = received()
        override fun importCollections(text: String) = received()
        override fun importPresentation(name:String,bytes:ByteArray)=received()
        override fun content() = app.ember.tv.phone.PhoneContent(emptyList(), emptyList(), emptyList())
        override fun proposeContent(fields: Map<String,String>) = received()
        override fun exportCollection(id: String, passphrase: String) = received().toByteArray()
    }

    private fun server(calls: AtomicInteger = AtomicInteger(), duration: Long = PhoneSetupAccess.DURATION_MILLIS,
        clock: () -> Long = { System.nanoTime() / 1_000_000 }) = PhoneSetup(
        { Handler(it, calls) }, address = { "127.0.0.1" }, port = 0,
        durationMillis = duration, nowMillis = clock,
    )

    private fun connect(uri: URI) = Socket(uri.host, uri.port).apply { soTimeout = 4000 }
    private fun request(uri: URI, target: String = "/?${uri.rawQuery}", method: String = "GET", body: String = ""): String =
        connect(uri).use { socket ->
            socket.getOutputStream().write(("$method $target HTTP/1.1\r\nHost: localhost\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body").toByteArray())
            socket.getInputStream().bufferedReader().readText()
        }

    @Test fun presentationUploadsUseTheSameAuthenticatedExpiringLease() {
        val calls=AtomicInteger();val now=AtomicLong(1000);val server=server(calls,clock=now::get)
        try {val uri=URI(server.start()!!)
            assertTrue(request(uri,"/presentation?k=wrong","POST").startsWith("HTTP/1.1 403"));assertEquals(0,calls.get())
            val boundary="fixture"
            val body="--$boundary\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\nphoto.jpg\r\n--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"photo.jpg\"\r\nContent-Type: image/jpeg\r\n\r\nfixture\r\n--$boundary--\r\n"
            connect(uri).use {socket->socket.getOutputStream().write(("POST /presentation?${uri.rawQuery} HTTP/1.1\r\nHost: localhost\r\nContent-Type: multipart/form-data; boundary=$boundary\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body").toByteArray())
                assertTrue(socket.getInputStream().bufferedReader().readText().startsWith("HTTP/1.1 200"))}
            assertEquals(1,calls.get());now.addAndGet(PhoneSetupAccess.DURATION_MILLIS)
            val rejected=runCatching {request(uri,"/presentation?${uri.rawQuery}","POST")}.getOrDefault("")
            assertFalse(rejected.startsWith("HTTP/1.1 200"));assertEquals(1,calls.get())
        }finally{server.stop()}
    }

    @Test fun accessIsExactlyFifteenMinutesAndRequestsDoNotExtendIt() {
        val now = AtomicLong(1000)
        val access = PhoneSetupAccess(nowMillis = now::get)
        assertEquals(900_000L, access.remainingMillis)
        now.addAndGet(899_999)
        assertTrue(access.accepts(access.code))
        assertEquals(1L, access.remainingMillis)
        now.incrementAndGet()
        assertFalse(access.active)
        assertFalse(access.accepts(access.code))
        assertEquals(0L, access.remainingMillis)
    }

    @Test fun revocationCannotBeUndoneByClockChangesAndEachSessionHasANewCode() {
        val now = AtomicLong(1000)
        val old = PhoneSetupAccess(nowMillis = now::get)
        old.revoke()
        now.set(0)
        assertFalse(old.active)
        assertThrows(IllegalStateException::class.java) { old.requireActive() }
        val fresh = PhoneSetupAccess(nowMillis = now::get)
        assertNotEquals(old.code, fresh.code)
        assertFalse(fresh.accepts(old.code))
        assertEquals(5, fresh.code.length)
        assertTrue(fresh.code.matches(Regex("[A-Za-z0-9]{5}")))
    }

    @Test fun shortCodesStayAlphanumericAndUrlSafe() {
        repeat(100) {
            val access = PhoneSetupAccess(nowMillis = { 1000 })
            assertTrue(access.code.matches(Regex("[A-Za-z0-9]{5}")))
            assertTrue(access.accepts(access.code))
            assertFalse(access.accepts(access.code + "0"))
        }
    }

    @Test fun incorrectLinksAreBoundedAcrossClientsWithoutExtendingTheLease() {
        val now = AtomicLong(1000)
        val access = PhoneSetupAccess(nowMillis = now::get)
        repeat(PhoneSetupAccess.MAX_FAILURES) { assertFalse(access.accepts("wrong-link")) }
        assertTrue(access.throttled)
        assertFalse(access.accepts(access.code))
        now.addAndGet(PhoneSetupAccess.FAILURE_WINDOW_MILLIS - 1)
        assertFalse(access.accepts(access.code))
        now.incrementAndGet()
        assertTrue(access.accepts(access.code))
        assertFalse(access.throttled)
        assertEquals(870_000L, access.remainingMillis)
    }

    @Test fun successfulRequestsDoNotResetTheIncorrectLinkBudget() {
        val access = PhoneSetupAccess(nowMillis = { 1000 })
        repeat(PhoneSetupAccess.MAX_FAILURES - 1) {
            assertFalse(access.accepts("wrong-link"))
            assertTrue(access.accepts(access.code))
        }
        assertFalse(access.accepts("wrong-link"))
        assertTrue(access.throttled)
        assertFalse(access.accepts(access.code))
    }

    @Test fun requestsWithoutAKeyDoNotUseTheGuessBudget() {
        val access = PhoneSetupAccess(nowMillis = { 1000 })
        repeat(PhoneSetupAccess.MAX_FAILURES * 3) { assertFalse(access.accepts(null)); assertFalse(access.accepts("")) }
        assertFalse(access.throttled)
        assertTrue(access.accepts(access.code))
    }

    @Test fun phoneBackupsAreAlwaysEncrypted() {
        val calls = AtomicInteger()
        val setup = server(calls)
        try {
            val uri = URI(setup.start()!!)
            val blank = request(uri, "/backup?${uri.rawQuery}", "POST", "passphrase=")
            assertTrue(blank, blank.startsWith("HTTP/1.1 200") && blank.contains("at least ${PhoneSetup.MIN_BACKUP_PASSPHRASE} characters"))
            request(uri, "/backup?${uri.rawQuery}", "POST", "passphrase=short")
            assertEquals(0, calls.get())
            val saved = request(uri, "/backup?${uri.rawQuery}", "POST", "passphrase=long-enough-pass")
            assertEquals(1, calls.get())
            assertFalse(saved.contains("at least ${PhoneSetup.MIN_BACKUP_PASSPHRASE} characters"))
        } finally { setup.stop() }
    }

    @Test fun cooldownDoesNotRestoreRevokedOrExpiredSessions() {
        val now = AtomicLong(1000)
        val revoked = PhoneSetupAccess(nowMillis = now::get)
        val expired = PhoneSetupAccess(durationMillis = 10, nowMillis = now::get)
        repeat(PhoneSetupAccess.MAX_FAILURES) { revoked.accepts(null); expired.accepts(null) }
        revoked.revoke()
        now.addAndGet(PhoneSetupAccess.FAILURE_WINDOW_MILLIS)
        assertFalse(revoked.accepts(revoked.code))
        assertFalse(expired.accepts(expired.code))
    }

    @Test fun shortUrlAndGuessLimitApplyBeforeHttpHandlersRun() {
        val now = AtomicLong(1000)
        val calls = AtomicInteger()
        val setup = server(calls, clock = now::get)
        try {
            val uri = URI(setup.start()!!)
            assertTrue(uri.rawQuery.matches(Regex("k=[A-Za-z0-9]{5}")))
            repeat(PhoneSetupAccess.MAX_FAILURES) { request(uri, "/save?k=invalid", "POST", "action=addon&url=test") }
            assertTrue(request(uri, "/save?${uri.rawQuery}", "POST", "action=addon&url=test")
                .startsWith("HTTP/1.1 429 Too Many Requests"))
            assertEquals(0, calls.get())
            now.addAndGet(PhoneSetupAccess.FAILURE_WINDOW_MILLIS)
            assertTrue(request(uri, "/save?${uri.rawQuery}", "POST", "action=addon&url=test").startsWith("HTTP/1.1 200"))
            assertEquals(1, calls.get())
        } finally { setup.stop() }
    }

    @Test fun serverDoesNotListenUntilExplicitlyStartedAndRepeatedStartDoesNotRenew() {
        val now = AtomicLong(1000)
        val setup = server(clock = now::get)
        try {
            assertNull(setup.url)
            assertEquals(0L, setup.remainingMillis)
            val first = setup.start()
            assertNotNull(first)
            now.addAndGet(2000)
            assertEquals(first, setup.start())
            assertEquals(898_000L, setup.remainingMillis)
        } finally { setup.stop() }
        assertNull(setup.url)
        assertEquals(0L, setup.remainingMillis)
    }

    @Test fun phoneFormsCarrySessionAuthorizationOnEveryPost() {
        val setup = server()
        try {
            val uri = URI(setup.start()!!)
            // Every tab (#49): each form posts with the session code, and together they keep every endpoint.
            val actions = listOf("setup", "settings", "content", "backup").flatMap { tab ->
                val response = request(uri, "/?${uri.rawQuery}&tab=$tab")
                assertTrue(response.startsWith("HTTP/1.1 200"))
                assertTrue(response.contains("Referrer-Policy: no-referrer"))
                assertTrue(response.contains("Cache-Control: no-store"))
                assertTrue(response.contains("aria-current=page"))
                Regex("action=\"([^\"]+)\"").findAll(response).map { it.groupValues[1] }.toList()
            }
            assertTrue(actions.isNotEmpty() && actions.all { it.endsWith("?${uri.rawQuery}") })
            assertEquals(setOf("/save", "/presentation", "/collections", "/badges", "/backup", "/restore"),
                actions.map { it.substringBefore('?') }.toSet())
        } finally { setup.stop() }
    }

    @Test fun unauthorizedUploadIsRejectedBeforeWaitingForItsBody() {
        val calls = AtomicInteger()
        val setup = server(calls)
        try {
            val uri = URI(setup.start()!!)
            connect(uri).use { socket ->
                socket.getOutputStream().write("POST /restore HTTP/1.1\r\nHost: localhost\r\nContent-Length: 8000000\r\n\r\n".toByteArray())
                assertTrue(socket.getInputStream().bufferedReader().readText().startsWith("HTTP/1.1 403"))
            }
            assertEquals(0, calls.get())
        } finally { setup.stop() }
    }

    @Test fun authorizedSaveStillWorks() {
        val calls = AtomicInteger()
        val setup = server(calls)
        try {
            val uri = URI(setup.start()!!)
            assertTrue(request(uri, "/save?${uri.rawQuery}", "POST", "action=addon&url=https%3A%2F%2Fexample.test%2Fmanifest.json")
                .startsWith("HTTP/1.1 200"))
            assertEquals(1, calls.get())
        } finally { setup.stop() }
    }

    @Test fun oldBrowserFormCannotAuthorizeNewSession() {
        val calls = AtomicInteger()
        val setup = server(calls)
        try {
            val old = URI(setup.start()!!)
            setup.stop()
            val fresh = URI(setup.start()!!)
            assertNotEquals(old.rawQuery, fresh.rawQuery)
            assertTrue(request(fresh, "/save?${old.rawQuery}", "POST", "action=addon&url=test").startsWith("HTTP/1.1 403"))
            assertEquals(0, calls.get())
            assertTrue(request(fresh, "/unknown?${fresh.rawQuery}").startsWith("HTTP/1.1 404"))
        } finally { setup.stop() }
    }

    @Test fun switchingOffClosesAnUploadAlreadyConnected() {
        val calls = AtomicInteger()
        val setup = server(calls)
        try {
            val uri = URI(setup.start()!!)
            connect(uri).use { socket ->
                socket.getOutputStream().write("POST /save?${uri.rawQuery} HTTP/1.1\r\nContent-Length: 100\r\n\r\naction=".toByteArray())
                setup.stop()
                // A queued accept may be reset; an admitted upload receives EOF. Neither may hang.
                try { assertEquals(-1, socket.getInputStream().read()) }
                catch (_: java.net.SocketException) { }
            }
            assertEquals(0, calls.get())
            assertNull(setup.url)
        } finally { setup.stop() }
    }

    @Test fun serverTimerClosesClientsWithoutAnyUiCountdown() {
        val setup = server(duration = 300)
        try {
            val uri = URI(setup.start()!!)
            connect(uri).use { socket ->
                socket.getOutputStream().write("GET / HTTP/1.1\r\n".toByteArray())
                assertEquals(-1, socket.getInputStream().read())
            }
            assertEquals(0L, setup.remainingMillis)
        } finally { setup.stop() }
    }

    @Test fun requestFinishingAfterDeadlineCannotDispatchAMutation() {
        val now = AtomicLong(1000)
        val calls = AtomicInteger()
        val setup = server(calls, clock = now::get)
        try {
            val uri = URI(setup.start()!!)
            connect(uri).use { socket ->
                val body = "action=addon&url=test"
                socket.getOutputStream().write("POST /save?${uri.rawQuery} HTTP/1.1\r\nContent-Length: ${body.length}\r\n\r\n".toByteArray())
                now.addAndGet(PhoneSetupAccess.DURATION_MILLIS)
                try {
                    socket.getOutputStream().write(body.toByteArray())
                    socket.getInputStream().bufferedReader().readText()
                }
                catch (_: java.net.SocketException) { }
            }
            assertEquals(0, calls.get())
        } finally { setup.stop() }
    }

    @Test fun oversizedOrInvalidContentLengthNeverReachesHandlers() {
        val calls = AtomicInteger()
        val setup = server(calls)
        try {
            val uri = URI(setup.start()!!)
            for ((length, status) in listOf("64001" to 413, "-1" to 400, "nonsense" to 400)) {
                connect(uri).use { socket ->
                    socket.getOutputStream().write("POST /save?${uri.rawQuery} HTTP/1.1\r\nContent-Length: $length\r\n\r\n".toByteArray())
                    assertTrue(socket.getInputStream().bufferedReader().readText().startsWith("HTTP/1.1 $status"))
                }
            }
            assertEquals(0, calls.get())
        } finally { setup.stop() }
    }

    @Test fun noNetworkAddressLeavesServerDisabled() {
        val setup = PhoneSetup({ error("Must not create a session without a network") }, address = { null })
        assertNull(setup.start())
        assertNull(setup.url)
        assertEquals(0L, setup.remainingMillis)
    }

    @Test fun contentAndCollectionExportRequireTheCurrentSessionCode() {
        val calls = AtomicInteger(); val setup = server(calls)
        try {
            val uri = URI(setup.start()!!)
            for (path in listOf("/content", "/collection-export")) assertTrue(request(uri,"$path?bad=code","POST","action=collection").startsWith("HTTP/1.1 403"))
            assertEquals(0,calls.get())
            assertTrue(request(uri,"/content?${uri.rawQuery}").contains("Approve"))
            assertTrue(request(uri,"/content?${uri.rawQuery}","POST","action=collection").contains("Received"))
            assertTrue(request(uri,"/collection-export?${uri.rawQuery}","POST","collectionId=opaque&passphrase=fixture-passphrase").contains("Content-Disposition: attachment"))
            assertEquals(2,calls.get())
        } finally { setup.stop() }
    }
    @Test fun encryptedCollectionUploadChecksThePassphraseBeforeDispatch() {
        val calls = AtomicInteger(); val setup = server(calls)
        val source = app.ember.tv.data.CollectionSource(provider="trakt",traktListId=1)
        val c = app.ember.tv.data.MediaCollection("c","Collection",folders=listOf(app.ember.tv.data.CollectionFolder("f","Folder",sources=listOf(source))))
        val pack = java.net.URLEncoder.encode(app.ember.tv.data.CollectionTransfer.seal(listOf(c),"fixture-passphrase"){null},"UTF-8")
        try {
            val uri = URI(setup.start()!!)
            assertTrue(request(uri,"/collections?${uri.rawQuery}","POST","json=$pack&passphrase=incorrect-passphrase").contains("Could not unlock")); assertEquals(0,calls.get())
            request(uri,"/collections?${uri.rawQuery}","POST","json=$pack&passphrase=fixture-passphrase"); assertEquals(1,calls.get())
        } finally { setup.stop() }
    }

    @Test fun aBrowserTryingHttpsIsRefusedAtOnceAndThePageStillLoadsOverHttp() {
        // #152: a TLS handshake used to wait out the 4-second header timeout.
        assertTrue(PhoneSetup.isTlsHandshake(0x16));assertTrue(PhoneSetup.isTlsHandshake(0x80))
        for(method in listOf("GET","POST","HEAD","OPTIONS"))assertFalse(PhoneSetup.isTlsHandshake(method[0].code))
        val setup=server()
        try {
            val uri=URI(setup.start()!!)
            val started=System.nanoTime()
            connect(uri).use {socket->
                // The start of a TLS 1.2+ ClientHello record; no line ending follows.
                socket.getOutputStream().write(byteArrayOf(0x16,0x03,0x01,0x02,0x00,0x01,0x00,0x01,0xFC.toByte(),0x03,0x03))
                socket.getOutputStream().flush()
                val answer=runCatching {socket.getInputStream().read()}.getOrDefault(-1)
                assertEquals("no HTTP answer to a TLS handshake",-1,answer)
            }
            assertTrue("refused well inside the header timeout",(System.nanoTime()-started)/1_000_000<2_000)
            assertTrue(request(uri).startsWith("HTTP/1.1 200"))
        } finally {setup.stop()}
    }

    @Test fun aPageThatFailsStillAnswersAndABusyTvSaysSo() {
        // #154: both used to close the connection with no response.
        val failing=PhoneSetup({access->object:PhoneSetup.Handler by Handler(access,AtomicInteger()) {
            override fun content():app.ember.tv.phone.PhoneContent=error("boom")
        }},address={"127.0.0.1"},port=0)
        try {
            val uri=URI(failing.start()!!)
            val answer=request(uri,"/content?${uri.rawQuery}")
            assertTrue(answer,answer.startsWith("HTTP/1.1 500"))
            assertTrue(answer.contains("Reload this page"));assertFalse(answer.contains("boom"))
            assertTrue("the next page still loads",request(uri).startsWith("HTTP/1.1 200"))
        } finally {failing.stop()}
        val setup=server()
        val idle=mutableListOf<Socket>()
        try {
            val uri=URI(setup.start()!!)
            repeat(4) {idle+=connect(uri)}
            Thread.sleep(300)
            val answer=request(uri)
            assertTrue(answer,answer.startsWith("HTTP/1.1 503"))
            assertTrue(answer.contains("Reload this page in a moment"))
        } finally {idle.forEach {runCatching {it.close()}};setup.stop()}
    }
}
