package app.ember.tv.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

/** Session-only, bounded diagnostics. Raw source IDs/names, URLs and exceptions are never retained. */
internal data class SourceDiagnostic(val group:String,val reference:String,val at:Long,val durationMillis:Long?,
    val success:Boolean,val cached:Boolean,val empty:Boolean,val failure:SourceFailure?)
internal object SourceDiagnostics {
    private const val LIMIT=64
    private val current=MutableStateFlow<List<SourceDiagnostic>>(emptyList())
    val records=current.asStateFlow()
    private val groups=setOf("catalogs","servers","iptv","trackers","search","playback","sources")
    @Synchronized fun record(group:String?,id:String,at:Long,duration:Long?,success:Boolean,cached:Boolean,empty:Boolean,failure:SourceFailure?) {
        val category=group?.takeIf {it in groups} ?: "sources"
        val reference=java.security.MessageDigest.getInstance("SHA-256").digest("$category:$id".toByteArray())
            .take(6).joinToString("") {"%02x".format(it)}
        val row=SourceDiagnostic(category,reference,at,duration,success,cached,empty,if(success)null else failure ?: SourceFailure.UNAVAILABLE)
        current.value=(listOf(row)+current.value.filterNot {it.group==category && it.reference==reference}).take(LIMIT)
    }
    @Synchronized fun clear() {current.value=emptyList()}
    fun export():String=buildJsonObject {
        put("schema",1)
        put("sources",buildJsonArray {current.value.forEach {row->add(buildJsonObject {
            put("group",row.group);put("reference",row.reference);put("at",row.at)
            row.durationMillis?.let {put("durationMillis",it)}
            put("success",row.success);put("cached",row.cached);put("empty",row.empty)
            row.failure?.let {put("failure",it.name.lowercase(java.util.Locale.ROOT))}
        })}})
    }.toString()
}

/** A typed status allows diagnostics without parsing or retaining private error text. */
class AddonHttpException(val status:Int):java.io.IOException("Add-on returned HTTP $status.")
