package app.ember.tv.player

import kotlinx.serialization.Serializable
import java.net.URI

/** Passwords are never part of a URL; the entire account record is encrypted at rest. */
@Serializable
internal data class NetworkAccount(
    val id:String=java.util.UUID.randomUUID().toString(),val label:String,val address:String,
    val username:String="",val password:String="",val domain:String="",val fingerprint:String="",
    /** SMB only: allow shares that cannot encrypt (older SMB2 NAS devices). Signing stays required. */
    val allowUnencryptedSmb:Boolean=false,
) {
    override fun toString()="NetworkAccount(<redacted>)"
}
internal data class NetworkLocation(val scheme:String,val host:String,val port:Int,val root:String) {
    companion object {
        fun parse(address:String):NetworkLocation {
            val uri=URI(address.trim())
            require(uri.scheme in setOf("smb","sftp","ftp","ftps","https","http")&&uri.host!=null) {"Use smb://, sftp://, ftps://, ftp://, or a WebDAV https:// address."}
            require(uri.rawUserInfo==null && uri.rawQuery==null && uri.rawFragment==null) {"Enter username and password separately, without query parameters."}
            require(uri.port==-1 || uri.port in 1..65535) {"Invalid network port."}
            val root=uri.path.orEmpty().trim('/')
            require(root.split('/').none {it==".."||it=="."||it.contains('\\')||it.any {c->c.isISOControl()}}) {"Invalid folder path."}
            require(uri.scheme!="smb"||root.isNotEmpty()) {"Include the SMB share name, for example smb://server/Movies."}
            return NetworkLocation(uri.scheme,uri.host,if(uri.port>0)uri.port else when(uri.scheme){"smb"->445;"sftp"->22;"https"->443;"http"->80;else->21},root)
        }
        fun child(path:String,name:String):String {
            require(name !in setOf(".","..")&&name.isNotEmpty()&&name.none {it=='/'||it=='\\'||it.isISOControl()})
            return listOf(path.trim('/'),name).filter {it.isNotEmpty()}.joinToString("/")
        }
        fun fingerprintMatches(expected:String,key:ByteArray):Boolean {
            if(!expected.startsWith("SHA256:"))return false
            val actual=java.security.MessageDigest.getInstance("SHA-256").digest(key)
            val supplied=runCatching {java.util.Base64.getDecoder().decode(expected.removePrefix("SHA256:"))}.getOrNull()?:return false
            return java.security.MessageDigest.isEqual(actual,supplied)
        }
    }
    fun full(path:String):String=listOf(root,path.trim('/')).filter {it.isNotEmpty()}.joinToString("/")
}
internal data class NetworkEntry(val name:String,val directory:Boolean,val size:Long)
