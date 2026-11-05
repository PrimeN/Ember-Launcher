package app.ember.tv.iptv

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Streams an XMLTV guide (plain or .gz) and keeps only the channels Ember shows and a time window,
 * so even very large guides fit in memory.
 */
object Xmltv {
    /** Returns programmes by XMLTV channel id, plus channel display names → id for matching by name. */
    fun parse(input: InputStream, wanted: Set<String>, wantedNames: Set<String>, from: Long, to: Long): Pair<Map<String, List<Programme>>, Map<String, String>> {
        val parsed = parseDetailed(input, wanted, wantedNames, from, to)
        return parsed.programmes to parsed.names
    }

    fun parseDetailed(input: InputStream, wanted: Set<String>, wantedNames: Set<String>, from: Long, to: Long,
        parserFactory:()->XmlPullParser={Xml.newPullParser()}): ParsedEpg {
        val buffered = BufferedInputStream(input, 65536)
        buffered.mark(2)
        val gz = buffered.read() == 0x1f && buffered.read() == 0x8b
        buffered.reset()
        val stream = if (gz) GZIPInputStream(buffered, 65536) else buffered

        val parser = parserFactory()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, null)

        val out = HashMap<String, MutableList<Programme>>()
        val nameIndex=EpgNameIndex(wantedNames)
        val labels = LinkedHashMap<String, String>()
        var channelId: String? = null
        var progChannel: String? = null
        var start = 0L
        var stop = 0L
        var title: String? = null
        var desc: String? = null
        var image:String?=null;var subtitle:String?=null;var rating:String?=null;var category:String?=null
        var season:Int?=null;var episode:Int?=null;var inRating=false
        var keep = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> channelId = parser.getAttributeValue(null, "id")
                    "display-name" -> {
                        val id = channelId
                        if (id != null) {
                            val n = parser.nextText().trim()
                            if (labels.size < 40_000) labels.putIfAbsent(id, n)
                            nameIndex.add(n,id)
                        }
                    }
                    "programme" -> {
                        progChannel = parser.getAttributeValue(null, "channel")
                        start = time(parser.getAttributeValue(null, "start"))
                        stop = time(parser.getAttributeValue(null, "stop"))
                        keep = progChannel != null && start!=0L && stop>start && (progChannel in wanted || nameIndex.containsId(progChannel)) && stop > from && start < to
                        title = null
                        desc = null
                        image=null;subtitle=null;rating=null;category=null;season=null;episode=null;inRating=false
                    }
                    "icon" -> if(keep && image==null)image=EpgMetadata.artwork(parser.getAttributeValue(null,"src"))
                    "sub-title" -> if(keep && subtitle==null)subtitle=parser.nextText().trim().take(160).ifBlank {null}
                    "category" -> if(keep && category==null)category=parser.nextText().trim().take(80).ifBlank {null}
                    "rating" -> if(keep)inRating=true
                    "value" -> if(keep && inRating && rating==null)rating=parser.nextText().trim().take(32).ifBlank {null}
                    "episode-num" -> if(keep && season==null && episode==null) {
                        val system=parser.getAttributeValue(null,"system")
                        val numbers=EpgMetadata.episode(parser.nextText(),system);season=numbers.first;episode=numbers.second
                    }
                    "title" -> if (keep && title == null) title = parser.nextText().trim()
                    "desc" -> if (keep && desc == null) desc = parser.nextText().trim()
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "channel" -> channelId = null
                    "rating" -> inRating=false
                    "programme" -> {
                        val c = progChannel
                        if (keep && c != null && title != null && stop > start) {
                            // Descriptions only for the next few hours (what the list and guide show in detail);
                            // later programmes keep their title. Big guides then fit in memory.
                            val keepDesc = start < from + 10 * 3600_000L
                            out.getOrPut(c) { ArrayList() } += Programme(start,stop,title!!,if(keepDesc)desc?.ifBlank {null}?.take(400) else null,
                                image=if(keepDesc)image else null,subtitle=subtitle,rating=rating,category=category,season=season,episode=episode)
                        }
                        keep = false
                    }
                }
            }
            event = parser.next()
        }
        out.keys.forEach { labels.putIfAbsent(it, it) }
        return ParsedEpg(out.mapValues { (_, l) -> l.sortedBy { it.start } }, nameIndex.names, labels)
    }

    private val qualityWords=Regex("""\b(hd|fhd|uhd|4k|sd|hevc)\b""")
    fun norm(s: String) = s.lowercase(Locale.ROOT).replace(qualityWords, "").filter { it.isLetterOrDigit() }

    private val timestamp=Regex("^(\\d{14}|\\d{12})(?:\\s*(Z|[+-]\\d{4}))?$")
    private val seconds=DateTimeFormatter.ofPattern("uuuuMMddHHmmss",Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)
    private val minutes=DateTimeFormatter.ofPattern("uuuuMMddHHmm",Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)

    /** XMLTV times look like "20261020193000 +0000". */
    fun time(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        val match=timestamp.matchEntire(s.trim()) ?: return 0
        val value=match.groupValues[1]
        return runCatching {
            val offset=match.groupValues[2].ifEmpty {"Z"}
            LocalDateTime.parse(value,if(value.length==14)seconds else minutes).toInstant(ZoneOffset.of(offset)).toEpochMilli()
        }.getOrDefault(0)
    }
}
