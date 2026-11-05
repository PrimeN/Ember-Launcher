package app.ember.tv

import app.ember.tv.mediaserver.ServerHttp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ServerRedirectPolicyTest {
    private suspend fun requests(code:Int,method:String):List<Request> {
        val requests=mutableListOf<Request>()
        val client=ServerHttp(OkHttpClient.Builder().addInterceptor {chain->
            val request=chain.request();requests+=request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("fixture")
                .code(if(requests.size==1)code else 200)
                .apply {if(requests.size==1)header("Location","/final")}
                .body("{}".toResponseBody()).build()
        }.build())
        client.ack("https://server.example/start",mapOf("X-Emby-Token" to "fixture"),method,buildJsonObject {put("position",123)})
        assertEquals(2,requests.size)
        assertTrue(requests.all {it.header("X-Emby-Token")=="fixture"})
        return requests
    }
    @Test fun explicitServerSchemesAreCaseInsensitiveAndKeepPathCase() {
        assertEquals("https://example.com/Base/Api",app.ember.tv.mediaserver.ServerAddress.normalize("HTTPS://Example.COM/Base/Api"))
        assertEquals("http://tv.local:8096/Base",app.ember.tv.mediaserver.ServerAddress.normalize("Http://Tv.Local:8096/Base/"))
    }
    @Test fun postSeeOtherDoesNotRepeatTheMutation()=runBlocking {
        val seen=requests(303,"POST")
        assertEquals(listOf("POST","GET"),seen.map {it.method});assertNull(seen.last().body)
    }
    @Test fun postMovedAndFoundUseGet()=runBlocking {
        for(code in listOf(301,302))assertEquals(listOf("POST","GET"),requests(code,"POST").map {it.method})
    }
    @Test fun temporaryAndPermanentRedirectPreservePostAndBody()=runBlocking {
        for(code in listOf(307,308)) {
            val seen=requests(code,"POST")
            assertEquals(listOf("POST","POST"),seen.map {it.method})
            val body=okio.Buffer();seen.last().body!!.writeTo(body)
            assertEquals("{\"position\":123}",body.readUtf8())
        }
    }
    @Test fun deleteSeeOtherUsesGetBut307KeepsDelete()=runBlocking {
        assertEquals(listOf("DELETE","GET"),requests(303,"DELETE").map {it.method})
        assertEquals(listOf("DELETE","DELETE"),requests(307,"DELETE").map {it.method})
    }
}
