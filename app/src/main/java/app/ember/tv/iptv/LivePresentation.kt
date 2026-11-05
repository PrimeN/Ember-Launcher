package app.ember.tv.iptv

/** Presentation uses authorized/visible channels only; it never expands the playback scope. */
object LivePresentation {
    fun initialChoice(channels:List<Channel>,sources:List<IptvSource>,library:LiveLibrary,favourites:Set<String>,catalogs:Boolean=false):LiveChoice {
        if("favourites" !in library.hiddenSections && channels.any {it.key in favourites})return LiveChoice("favourites")
        val source=sources.firstOrNull {s->s.enabled && channels.any {it.sourceId==s.id}} ?: sources.firstOrNull {it.enabled}
        if(source!=null)return LiveChoice("source",source.id)
        return if(catalogs && "addons" !in library.hiddenSections)LiveChoice("addons") else LiveChoice()
    }
    fun featured(channels:List<Channel>,favourites:Set<String>,recent:List<String>,guide:Map<String,List<Programme>>,clock:Long,limit:Int=20):List<Channel> {
        if(limit<=0 || channels.isEmpty())return emptyList()
        val byKey=channels.associateBy {it.key};val out=linkedMapOf<String,Channel>()
        fun add(channel:Channel) {if(out.size<limit)out.putIfAbsent(channel.key,channel)}
        for(key in favourites.asSequence()+recent.asSequence()) {
            if(out.size>=limit)break
            byKey[key]?.let(::add)
        }
        for(channel in channels) {
            if(out.size>=limit)break
            if(channel.key !in out && guide[channel.key].orEmpty().any {clock>=it.start && clock<it.stop})add(channel)
        }
        for(channel in channels) {
            if(out.size>=limit)break
            add(channel)
        }
        return out.values.toList()
    }
    fun progress(programme:Programme?,clock:Long):Float? = programme?.takeIf {it.stop>it.start && clock>=it.start && clock<it.stop}
        ?.let {((clock-it.start).toDouble()/(it.stop-it.start)).toFloat().coerceIn(0f,1f)}
    fun remainingMinutes(programme:Programme?,clock:Long):Long? = programme?.takeIf {progress(it,clock)!=null}
        ?.let {((it.stop-clock+59_999)/60_000).coerceAtLeast(1)}
    fun summary(programme:Programme?,guideStatus:String):String? =
        programme?.description?.takeIf(String::isNotBlank) ?: guideStatus.takeIf {programme==null}
    fun markerLabelLeft(marker:Float,width:Float,rail:Float,labelWidth:Float,rtl:Boolean):Float {
        val from=if(rtl)0f else rail;val to=if(rtl)width-rail else width
        return (marker+5).coerceIn(from,(to-labelWidth).coerceAtLeast(from))
    }
}

/** Bounded optional XMLTV metadata. Artwork may only load from HTTP(S), never local files. */
object EpgMetadata {
    fun artwork(value:String?):String? = value?.trim()?.takeIf {it.length<=4096}?.takeIf {raw->
        runCatching {val uri=java.net.URI(raw);uri.scheme?.lowercase() in setOf("http","https") && !uri.host.isNullOrBlank() && uri.rawUserInfo==null}.getOrDefault(false)
    }
    fun episode(value:String?,system:String?):Pair<Int?,Int?> {
        val text=value?.trim().orEmpty()
        if(system=="xmltv_ns") {
            val parts=text.split('.')
            fun number(index:Int)=parts.getOrNull(index)?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf {it in 0..9999}?.plus(1)
            return number(0) to number(1)
        }
        val m=Regex("(?i)S(\\d{1,4})\\s*E(\\d{1,4})").find(text)?:return null to null
        return m.groupValues[1].toIntOrNull()?.takeIf {it>0} to m.groupValues[2].toIntOrNull()?.takeIf {it>0}
    }
}
