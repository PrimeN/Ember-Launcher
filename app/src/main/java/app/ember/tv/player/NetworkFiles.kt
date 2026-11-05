package app.ember.tv.player

import app.ember.tv.data.Net
import app.ember.tv.data.SubtitleFiles
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.*
import com.hierynomus.smbj.*
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.jcraft.jsch.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.commons.net.ftp.*
import java.io.*
import java.time.Duration
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** Short-lived browsing sessions; streams own their connection and close it on seek/cancel. */
internal object NetworkFiles {
    private class Owner:Closeable {
        private val closers=mutableListOf<()->Unit>()
        fun add(close:()->Unit){closers+=close}
        override fun close(){closers.asReversed().forEach {runCatching(it)};closers.clear()}
    }
    private fun <T> owned(block:(Owner)->T):T {val owner=Owner();try{return block(owner)} catch(e:Throwable){owner.close();throw e}}
    private fun smb(a:NetworkAccount,o:Owner):DiskShare {
        val l=NetworkLocation.parse(a.address)
        val client=SMBClient(SmbConfig.builder().withTimeout(20,TimeUnit.SECONDS).withSoTimeout(30_000).withSigningRequired(true).withEncryptData(!a.allowUnencryptedSmb).withAuthenticators(com.hierynomus.smbj.auth.NtlmAuthenticator.Factory()).build());o.add {client.close()}
        val connection=client.connect(l.host,l.port);o.add {connection.close()}
        val session=connection.authenticate(AuthenticationContext(a.username,a.password.toCharArray(),a.domain));o.add {session.close()}
        val share=session.connectShare(l.root.substringBefore('/')) as? DiskShare ?: error("This is not a disk share.");o.add {share.close()};return share
    }
    private fun smbPath(l:NetworkLocation,path:String)=listOf(l.root.substringAfter('/',""),path).filter {it.isNotEmpty()}.joinToString("\\").replace('/','\\')
    private fun sftp(a:NetworkAccount,o:Owner):ChannelSftp {
        val l=NetworkLocation.parse(a.address)
        require(a.fingerprint.startsWith("SHA256:")) {"Enter the server's verified SHA256 host key fingerprint."}
        val jsch=JSch()
        jsch.setHostKeyRepository(object:HostKeyRepository {
            override fun check(host:String,key:ByteArray)=if(NetworkLocation.fingerprintMatches(a.fingerprint,key))HostKeyRepository.OK else HostKeyRepository.CHANGED
            override fun add(hostkey:HostKey,ui:UserInfo?){}
            override fun remove(host:String,type:String?){}
            override fun remove(host:String,type:String?,key:ByteArray?){}
            override fun getKnownHostsRepositoryID()="Ember pinned host key"
            override fun getHostKey()=emptyArray<HostKey>()
            override fun getHostKey(host:String?,type:String?)=emptyArray<HostKey>()
        })
        val session=jsch.getSession(a.username,l.host,l.port);o.add {session.disconnect()}
        session.setPassword(a.password.toByteArray(Charsets.UTF_8));session.setConfig("StrictHostKeyChecking","yes");session.setConfig("PreferredAuthentications","password,keyboard-interactive")
        session.timeout=30_000;session.connect(20_000)
        return (session.openChannel("sftp") as ChannelSftp).also {channel->o.add {channel.disconnect()};channel.connect(20_000)}
    }
    private interface ListingFtp { fun listing(command:FTPCmd,path:String):java.net.Socket? }
    private class ListingPlainFtp:FTPClient(),ListingFtp {override fun listing(command:FTPCmd,path:String)=_openDataConnection_(command,path)}
    private class ListingFtps:FTPSClient(false),ListingFtp {override fun listing(command:FTPCmd,path:String)=_openDataConnection_(command,path)}
    private fun ftp(a:NetworkAccount,o:Owner):FTPClient {
        val l=NetworkLocation.parse(a.address)
        val client=if(l.scheme=="ftps")ListingFtps().apply {isEndpointCheckingEnabled=true;setTrustManager(javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()).apply {init(null as java.security.KeyStore?)}.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().first())} else ListingPlainFtp()
        o.add {client.disconnect()}
        client.setAutodetectUTF8(true);client.connectTimeout=20_000;client.defaultTimeout=30_000;client.setDataTimeout(Duration.ofSeconds(30));client.connect(l.host,l.port)
        check(FTPReply.isPositiveCompletion(client.replyCode)&&client.login(a.username.ifEmpty {"anonymous"},a.password)) {"File server sign-in failed."}
        if(client is FTPSClient){client.execPBSZ(0);client.execPROT("P")}
        client.enterLocalPassiveMode();check(client.setFileType(FTP.BINARY_FILE_TYPE));return client
    }
    private val http=Net.client(20,30).newBuilder().cache(null).followRedirects(false).followSslRedirects(false).build()
    private fun webUrl(l:NetworkLocation,path:String)=HttpUrl.Builder().scheme(l.scheme).host(l.host).port(l.port).addPathSegments(l.full(path)).build()
    private fun web(a:NetworkAccount,path:String,method:String="GET",offset:Long=0):Response {
        val l=NetworkLocation.parse(a.address);var url=webUrl(l,path)
        if(method=="PROPFIND"&&!url.encodedPath.endsWith('/'))url=url.newBuilder().addPathSegment("").build()
        val origin=url
        repeat(6) {
            val request=Request.Builder().url(url).header("Cache-Control","no-store").apply {
                if(a.username.isNotEmpty())header("Authorization",Credentials.basic(a.username,a.password))
                if(method=="PROPFIND") {header("Depth","1");method(method,"<d:propfind xmlns:d=\"DAV:\"><d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>".toRequestBody("application/xml".toMediaType()))}
                else if(offset>0)header("Range","bytes=$offset-")
            }.build()
            val response=http.newCall(request).execute()
            if(response.code in listOf(301,302,303,307,308)) {
                val next=url.resolve(response.header("Location").orEmpty());response.close()
                check(next!=null && next.scheme==origin.scheme&&next.host==origin.host&&next.port==origin.port) {"Configure the server's final WebDAV address; cross-origin redirects are blocked."};url=next
            } else {
                if(!response.isSuccessful){response.close();error("File server request failed.")}
                if(offset>0 && (response.code!=206||response.header("Content-Range")?.startsWith("bytes $offset-")!=true)){response.close();error("This server does not support seeking.")}
                return response
            }
        };error("Too many file server redirects.")
    }
    fun listPage(a:NetworkAccount,path:String,offset:Int=0,check:()->Unit={}):NetworkPage {
        val l=NetworkLocation.parse(a.address)
        val listing=NetworkListing(path,offset,check=check)
        when(l.scheme) {
            "smb" -> owned {o->o.use {
                smb(a,o).openDirectory(smbPath(l,path),EnumSet.of(AccessMask.GENERIC_READ),null,SMB2ShareAccess.ALL,SMB2CreateDisposition.FILE_OPEN,null).use {directory ->
                    val iterator=directory.iterator()
                    while(iterator.hasNext()) {val e=iterator.next();if(!listing.add(NetworkEntry(e.fileName,e.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value!=0L,e.endOfFile)))break}
                }
            }}
            "sftp" -> owned {o->o.use {sftp(a,o).ls("/"+l.full(path),ChannelSftp.LsEntrySelector {e ->
                if(listing.add(NetworkEntry(e.filename,e.attrs.isDir,e.attrs.size)))ChannelSftp.LsEntrySelector.CONTINUE else ChannelSftp.LsEntrySelector.BREAK
            })}}
            "ftp","ftps" -> owned {o->o.use {
                val client=ftp(a,o)
                var parser:FTPFileEntryParser=org.apache.commons.net.ftp.parser.MLSxEntryParser.getInstance()
                var socket=(client as ListingFtp).listing(FTPCmd.MLSD,"/"+l.full(path))
                if(socket==null){
                    // SYST is a control-channel command: resolve the parser before starting LIST.
                    parser=org.apache.commons.net.ftp.parser.DefaultFTPFileEntryParserFactory().createFileEntryParser(client.systemType)
                    socket=(client as ListingFtp).listing(FTPCmd.LIST,"/"+l.full(path))
                }
                checkNotNull(socket) {"Directory listing unavailable."}
                socket.use {data -> data.getInputStream().bufferedReader(java.nio.charset.Charset.forName(client.controlEncoding)).use {reader ->
                    while(true) {
                        check()
                        val line=StringBuilder();var eof=false
                        while(true){val c=reader.read();if(c<0){eof=true;break};if(c==10)break;require(line.length<16*1024){"Directory entry is too long."};if(c!=13)line.append(c.toChar())}
                        if(line.isEmpty()){if(eof)break else continue}
                        val e=parser.parseFTPEntry(line.toString())?:continue
                        if(!listing.add(NetworkEntry(e.name,e.isDirectory,e.size)))break
                    }
                }}
                // The data socket can be closed early after the lookahead. The owner closes
                // the control session; do not wait for a transfer-complete reply on cancellation.
            }}
            else -> web(a,path,"PROPFIND").use {response -> WebDavListing.parse(response.body.charStream(),response.request.url,listing)}
        }
        return listing.result()
    }
    fun resource(a:NetworkAccount,path:String,size:Long):RemoteMedia=object:RemoteMedia {
        override val length=size
        override fun open(offset:Long):InputStream=owned {o->
            val l=NetworkLocation.parse(a.address)
            val stream:InputStream=when(l.scheme) {
                "smb"->{val share=smb(a,o);val file=share.openFile(smbPath(l,path),EnumSet.of(AccessMask.GENERIC_READ),null,SMB2ShareAccess.ALL,SMB2CreateDisposition.FILE_OPEN,null);o.add {file.close()}
                    object:InputStream(){var at=offset;override fun read():Int {val b=ByteArray(1);return if(read(b,0,1)<0)-1 else b[0].toInt() and 255}
                        override fun read(b:ByteArray,off:Int,len:Int):Int {if(len==0)return 0;if(at>=length)return -1;val n=file.read(b,at,off,minOf(len.toLong(),length-at).toInt());if(n>0)at+=n;return n}}}
                "sftp"->sftp(a,o).get("/"+l.full(path),null,offset)
                "ftp","ftps"->{val client=ftp(a,o);client.restartOffset=offset;client.retrieveFileStream("/"+l.full(path))?:error("File unavailable.")}
                else->{val response=web(a,path,offset=offset);o.add {response.close()};response.body.byteStream()}
            }
            object:FilterInputStream(stream) {override fun close(){try{super.close()}finally{o.close()}}}
        }
    }
}
