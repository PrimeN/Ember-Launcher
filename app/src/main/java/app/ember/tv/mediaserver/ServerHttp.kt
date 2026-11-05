package app.ember.tv.mediaserver

import app.ember.tv.data.Net
import app.ember.tv.data.withResponse
import app.ember.tv.data.ResponseLimits
import app.ember.tv.data.boundedText
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class ServerHttpException(val status: Int): Exception("Server returned HTTP $status.")
/** Authenticated server calls bypass disk caches and scope credentials across redirects. */
class ServerHttp(client: OkHttpClient = Net.client(5,15), private val maxResponseBytes: Long = ResponseLimits.ADDON) {
    init { require(maxResponseBytes > 0) }
    private val http=client.newBuilder().cache(null).followRedirects(false).build()
    suspend fun get(url: String,headers: Map<String,String> = emptyMap()): JsonElement = json(url,headers,"GET",null)
    suspend fun post(url: String,body: JsonObject?=null,headers: Map<String,String> = emptyMap()): JsonElement = json(url,headers,"POST",body)
    suspend fun ack(url: String,headers: Map<String,String>,method: String="GET",body: JsonObject?=null) { text(url,headers,method,body) }
    suspend fun rawGet(url:String,headers:Map<String,String> = emptyMap()) = text(url,headers,"GET",null)
    suspend fun rawPost(url:String,headers:Map<String,String> = emptyMap()) = text(url,headers,"POST",null)
    private suspend fun json(url: String,headers: Map<String,String>,method: String,body: JsonObject?): JsonElement {
        val text=text(url,headers,method,body); return if(text.isBlank()) JsonNull else Json.parseToJsonElement(text)
    }
    private suspend fun text(url: String,headers: Map<String,String>,method: String,body: JsonObject?): String = coroutineScope {
        var target=url.toHttpUrl(); val origin=target; val auth=headers
        var requestMethod=method
        repeat(6) {
            val request=Request.Builder().url(target).apply { auth.forEach { (k,v)->header(k,v) }
                when (requestMethod) {
                    "POST" -> post((body?.toString()?:"{}").toRequestBody("application/json".toMediaType()))
                    "PUT" -> put((body?.toString()?:"{}").toRequestBody("application/json".toMediaType()))
                    "DELETE" -> delete()
                    else -> get()
                }
            }.build()
            withResponse(http.newCall(request)) { response ->
                    if(response.code in listOf(301,302,303,307,308)) {
                        val next=target.resolve(response.header("Location")?:error("Missing server redirect."))?:error("Invalid server redirect.")
                        check(!(target.isHttps&&!next.isHttps)) { "The server attempted an insecure redirect." }
                        if(next.host!=origin.host||next.port!=origin.port||next.scheme!=origin.scheme) {
                            // Server API tokens must never follow a redirect to another origin.
                            error("The server redirected to another address. Add or select that route explicitly.")
                        }
                        // See Other follows with GET; 301/302 retain the standard POST-to-GET behavior.
                        // 307/308 preserve the original method and body. Origin checks always run first.
                        if(response.code==303 || response.code in listOf(301,302) && requestMethod=="POST")requestMethod="GET"
                        target=next
                    } else {
                        if(!response.isSuccessful) throw ServerHttpException(response.code)
                        return@coroutineScope withContext(Dispatchers.IO) { response.body.boundedText(maxResponseBytes) }
                    }
            }
        }; error("Too many server redirects.")
    }
}
/** Parsed classification, not a guess that every numeric address belongs to the LAN. */
object ServerAddress {
    private fun validate(value: String): String {
        val parsed = value.toHttpUrl()
        require(parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.query == null && parsed.fragment == null) { "Enter the server base address without credentials or query parameters." }
        return parsed.toString().trimEnd('/')
    }
    fun normalize(raw: String): String {
        val text=raw.trim().trimEnd('/'); require(text.isNotBlank()) { "Enter a server address." }
        if(text.startsWith("http://",ignoreCase=true)||text.startsWith("https://",ignoreCase=true)) return validate(text)
        val host=(if(text.startsWith('[')) text.substringAfter('[').substringBefore(']') else text.substringBefore('/').substringBefore(':')).lowercase()
        val bytes=host.split('.').mapNotNull { it.toIntOrNull()?.takeIf { n->n in 0..255 } }
        val local=host=="localhost"||host.endsWith(".local")||host.endsWith(".lan")||host.endsWith(".home")||host=="::1"||host.startsWith("fe80:")||host.startsWith("fc")&&host.contains(':')||host.startsWith("fd")&&host.contains(':')||
            bytes.size==4&&(bytes[0]==10||bytes[0]==127||bytes[0]==192&&bytes[1]==168||bytes[0]==172&&bytes[1] in 16..31||bytes[0]==169&&bytes[1]==254)||!host.contains('.')&&!host.contains(':')
        return validate((if(local) "http://" else "https://")+text)
    }
}
