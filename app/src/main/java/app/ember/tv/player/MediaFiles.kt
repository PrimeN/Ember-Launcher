package app.ember.tv.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import app.ember.tv.data.SubtitleFiles
import java.io.File

internal object MediaFiles {
    /** Failed activity launches must not leave relay resources or abandoned queues alive. */
    fun launch(context:Context,items:List<QueueMedia>,resources:List<java.io.Closeable> = emptyList()) {
        MediaQueues.launch(items,resources) {queue->context.startActivity(intent(context,items,queue))}
    }
    fun name(context:Context,uri:Uri):String=runCatching {
        context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {if(it.moveToFirst())it.getString(0) else null}
    }.getOrNull()?.take(180) ?: "Media file"
    fun retain(context:Context,uri:Uri) {runCatching {context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}}
    fun subtitle(context:Context,uri:Uri):Pair<String,String> {
        val bytes=context.contentResolver.openInputStream(uri)?.use {SubtitleFiles.read(it)} ?: error("File unavailable.")
        val text=SubtitleFiles.bom(bytes) ?: String(bytes,Charsets.UTF_8)
        return text to SubtitleFiles.format(name(context,uri),text.toByteArray())
    }
    /** A VobSub pair shares a generated basename; original file paths never enter the cache. */
    fun bitmap(context:Context,uris:List<Uri>):Pair<File,List<File>>? {
        val chosen=uris.associateBy {name(context,it).substringAfterLast('.').lowercase()}
        val idx=chosen["idx"];val sub=chosen["sub"]
        val pgs=chosen["sup"]
        if(pgs==null && (idx==null||sub==null))return null
        val dir=File(context.cacheDir,"player-sidecars").apply {mkdirs()}
        val base=java.util.UUID.randomUUID().toString();val files=mutableListOf<File>()
        try {
            val formats=if(pgs!=null)listOf("sup") else listOf("idx","sub")
            for(ext in formats) {
                val bytes=context.contentResolver.openInputStream(chosen.getValue(ext))?.use {SubtitleFiles.read(it,SubtitleFiles.MAX_EXPANDED)} ?: error("Subtitle file unavailable.")
                files+=File(dir,"$base.$ext").apply {writeBytes(bytes)}
            }
            return files.first() to files.drop(1)
        } catch(e:Throwable) {files.forEach {it.delete()};throw e}
    }
    private fun intent(context:Context,items:List<QueueMedia>,queue:String):Intent {
        require(items.isNotEmpty())
        return Intent(context,PlayerActivity::class.java).putExtra("ember.fileQueue",queue)
            .putExtra(PlayRequest.EXTRA_URL,items.first().uri).putExtra(PlayRequest.EXTRA_TITLE,items.first().title)
            .putExtra(PlayRequest.EXTRA_RESUME_KEY,items.first().key)
    }
}
