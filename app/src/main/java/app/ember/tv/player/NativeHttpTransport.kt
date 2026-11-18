package app.ember.tv.player

import app.ember.tv.data.Net
import app.ember.tv.data.SubtitleFiles
import okhttp3.*
import okio.buffer
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** libmpv uses opaque loopback URLs. OkHttp owns TLS, redirect policy and credentials.
 * Adaptive manifests are rewritten to the same transport, including DASH templates.
 * Bodies and credentials never enter a disk cache or exported diagnostics.
 */
internal class NativeHttpTransport(private val headers:Map<String,String>,origins:List<String>,private val timing:StreamConfig=StreamConfig()):Closeable {
    private val relay=MediaRelay()
    private val active=ConcurrentHashMap.newKeySet<Call>()
    private val bases=ConcurrentHashMap<String,String>()
    private data class Metadata(val length:Long,val mime:String,val acceptsRanges:Boolean)
    private val metadata=object:LinkedHashMap<String,Metadata>(256,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Metadata>?)=size>256
    }
    private val authOrigins=origins.mapNotNull {runCatching {it.toHttpUrl().let(::origin)}.getOrNull()}.toSet()
    private val http=Net.client(timing.normalized().connectSeconds.toLong(),timing.normalized().readSeconds.toLong()).newBuilder().cache(null).followRedirects(false).followSslRedirects(false).build()
    @Volatile private var closed=false
    @Volatile var failureCode:Int?=null;private set
    private fun origin(url:HttpUrl)="${url.scheme}://${url.host}:${url.port}"
    fun expose(value:String):String {
        if(!value.startsWith("http://")&&!value.startsWith("https://"))return value
        val url=value.toHttpUrl();require(url.username.isEmpty()&&url.password.isEmpty()) {"Use a saved account instead of URL credentials."}
        val base=synchronized(bases) {bases.getOrPut(origin(url)) {relay.origin {suffix->
            val resolved=url.newBuilder().encodedPath("/").query(null).fragment(null).build().resolve(suffix)?:error("Invalid media path.")
            require(origin(resolved)==origin(url));resource(resolved)
        }}}
        return base+url.encodedPath+(url.encodedQuery?.let {"?$it"}?:"")
    }
    private fun request(initial:HttpUrl,head:Boolean=false,range:String?=null):Response {
        var url=initial
        repeat(6) {
            check(!closed)
            val request=Request.Builder().url(url).header("Cache-Control","no-store").apply {
                headers.forEach {(k,v)->if(origin(url) in authOrigins||k.equals("User-Agent",true))header(k,v)}
                if(head)head()
                if(range!=null)header("Range",range)
            }.build()
            val call=http.newCall(request);active+=call
            val response=try {call.execute()}catch(e:Exception){active-=call;failureCode=2001;throw e}
            // Closing the response body also releases its cancellable call registration.
            if(response.code in setOf(301,302,303,307,308)) {
                val next=url.resolve(response.header("Location").orEmpty());response.close();active-=call
                check(next!=null && !(url.isHttps&&!next.isHttps) && next.username.isEmpty()&&next.password.isEmpty()) {"Unsafe media redirect."}
                url=next
            } else {
                failureCode=if(response.isSuccessful)null else 2004
                val original=response.body
                val body=object:ResponseBody() {
                    override fun contentType()=original.contentType()
                    override fun contentLength()=original.contentLength()
                    private val stream=object:okio.ForwardingSource(original.source()) {override fun close(){try{super.close()}finally{active-=call}}}.buffer()
                    override fun source()=stream
                }
                return response.newBuilder().body(body).build()
            }
        };error("Too many media redirects.")
    }
    private fun manifest(url:HttpUrl,fetched:Response?=null):RemoteMedia {
        var final=url;var useDash=url.encodedPath.endsWith(".mpd",true)
        val bytes=(fetched ?: request(url)).use {response->
            check(response.isSuccessful);final=response.request.url
            val type=response.header("Content-Type").orEmpty()
            useDash=when {type.contains("dash+xml",true)->true;type.contains("mpegurl",true)->false;else->useDash||final.encodedPath.endsWith(".mpd",true)}
            SubtitleFiles.read(response.body.byteStream(),4*1024*1024)
        }
        val text=String(bytes,Charsets.UTF_8)
        val mapped=if(useDash)dash(text,final) else hls(text,final)
        val content=mapped.toByteArray()
        return object:RemoteMedia {
            override val length=content.size.toLong()
            override val mime=if(useDash)"application/dash+xml" else "application/vnd.apple.mpegurl"
            override fun open(offset:Long)=content.inputStream().apply {skip(offset)}
        }
    }
    private fun isManifest(url:HttpUrl,type:String="")=listOf(".m3u8",".m3u",".mpd").any {url.encodedPath.endsWith(it,true)}||type.contains("mpegurl",true)||type.contains("dash+xml",true)
    private fun manifestResponse(media:RemoteMedia,range:String?,head:Boolean):RemoteHttpResponse {
        val bytes=ByteRange.parse(range,media.length) ?: return RemoteHttpResponse(416,0,media.mime,"bytes */${media.length}")
        val count=bytes.end-bytes.start+1
        val stream=if(head)null else object:java.io.FilterInputStream(media.open(bytes.start)) {
            private var remaining=count
            override fun read():Int {if(remaining<=0)return -1;return super.read().also {if(it>=0)remaining--}}
            override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                if(length==0)return 0
                if(remaining<=0)return -1
                return `in`.read(buffer,offset,minOf(length.toLong(),remaining).toInt()).also {if(it>0)remaining-=it}
            }
        }
        return RemoteHttpResponse(if(range==null)200 else 206,count,media.mime,if(range==null)null else "bytes ${bytes.start}-${bytes.end}/${media.length}",true,stream)
    }
    private fun resource(url:HttpUrl):RemoteMedia=object:RemoteHttpMedia {
        override fun response(range:String?,head:Boolean):RemoteHttpResponse {
            check(!closed)
            if(isManifest(url))return manifestResponse(manifest(url),range,head)
            val cached=synchronized(metadata){metadata[url.toString()]}
            if(head&&range==null&&cached!=null)return RemoteHttpResponse(200,cached.length,cached.mime,acceptsRanges=cached.acceptsRanges)
            var response=request(url,head,range)
            var upstreamRange=range
            if(head&&!response.isSuccessful) {
                response.close()
                upstreamRange=range ?: "bytes=0-0"
                response=request(url,range=upstreamRange)
            }
            val type=response.header("Content-Type").orEmpty()
            if(isManifest(response.request.url,type)) {
                val final=response.request.url
                val fetched=if(response.request.method=="GET"&&response.code==200)response else {response.close();null}
                return manifestResponse(manifest(final,fetched),range,head)
            }
            val result=response.relayResponse(head,upstreamRange)
            if(result.code in setOf(200,206)) {
                val size=if(result.code==206)result.contentRange?.substringAfterLast('/')?.toLongOrNull() ?: -1 else result.length
                synchronized(metadata){if(!closed)metadata[url.toString()]=Metadata(size,result.mime,result.acceptsRanges)}
                if(head&&range==null&&result.code==206)return RemoteHttpResponse(200,size,result.mime,acceptsRanges=result.acceptsRanges)
            }
            return result
        }
    }
    private fun mapped(base:HttpUrl,value:String):String {
        val resolved=base.resolve(value)?:error("Invalid manifest reference.")
        return expose(resolved.toString())
    }
    internal fun hls(text:String,base:HttpUrl):String=text.lineSequence().joinToString("\n") {line->
        when {line.isBlank()->line;!line.startsWith('#')->mapped(base,line.trim())
            else->Regex("URI=\"([^\"]+)\"").replace(line){"URI=\"${mapped(base,it.groupValues[1])}\""}}
    }
    internal fun dash(text:String,base:HttpUrl):String {
        val document=SafePlayerXml.parse(text)
        fun rewrite(element:org.w3c.dom.Element,parent:HttpUrl) {
            val children=(0 until element.childNodes.length).mapNotNull {element.childNodes.item(it) as? org.w3c.dom.Element}
            children.filter {it.localName=="Location"}.forEach {it.textContent=mapped(parent,it.textContent.trim())}
            val urls=children.filter {it.localName=="BaseURL"}
            val effective=urls.firstOrNull()?.textContent?.trim()?.let {parent.resolve(it)}?:parent
            urls.forEach {it.textContent=mapped(parent,it.textContent.trim())}
            listOf("media","initialization","sourceURL","index","href").forEach {key->
                if(element.hasAttribute(key)){val value=element.getAttribute(key);if(value.isNotEmpty())element.setAttribute(key,mapped(effective,value))}
            }
            children.filter {it.localName!="BaseURL" && it.localName!="Location"}.forEach {rewrite(it,effective)}
        }
        rewrite(document.documentElement,base)
        val output=java.io.StringWriter();TransformerFactory.newInstance().newTransformer().transform(DOMSource(document),StreamResult(output));return output.toString()
    }
    override fun close(){closed=true;active.forEach {it.cancel()};active.clear();relay.close();synchronized(metadata){metadata.clear()};bases.clear()}
}
