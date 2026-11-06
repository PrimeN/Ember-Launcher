package app.ember.tv

import app.ember.tv.data.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ActionsUpdatesTest {
    @get:Rule val temp = TemporaryFolder()
    private val apkName = "ember-tv-0.9.6-216200000.apk"
    private val payload = "apk contents".toByteArray()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun zip(vararg names: String) = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { out -> names.forEach { name ->
            out.putNextEntry(ZipEntry(name)); out.write(payload); out.closeEntry()
        } }
    }.toByteArray()
    private fun run(event: String = "push", repo: String = BuildConfig.UPDATE_REPO, result: String = "success", id: Int = 7) =
        Json.parseToJsonElement("""{"id":$id,"head_branch":"beta","status":"completed","conclusion":"$result","event":"$event","head_sha":"${"a".repeat(40)}","head_repository":{"full_name":"$repo"}}""").jsonObject
    private fun artifact(bytes: ByteArray) = Json.parseToJsonElement("""{"id":42,"name":"ember-tv-0.9.6-216200000-release","size_in_bytes":${bytes.size},"expired":false,"expires_at":"2099-01-01T00:00:00Z","digest":"sha256:${hash(bytes)}"}""").jsonObject
    private fun change(item: JsonObject, key: String, value: JsonElement) = JsonObject(item + (key to value))
    private fun http(response: (Request) -> String) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(response(chain.request()).toResponseBody()).build()
    }.build()
    private fun bytesHttp(bytes: ByteArray) = OkHttpClient.Builder().addInterceptor { chain ->
        assertNull(chain.request().header("Authorization")); assertNull(chain.request().header("Cookie"))
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(bytes.toResponseBody()).build()
    }.build()

    @Test fun onlySuccessfulFirstPartyBetaRunsAndReleaseArtifactsAreEligible() {
        val good = run()
        assertTrue(ActionsUpdates.trusted(good))
        for (bad in listOf(run(event="pull_request"), run(repo="other/repo"), run(result="failure"),
            change(good,"head_branch",JsonPrimitive("main")), change(good,"status",JsonPrimitive("in_progress")))) {
            assertFalse(ActionsUpdates.trusted(bad))
        }
        val valid = artifact(zip(apkName))
        assertNotNull(ActionsUpdates.releaseOf(valid, good))
        for (bad in listOf(change(valid,"expired",JsonPrimitive(true)), change(valid,"expires_at",JsonPrimitive("2000-01-01T00:00:00Z")),
            change(valid,"digest",JsonPrimitive("sha256:bad")), change(valid,"name",JsonPrimitive("ember-tv-0.9.6-216200000-debug")),
            change(valid,"size_in_bytes",JsonPrimitive(ActionsUpdates.MAX_ARCHIVE_BYTES+1)), change(valid,"id",JsonPrimitive(-1)))) {
            assertNull(ActionsUpdates.releaseOf(bad, good))
        }
        assertNull(ActionsUpdates.releaseOf(valid,good,Instant.parse("2100-01-01T00:00:00Z")))
    }

    @Test fun discoverySkipsForksAndFindsTheNewestAvailableSignedArtifact() = runBlocking {
        val archive = zip(apkName)
        val requested = mutableListOf<String>()
        val client = http { request ->
            assertNull(request.header("Authorization")); requested += request.url.toString()
            if (request.url.encodedPath.endsWith("/runs")) {
                assertEquals("beta",request.url.queryParameter("branch"))
                assertEquals("success",request.url.queryParameter("status"))
            }
            if (request.url.encodedPath.endsWith("/runs")) """{"workflow_runs":[${run(repo="other/repo",id=8)},${run()}]}"""
            else """{"artifacts":[${change(artifact(archive),"expired",JsonPrimitive(true))},${artifact(archive)}]}"""
        }
        val result = Updater.check("beta-actions",client).getOrThrow()!!
        assertEquals(ActionsUpdates.runsUrl,requested[0])
        assertEquals(2,requested.size)
        assertTrue(requested[1].contains("/actions/runs/7/artifacts"))
        assertEquals("https://nightly.link/${BuildConfig.UPDATE_REPO}/actions/artifacts/42.zip",result.assetApiUrl)
        assertTrue(result.zippedApk); assertEquals(apkName,result.assetName)
        assertEquals(216200000,result.versionCode)
        assertEquals(hash(archive),result.sha256)
    }

    @Test fun missingArtifactsFallBackToThePreviousSuccessfulRun() = runBlocking {
        val archive = zip(apkName)
        val client = http { request -> when {
            request.url.encodedPath.endsWith("/runs") -> """{"workflow_runs":[${run(id=8)},${run()}]}"""
            "/runs/8/" in request.url.encodedPath -> """{"artifacts":[]}"""
            else -> """{"artifacts":[${artifact(archive)}]}"""
        } }
        assertNotNull(Updater.check("beta-actions",client).getOrThrow())
        assertNull(Updater.check("beta-actions",http { """{"workflow_runs":[]}""" }).getOrThrow())
    }

    @Test fun aVerifiedArchiveExtractsOnlyTheMatchingApkAndDeletesTheZip() = runBlocking {
        val archive = zip("release/$apkName")
        val context = PersistenceContext(temp.newFolder())
        val release = ActionsUpdates.releaseOf(artifact(archive),run())!!
        val output = Updater.download(context,release,{},bytesHttp(archive)).getOrThrow()
        assertArrayEquals(payload,output.readBytes())
        assertEquals(listOf("ember-update.apk"),output.parentFile!!.listFiles()!!.map { it.name })
    }

    @Test fun damagedArchivesAndUnsignedOrUnexpectedApksAreRejectedAndCleanedUp() = runBlocking {
        for (bad in listOf(zip("../$apkName"),zip("ember-tv-0.9.6-216200000-debug.apk"),zip(apkName,"other.apk"),zip("readme.txt"))) {
            val context = PersistenceContext(temp.newFolder())
            val release = ActionsUpdates.releaseOf(artifact(bad),run())!!
            assertTrue(Updater.download(context,release,{},bytesHttp(bad)).isFailure)
            assertTrue(File(context.cacheDir,"updates").listFiles().orEmpty().isEmpty())
        }
        val archive = zip(apkName)
        val context = PersistenceContext(temp.newFolder())
        val release = ActionsUpdates.releaseOf(artifact(archive),run())!!
        assertTrue(Updater.download(context,release,{},bytesHttp("damaged".toByteArray())).isFailure)
        assertTrue(File(context.cacheDir,"updates").listFiles().orEmpty().isEmpty())
    }

    @Test fun extractionIsBoundedAndEmptyArchivesAreRejected() = runBlocking {
        val archive = temp.newFile().apply {writeBytes(zip(apkName))}
        val output = temp.newFile()
        assertTrue(runCatching {ActionsUpdates.extract(archive,output,apkName,maxBytes=4)}.isFailure)
        archive.writeBytes(zip())
        assertTrue(runCatching {ActionsUpdates.extract(archive,output,apkName)}.isFailure)
    }

    @Test fun cancellationRemovesTheArchiveAndDoesNotBlockTheNextDownload() = runBlocking {
        val archive = zip(apkName)
        val release = ActionsUpdates.releaseOf(artifact(archive),run())!!
        val context = PersistenceContext(temp.newFolder())
        val task = launch(start=CoroutineStart.UNDISPATCHED) {
            val owner = currentCoroutineContext().job
            Updater.download(context,release,{owner.cancel()},bytesHttp(archive))
        }
        task.join()
        assertTrue(task.isCancelled)
        assertTrue(File(context.cacheDir,"updates").listFiles().orEmpty().isEmpty())
        assertTrue(Updater.download(context,release,{},bytesHttp(archive)).isSuccess)
    }
}
