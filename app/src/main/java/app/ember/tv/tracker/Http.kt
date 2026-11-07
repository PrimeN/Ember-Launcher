package app.ember.tv.tracker

import app.ember.tv.data.Net
import app.ember.tv.data.withResponse
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class HttpException(val code: Int, body: String, val retryAfterSec: Long? = null) : Exception("HTTP $code: ${body.take(160)}")

object Http {
    private val client = Net.client(10, 20)
    // Provider credentials in custom headers must never follow redirects to another host.
    private val privateClient by lazy { client.newBuilder().followRedirects(false).followSslRedirects(false).build() }
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): JsonElement =
        call(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build())

    suspend fun metadata(url: String, headers: Map<String, String> = emptyMap()): JsonElement {
        val identity = url + headers.toSortedMap().toString()
        return withContext(Dispatchers.IO) { json.parseToJsonElement(app.ember.tv.data.CacheManagement.json(app.ember.tv.data.CacheKind.METADATA, identity) { get(url, headers + ("Cache-Control" to "no-store")).toString() }) }
    }

    suspend fun post(url: String, body: JsonObject? = null, headers: Map<String, String> = emptyMap()): JsonElement =
        call(
            Request.Builder().url(url)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .post((body?.toString() ?: "{}").toRequestBody(jsonType))
                .build()
        )

    suspend fun delete(url: String, headers: Map<String, String> = emptyMap(), body: JsonObject? = null): JsonElement =
        call(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.delete(body?.toString()?.toRequestBody(jsonType)).build())

    /** A GET that also hands back the response headers, for paging (X-Pagination-*). */
    suspend fun getPaged(url: String, headers: Map<String, String> = emptyMap()): Pair<JsonElement, okhttp3.Headers> {
        var seen: okhttp3.Headers? = null
        val body = read(client.newCall(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build())) { seen = it }
        return body to (seen ?: okhttp3.Headers.headersOf())
    }

    suspend fun privateGet(url: String, headers: Map<String, String>): JsonElement =
        read(privateClient.newCall(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("Cache-Control", "no-store").get().build()), 2_000_000)

    suspend fun privatePost(url: String, body: JsonObject, headers: Map<String, String>): JsonElement =
        read(privateClient.newCall(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("Cache-Control", "no-store").post(body.toString().toRequestBody(jsonType)).build()), 2_000_000)

    /** Cancellable: leaving a screen (or a time limit) drops the request instead of waiting it out. */
    private suspend fun call(request: Request): JsonElement = read(client.newCall(request))

    /** Cancellation covers response-body consumption as well as waiting for headers. */
    internal suspend fun read(call: okhttp3.Call, maxBytes: Long = Long.MAX_VALUE, onHeaders: ((okhttp3.Headers) -> Unit)? = null): JsonElement = coroutineScope {
        try {
            withResponse(call) { r ->
                onHeaders?.invoke(r.headers)
                withContext(Dispatchers.IO) {
                    val text = if (maxBytes == Long.MAX_VALUE) r.body?.string().orEmpty() else r.body?.let { body ->
                        check(body.contentLength() <= maxBytes) { "Provider response was too large." }
                        val source = body.source()
                        check(!source.request(maxBytes + 1)) { "Provider response was too large." }
                        source.readUtf8()
                    }.orEmpty()
                    if (!r.isSuccessful) throw HttpException(r.code, text, r.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 86400))
                    if (text.isBlank()) JsonNull else json.parseToJsonElement(text)
                }
            }
        } catch (e: Exception) {
            ensureActive() // Closing a cancelled socket may throw IOException first.
            throw e
        }
    }
}

// ---- small helpers for reading loosely shaped JSON ----
fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
fun JsonElement?.arr(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray
fun JsonElement?.str(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull
fun JsonElement?.int(key: String): Int? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.intOrNull
fun JsonElement?.long(key: String): Long? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.longOrNull
fun JsonElement?.dbl(key: String): Double? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.doubleOrNull
fun JsonElement?.list(): List<JsonElement> = (this as? JsonArray)?.toList() ?: emptyList()
fun JsonElement?.bool(key: String): Boolean? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.booleanOrNull
