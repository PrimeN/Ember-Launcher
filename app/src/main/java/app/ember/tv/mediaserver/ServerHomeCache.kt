package app.ember.tv.mediaserver

import android.content.Context
import app.ember.tv.tracker.TokenStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Optional encrypted metadata only. Playback transport is resolved afresh when a title opens. */
internal class ServerHomeCache(
    private val read:(String)->String?,
    private val write:(String,String)->Boolean,
    private val now:()->Long=System::currentTimeMillis,
) {
    constructor(context:Context):this(TokenStore(context))
    private constructor(store:TokenStore):this(store::get,store::putEncrypted)
    private val json=Json {ignoreUnknownKeys=true}
    @Serializable data class Snapshot(val fingerprint:String,val at:Long,val providers:Map<String,List<ServerRow>>)
    fun load(profile:String,fingerprint:String):Snapshot? = runCatching {
        json.decodeFromString<Snapshot>(read("server.home.$profile")?:return null)
            .takeIf {it.fingerprint==fingerprint && now()-it.at in 0..7*24*60*60*1000L}
    }.getOrNull()
    fun save(profile:String,fingerprint:String,providers:Map<String,List<ServerRow>>,at:Long) {
        val bounded=providers.mapValues {(_,rows)->rows.take(16).map {r->r.copy(items=r.items.take(30).map(ServerMetadata::safe))}}
        write("server.home.$profile",json.encodeToString(Snapshot(fingerprint,at,bounded)))
    }
}
