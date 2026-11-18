package app.ember.tv.player

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class HttpMediaResponseTest {
    private fun response(code:Int=206,range:String?="bytes 2-4/10",length:String?="3"):Response =
        Response.Builder().request(Request.Builder().url("https://media.example/video").build())
            .protocol(Protocol.HTTP_1_1).code(code).message("Media").apply {
                if(range!=null)header("Content-Range",range)
                if(length!=null)header("Content-Length",length)
            }.body("234".toResponseBody()).build()
    @Test fun aRangeReadUsesTheExistingBodyAndPreservesItsHeaders() {
        response().relayResponse(false,"bytes=2-4").use {
            assertEquals(206,it.code);assertEquals(3L,it.length)
            assertEquals("bytes 2-4/10",it.contentRange)
            assertEquals("234",it.body!!.bufferedReader().readText())
        }
    }
    @Test fun mismatchedRangesAndIgnoredSeeksAreRejected() {
        val invalid=listOf(response(range="bytes 0-2/10"),response(range="bytes 2-10/10"),response(length="10"),response(code=200,range=null))
        for(value in invalid) {
            try {value.relayResponse(false,"bytes=2-4");fail("Expected an invalid seek response")}catch(_:IllegalStateException){}
        }
    }
    @Test fun headAndUnsatisfiableRangesNeverExposeABody() {
        response().relayResponse(true,"bytes=2-4").use {assertNull(it.body);assertEquals(3L,it.length)}
        response(code=416,range="bytes */10").relayResponse(false,"bytes=20-").use {
            assertNull(it.body);assertEquals(0L,it.length);assertEquals("bytes */10",it.contentRange)
        }
    }
}
