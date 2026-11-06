package app.ember.tv.mediaserver

import app.ember.tv.data.Meta
import app.ember.tv.data.Episode
import app.ember.tv.data.StreamOption
import app.ember.tv.data.SubtitleOption
import java.util.Base64
import java.util.UUID

/** Persistent references contain identity only, never tokens, URLs or account passwords. */
data class ServerRef(val provider: String, val account: String, val item: String) {
    val value get() = "$provider|$account|$item"
    companion object {
        fun parse(value: String?): ServerRef? = value?.split('|', limit = 3)?.takeIf { it.size == 3 && it[0] in setOf("plex", "jellyfin", "emby") && it.all(String::isNotBlank) }?.let { ServerRef(it[0],it[1],it[2]) }
        fun of(meta: Meta): ServerRef? = parse(meta.serverRef) ?: meta.id.split(':', limit=3).takeIf { it.size==3 }?.let {
            val provider=it[0].removeSuffix("show"); if(provider in setOf("plex","jellyfin","emby")) ServerRef(provider,it[1],it[2]) else null
        }
    }
}
data class ServerPage(val items: List<Meta>, val next: Int, val end: Boolean, val total: Int? = null)
data class ServerSeason(val number: Int, val title: String)
data class ServerFilter(val query: String = "", val sort: String = "added", val unwatched: Boolean = false, val genre: String? = null, val collections: Boolean = false, val parent: String? = null)
data class ServerPart(val id: String, val url: String, val durationMs: Long? = null, val subtitles: List<SubtitleOption> = emptyList())
/** Runtime-only transport. Never serialize this object into preferences/library/cache metadata. */
data class ServerPlayback(val ref: ServerRef, val sourceId: String, val parts: List<ServerPart>, val durationMs: Long?,
    val seriesRef: String? = null, val sessionId: String = UUID.randomUUID().toString(), val method: String = "DirectPlay", val stopUrl: String? = null, val quality: ServerQuality = ServerQuality.ORIGINAL) {
    /** Media3 requires a finite placeholder for every progressive child, even before probing. */
    fun placeholderDuration(index: Int): Long {
        parts[index].durationMs?.takeIf { it > 0 }?.let { return it }
        val missing = parts.count { (it.durationMs ?: 0) <= 0 }.coerceAtLeast(1)
        val known = parts.sumOf { it.durationMs?.coerceAtLeast(0) ?: 0L }
        return durationMs?.let { ((it - known).coerceAtLeast(missing * 1000L) / missing) } ?: 60_000L
    }
    fun withProbedDurations(values: List<Long>): ServerPlayback? = if (values.size == parts.size && values.all { it > 0 })
        copy(parts = parts.mapIndexed { i, part -> part.copy(durationMs = values[i]) }, durationMs = values.sum()) else null
    fun offset(index: Int): Long? { val before=parts.take(index); return if(before.all { (it.durationMs ?: 0L) > 0L }) before.sumOf { it.durationMs!! } else null }
    fun position(index: Int, local: Long): Long = (offset(index) ?: 0L) + local
    fun seek(overall: Long): Pair<Int,Long>? {
        var left=overall.coerceAtLeast(0)
        parts.forEachIndexed { i,p -> val duration=p.durationMs?.takeIf { it > 0L }
            if(i==parts.lastIndex || duration!=null && left<duration) return i to left
            if(duration==null) return null
            left-=duration
        }; return null
    }
}
object ServerPlaybackRegistry {
    private val values=LinkedHashMap<String,ServerPlayback>()
    @Synchronized fun put(value: ServerPlayback): String { values[value.sessionId]=value; while(values.size>8) values.remove(values.keys.first()); return value.sessionId }
    @Synchronized fun get(id: String?) = values[id]
    @Synchronized fun remove(id: String?) { values.remove(id) }
}
object ServerMetadata {
    private const val PREFIX="ember-art://"
    internal fun versioned(path:String,version:String?):String=version?.takeIf(String::isNotBlank)?.let {
        path+(if('?' in path)"&" else "?")+"tag="+java.net.URLEncoder.encode(it,"UTF-8")
    } ?: path
    fun artwork(ref: ServerRef, path: String?): String? = path?.takeIf { it.startsWith('/') && !it.startsWith("//") }?.let {
        val enc=Base64.getUrlEncoder().withoutPadding()
        PREFIX+enc.encodeToString(ref.value.toByteArray())+"/"+enc.encodeToString(cleanPath(it).toByteArray())
    }
    fun artworkParts(value: String): Pair<ServerRef,String>? = runCatching {
        if(!value.startsWith(PREFIX)) return null
        val p=value.removePrefix(PREFIX).split('/',limit=2); val dec=Base64.getUrlDecoder()
        val ref=ServerRef.parse(String(dec.decode(p[0]))) ?: return null
        val path=String(dec.decode(p[1])); require(path.startsWith('/')&&!path.startsWith("//")); ref to path
    }.getOrNull()
    fun cleanPath(path: String): String {
        val query=path.substringAfter('?',"").split('&').filter { field -> java.net.URLDecoder.decode(field.substringBefore('='), "UTF-8").lowercase() !in setOf("x-plex-token","x-emby-token","api_key","apikey","api-key","token","access_token","authorization","auth","signature","sig") && field.isNotBlank() }
        return path.substringBefore('?')+if(query.isEmpty()) "" else "?"+query.joinToString("&")
    }
    fun safe(meta: Meta): Meta {
        val ref=ServerRef.of(meta) ?: return meta.copy(playUrl=null)
        fun image(value: String?): String? {
            if(value==null || value.startsWith(PREFIX)) return value
            return runCatching { val u=java.net.URI(value); val path=u.rawPath.orEmpty()
                if(cleanPath(path+(u.rawQuery?.let { "?$it" }?:"")) != path+(u.rawQuery?.let { "?$it" }?:"") || path.contains("/library/")||path.contains("/Items/")) artwork(ref,path+(u.rawQuery?.let { "?$it" }?:"")) else value
            }.getOrNull()
        }
        return meta.copy(playUrl=null,serverRef=ref.value,poster=image(meta.poster),background=image(meta.background),logo=image(meta.logo),episodeThumbnail=image(meta.episodeThumbnail))
    }
    fun episode(meta: Meta) = Episode(meta.serverRef ?: meta.id,meta.season ?: 1,meta.episode ?: 1,meta.badge ?: meta.name,null,meta.episodeThumbnail ?: meta.poster,meta.description,meta.serverRef,meta.serverSeriesRef,meta.progress,meta.watched)
}
