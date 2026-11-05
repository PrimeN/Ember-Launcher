package app.ember.tv.player

import okhttp3.HttpUrl
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2
import java.io.FilterReader
import java.io.Reader
import javax.xml.parsers.SAXParserFactory

internal class ListingLimitException:java.io.IOException("Directory listing exceeds the browse limit.")

internal data class NetworkPage(val entries: List<NetworkEntry>, val next: Int?)

/** Retains one page plus a lookahead, even when a server has hundreds of thousands of files. */
internal class NetworkListing(private val path: String, private val offset: Int, private val limit: Int = 200,
    private val check: () -> Unit = {}) {
    private var seen = 0
    private val page = linkedMapOf<String, NetworkEntry>()
    private var more = false
    init { require(offset >= 0 && limit in 1..500) }
    fun add(entry: NetworkEntry): Boolean {
        check()
        if (runCatching { NetworkLocation.child(path, entry.name) }.isFailure) return true
        if (seen++ < offset) return true
        if (entry.name in page) return true
        if (page.size == limit) { more = true; return false }
        page[entry.name] = entry
        return true
    }
    fun result() = NetworkPage(page.values.sortedWith(compareByDescending<NetworkEntry> { it.directory }.thenBy { it.name.lowercase() }), if (more) offset + page.size else null)
}

internal object MediaFileTypes {
    private val extensions = setOf("mkv","mp4","m4v","avi","mov","wmv","webm","ts","m2ts","mts","mpeg","mpg","vob","ogv","flv","3gp","asf","mxf","divx","rm","rmvb","iso","mp3","flac","aac","m4a","ogg","opus","wav","wma","aiff","aif","ac3","eac3","dts","mka","ape","alac","amr","m3u8","mpd")
    fun playable(entry: NetworkEntry) = !entry.directory && entry.size > 0 && entry.name.substringAfterLast('.', "").lowercase() in extensions
}

/** Streaming DAV parsing: no directory DOM, external entities, or unbounded XML text. */
internal object WebDavListing {
    private class Done : SAXException()
    fun parse(reader: Reader, base: HttpUrl, listing: NetworkListing) {
        val guarded = object : FilterReader(reader) {
            var tail = ""; var total = 0
            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                val n = super.read(cbuf, off, minOf(len, 8192))
                if (n > 0) {
                    total += n; if(total > 64 * 1024 * 1024)throw ListingLimitException()
                    val text = tail + String(cbuf, off, n)
                    require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true) && '\u0000' !in text) { "Unsafe directory XML." }
                    tail = text.takeLast(10)
                }
                return n
            }
            override fun read(): Int { val one = CharArray(1); return if (read(one,0,1) < 0) -1 else one[0].code }
        }
        val handler = object : DefaultHandler2() {
            var depth = 0; var responseDepth = 0; var field: String? = null
            val text = StringBuilder(); var href = ""; var size = 0L; var directory = false
            override fun startDTD(name: String?, publicId: String?, systemId: String?) { throw SAXException("DTD disabled") }
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource { throw SAXException("Entities disabled") }
            override fun startElement(uri: String, local: String, q: String, attrs: org.xml.sax.Attributes) {
                require(++depth <= 64) { "Directory XML nesting is too deep." }
                if (uri != "DAV:") return
                when (local) {
                    "response" -> { responseDepth = depth; href = ""; size = 0; directory = false }
                    "href", "getcontentlength" -> { field = local; text.clear() }
                    "collection" -> directory = true
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (field != null) { require(text.length + length <= 16 * 1024) { "Directory XML field is too long." }; text.append(ch,start,length) }
            }
            override fun endElement(uri: String, local: String, q: String) {
                if (uri == "DAV:" && local == field) {
                    if (field == "href") href = text.toString().trim() else size = text.toString().trim().toLongOrNull() ?: 0
                    field = null
                }
                if (uri == "DAV:" && local == "response" && depth == responseDepth) {
                    val u = base.resolve(href)
                    val root = base.encodedPath.trimEnd('/') + "/"
                    if (u != null && u.scheme == base.scheme && u.host == base.host && u.port == base.port && u.encodedPath.startsWith(root)) {
                        val child = u.encodedPath.trimEnd('/').removePrefix(root)
                        if (child.isNotEmpty() && '/' !in child) {
                            val name = u.pathSegments.lastOrNull { it.isNotEmpty() }
                            if (name != null && !listing.add(NetworkEntry(name,directory,size))) throw Done()
                        }
                    }
                }
                depth--
            }
        }
        val parser = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
        parser.contentHandler = handler; parser.entityResolver = handler
        runCatching { parser.setProperty("http://xml.org/sax/properties/lexical-handler",handler) }
        try { parser.parse(InputSource(guarded)) } catch (_: Done) { /* page is complete */ }
    }
}
