package app.ember.tv.player

import app.ember.tv.data.Net
import app.ember.tv.data.SubtitleFiles
import okhttp3.*
import okio.buffer
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** libmpv uses opaque loopback URLs. OkHttp owns TLS, redirect policy and credentials.
 * Adaptive manifests are rewritten to the same transport, including DASH templates.
 * Bodies and credentials never enter a disk cache or exported diagnostics.
 */
internal class NativeHttpTransport(private val headers:Map<String,String>,origins:List<String>):Closeable {
    private val relay=MediaRelay()
    private val active=ConcurrentHashMap.newKeySet<Call>()
    private val bases=ConcurrentHashMap<String,String>()
    private val authOrigins=origins.mapNotNull {runCatching {it.toHttpUrl().let(::origin)}.getOrNull()}.toSet()
    private val http=Net.client(20,30).newBuilder().cache(null).followRedirects(false).followSslRedirects(false).build()
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
    private fun request(initial:HttpUrl,head:Boolean=false,offset:Long=0):Response {
        var url=initial
        repeat(6) {
            check(!closed)
            val request=Request.Builder().url(url).header("Cache-Control","no-store").apply {
                headers.forEach {(k,v)->if(origin(url) in authOrigins||k.equals("User-Agent",true))header(k,v)}
                if(head)head() else if(offset>0)header("Range","bytes=$offset-")
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
    private fun resource(url:HttpUrl):RemoteMedia {
        var final=url
        var type="";var size=-1L
        request(url,head=true).use {r ->
            if(r.isSuccessful){type=r.header("Content-Type").orEmpty().lowercase();size=r.header("Content-Length")?.toLongOrNull()?:-1;final=r.request.url}
        }
        if(type.isEmpty())request(final).use {r->if(r.isSuccessful){type=r.header("Content-Type").orEmpty().lowercase();size=r.header("Content-Length")?.toLongOrNull()?:-1;final=r.request.url}}
        val path=final.encodedPath.lowercase()
        if(path.endsWith(".m3u8")||path.endsWith(".m3u")||path.endsWith(".mpd")||type.contains("mpegurl")||type.contains("dash+xml")) {
            val bytes=request(final).use {r->check(r.isSuccessful);final=r.request.url;SubtitleFiles.read(r.body.byteStream(),4*1024*1024)}
            val text=String(bytes,Charsets.UTF_8)
            val mapped=if(path.endsWith(".mpd")||type.contains("dash+xml"))dash(text,final) else hls(text,final)
            val content=mapped.toByteArray()
            return object:RemoteMedia {override val length=content.size.toLong();override fun open(offset:Long)=content.inputStream().apply {skip(offset)}}
        }
        return object:RemoteMedia {
            override val length=size
            override fun open(offset:Long):InputStream {
                val response=request(final,offset=offset)
                if(!response.isSuccessful || offset>0&&(response.code!=206||response.header("Content-Range")?.startsWith("bytes $offset-")!=true)) {response.close();error("Media source cannot seek.")}
                return object:java.io.FilterInputStream(response.body.byteStream()){override fun close(){response.close()}}
            }
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
    override fun close(){closed=true;active.forEach {it.cancel()};active.clear();relay.close()}
}
