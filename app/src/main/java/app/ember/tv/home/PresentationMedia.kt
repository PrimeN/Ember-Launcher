package app.ember.tv.home

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import app.ember.tv.data.AtomicFile
import java.io.File

internal object PresentationMedia {
    private val images=setOf("jpg","jpeg","png","webp","gif")
    private val videos=setOf("mp4","m4v","mkv","webm","mov")
    const val MAX_UPLOAD=8_000_000
    fun documents(context:Context,uris:List<Uri>):List<PresentationItem> = uris.mapNotNull {uri->runCatching {
        require(uri.scheme=="content")
        val mime=context.contentResolver.getType(uri).orEmpty();require(mime.startsWith("image/") || mime.startsWith("video/"))
        context.contentResolver.takePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val name=context.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use {if(it.moveToFirst())it.getString(0) else null} ?: "Media"
        PresentationItem(java.util.UUID.randomUUID().toString(),name.take(100),uri.toString(),mime.startsWith("video/"))
    }.getOrNull()}
    fun upload(context:Context,name:String,bytes:ByteArray):PresentationItem {
        val ext=name.substringAfterLast('.').lowercase(java.util.Locale.ROOT)
        require(ext in images+videos){"Choose an image, MP4, MKV, MOV or WebM file."}
        require(bytes.isNotEmpty() && bytes.size<=MAX_UPLOAD){"Uploads must be smaller than 8 MB. Larger videos can be chosen on the TV."}
        if(ext in images)AppArtImport.decode(bytes).recycle()
        val folder=File(context.filesDir,"presentation").apply {mkdirs()}
        require(folder.listFiles().orEmpty().sumOf {it.length()}+bytes.size<=128L*1024*1024){"Presentation storage is full. Remove some uploaded items."}
        val id=java.util.UUID.randomUUID().toString();val file=File(folder,"$id.$ext")
        AtomicFile.write(file,bytes).getOrThrow()
        return PresentationItem(id,name.substringAfterLast('/').substringAfterLast('\\').take(100),file.toUri().toString(),ext in videos)
    }
    fun safeUri(context:Context,value:String):Uri?=runCatching {
        val uri=value.toUri()
        when(uri.scheme){
            "content"->uri.takeIf {it.authority?.isNotBlank()==true}
            "file"->{val root=File(context.filesDir,"presentation").canonicalFile;val f=File(requireNotNull(uri.path)).canonicalFile
                uri.takeIf {f.parentFile==root && f.isFile}}
            else->null
        }
    }.getOrNull()
    /** Deletes an uploaded copy, or gives back the read grant for a chosen document so removed items do not use up Android's persisted-grant limit. */
    fun removeUpload(context:Context,item:PresentationItem){runCatching {
        val uri=safeUri(context,item.uri) ?: return
        when(uri.scheme){
            "file"->uri.path?.let {File(it).delete()}
            "content"->context.contentResolver.releasePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }}
}
