package app.ember.tv.data

/** Bounded SAMI text conversion. Markup is parsed as text, never loaded or executed. */
internal object SamiSubtitles {
    private val sync=Regex("(?is)<sync\\b([^>]{0,4096})>")
    private val start=Regex("(?i)\\bstart\\s*=\\s*[\"']?(\\d+)")
    private val paragraph=Regex("(?is)<p\\b([^>]{0,4096})>(.*?)(?=<p\\b|$)")
    private val klass=Regex("(?i)\\bclass\\s*=\\s*[\"']?([\\w-]+)")
    private val styles=Regex("(?is)\\.([\\w-]+)\\s*\\{([^}]{0,4096})}")
    private val language=Regex("(?i)\\blang\\s*:\\s*([\\w-]+)")
    private val ignored=Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>")
    private val tags=Regex("(?s)<[^>]{0,4096}>")
    private val entity=Regex("&(#x[0-9a-fA-F]{1,6}|#\\d{1,7}|[A-Za-z]{2,8});")
    fun toSrt(text:String, preferred:List<String> = emptyList()):String? {
        if(text.length>SubtitleFiles.MAX_DOWNLOAD)return null
        val markers=sync.findAll(text).take(50_001).toList()
        if(markers.isEmpty() || markers.size>50_000)return null
        val classes=styles.findAll(text.take(64*1024)).mapNotNull {m->language.find(m.groupValues[2])?.groupValues?.get(1)?.let {m.groupValues[1].lowercase() to it.lowercase().substringBefore('-')}}.toMap()
        val available=markers.take(64).flatMapIndexed {index,m->
            val body=text.substring(m.range.last+1,markers.getOrNull(index+1)?.range?.first ?: text.length)
            paragraph.findAll(body).mapNotNull {p->klass.find(p.groupValues[1])?.groupValues?.get(1)?.lowercase()}.take(16).toList()
        }
        val chosen=preferred.asSequence().map {it.lowercase().substringBefore('-')}.mapNotNull {wanted->available.firstOrNull {classes[it]==wanted}}.firstOrNull() ?: available.firstOrNull()
        data class Row(val at:Long,val text:String)
        val rows=markers.mapIndexedNotNull {index,m->
            val at=start.find(m.groupValues[1])?.groupValues?.get(1)?.toLongOrNull()?.takeIf {it in 0..604_800_000L} ?: return@mapIndexedNotNull null
            val body=text.substring(m.range.last+1,markers.getOrNull(index+1)?.range?.first ?: text.length)
            val ps=paragraph.findAll(body).toList()
            val selected=if(ps.isEmpty())body else ps.filter {chosen==null || klass.find(it.groupValues[1])?.groupValues?.get(1)?.lowercase()==chosen}.joinToString("\n") {it.groupValues[2]}
            Row(at,plain(selected))
        }.sortedBy {it.at}.distinctBy {it.at}
        fun time(ms:Long)="%02d:%02d:%02d,%03d".format(java.util.Locale.ROOT,ms/3600000,ms/60000%60,ms/1000%60,ms%1000)
        var count=0
        val result=rows.mapIndexedNotNull {index,row->
            val end=rows.getOrNull(index+1)?.at ?: row.at+5000
            if(row.text.isBlank() || end<=row.at)null else "${++count}\n${time(row.at)} --> ${time(end)}\n${row.text}\n"
        }.joinToString("\n")
        return result.takeIf {count>0}
    }
    private fun plain(value:String):String {
        val stripped=tags.replace(ignored.replace(value," ").replace(Regex("(?i)<br\\s*/?>"),"\n"),"")
        val decoded=entity.replace(stripped) {m->
            val name=m.groupValues[1]
            when(name.lowercase()) {"nbsp"->" ";"amp"->"&";"lt"->"<";"gt"->">";"quot"->"\"";"apos"->"'"
                else->if(name.startsWith("#")) {
                    val code=if(name.startsWith("#x",true))name.drop(2).toIntOrNull(16) else name.drop(1).toIntOrNull()
                    code?.takeIf {Character.isValidCodePoint(it)&&it !in 0xD800..0xDFFF}?.let {String(Character.toChars(it))} ?: ""
                } else m.value
            }
        }
        return decoded.lines().map {it.replace(Regex("[\\t \\u00a0]+")," ").trim()}.filter {it.isNotBlank()}.joinToString("\n")
    }
}
