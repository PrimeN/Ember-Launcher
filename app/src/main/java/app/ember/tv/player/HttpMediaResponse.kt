package app.ember.tv.player

import okhttp3.Response
import java.io.FilterInputStream

internal fun Response.relayResponse(head:Boolean,requestedRange:String?=null):RemoteHttpResponse {
    try {
        check(code in setOf(200,206,416)) {"Media request failed."}
        val contentRange=header("Content-Range")
        val length=header("Content-Length")?.toLongOrNull()?.takeIf {it>=0} ?: -1
        if(code==206) {
            val match=Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(contentRange.orEmpty()) ?: error("Invalid media range.")
            val first=match.groupValues[1].toLongOrNull() ?: error("Invalid media range.")
            val last=match.groupValues[2].toLongOrNull() ?: error("Invalid media range.")
            val total=match.groupValues[3].toLongOrNull()
            check(first<=last&&last<Long.MAX_VALUE&&(total==null&&match.groupValues[3]=="*"||total!=null&&last<total)) {"Invalid media range."}
            check(length<0||length==last-first+1) {"Invalid media range length."}
            if(requestedRange!=null) {
                if(total!=null) {
                    val expected=ByteRange.parse(requestedRange,total) ?: error("Invalid media range.")
                    check(first==expected.start&&last<=expected.end) {"Media source returned the wrong range."}
                } else {
                    val start=requestedRange.substringAfter("bytes=").substringBefore('-').toLongOrNull()
                    val end=requestedRange.substringAfter('-').toLongOrNull()
                    check(start!=null&&first==start&&(end==null||last<=end)) {"Media source returned the wrong range."}
                }
            }
        } else if(code==200&&requestedRange!=null) {
            check(requestedRange.substringAfter("bytes=").substringBefore('-').toLongOrNull()==0L) {"Media source cannot seek."}
        }
        val mime=header("Content-Type").orEmpty().substringBefore(';').trim().ifBlank {"application/octet-stream"}
        val stream=if(head)null else object:FilterInputStream(body.byteStream()) {
            override fun close(){this@relayResponse.close()}
        }
        return RemoteHttpResponse(code,if(code==416)0 else length,mime,contentRange,header("Accept-Ranges")=="bytes"||code==206,if(code==416)null else stream)
            .also {if(head||code==416)close()}
    }catch(e:Exception){close();throw e}
}
