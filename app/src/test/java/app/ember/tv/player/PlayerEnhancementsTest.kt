package app.ember.tv.player

import app.ember.tv.data.SubtitleFiles
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.URI
import java.net.HttpURLConnection
import com.sun.net.httpserver.HttpServer

class PlayerEnhancementsTest {
    @Test fun engineRecoveryDoesNotMaskAuthenticationNetworkOrDrmErrors() {
        for(code in listOf(3001,3004,4001,4007,5001,5004))assertTrue(PlaybackEnginePolicy.compatibilityError(code))
        for(code in listOf(1000,2001,2002,2004,6001,6002,6006))assertFalse(PlaybackEnginePolicy.compatibilityError(code))
        assertTrue(PlaybackEnginePolicy.nativeFirst("https://example.org/movie.WMV?token=private"))
        assertFalse(PlaybackEnginePolicy.nativeFirst("https://example.org/movie.mp4?name=file.wmv"))
        assertFalse(PlaybackEnginePolicy.nativeFirst("%%%"))
    }
    @Test fun rangesSupportOpenSuffixAndBoundedReads() {
        assertEquals(ByteRange(0,99),ByteRange.parse(null,100))
        assertEquals(ByteRange(10,29),ByteRange.parse("bytes=10-29",100))
        assertEquals(ByteRange(80,99),ByteRange.parse("bytes=80-",100))
        assertEquals(ByteRange(90,99),ByteRange.parse("bytes=-10",100))
        assertEquals(ByteRange(0,99),ByteRange.parse("bytes=-1000",100))
        assertEquals(ByteRange(90,99),ByteRange.parse("bytes=90-999",100))
    }
    @Test fun invalidRangesCannotOverflowOrReadOutsideTheFile() {
        for(value in listOf("bytes=100-","bytes=20-10","bytes=-0","bytes=-","items=0-1","bytes=1-2,4-5","bytes=9999999999999999999999999-"))assertNull(value,ByteRange.parse(value,100))
        assertNull(ByteRange.parse("bytes=0-",0))
    }
    @Test fun queueMovesPreserveCurrentIdentityEvenWithDuplicates() {
        val same=QueueMedia("content://a","A")
        val q=MediaQueue(listOf(same,QueueMedia("content://b","B"),same))
        q.select(2);q.move(0,2);assertEquals(1,q.index);q.move(1,0);assertEquals(0,q.index)
        assertFalse(q.remove(0));assertTrue(q.remove(2));assertEquals(0,q.index)
    }
    @Test fun queueRepeatModesAndBoundsAreDeterministic() {
        val q=MediaQueue(listOf(QueueMedia("a","A"),QueueMedia("b","B")))
        assertEquals("B",q.next()?.title);assertNull(q.next(ended=true))
        q.repeat="one";assertEquals("B",q.next(ended=true)?.title)
        q.repeat="all";assertEquals("A",q.next(ended=true)?.title)
        assertNull(q.select(-1));assertEquals("A",q.current?.title)
        q.add((1..300).map {QueueMedia("$it","$it")});assertEquals(200,q.items.size)
    }
    @Test fun queuePositionKeysDoNotContainAddressesOrCredentials() {
        val key=QueueMedia("https://example.org/private?token=secret","Title").key
        assertTrue(key.startsWith("file:"));assertFalse(key.contains("secret"));assertFalse(key.contains("example"))
    }
    @Test fun repeatRangeRejectsInvalidEndAndClearsPerItem() {
        val r=RepeatRange();assertFalse(r.markEnd(5000));r.markStart(10000)
        assertFalse(r.markEnd(9000));assertFalse(r.markEnd(10500));assertTrue(r.markEnd(20000))
        assertNull(r.destination(19999));assertEquals(10000L,r.destination(20000));r.clear();assertNull(r.destination(30000))
    }
    @Test fun networkLocationsKeepPasswordsSeparateAndBoundPaths() {
        val smb=NetworkLocation.parse("smb://server/Movies/Drama");assertEquals(445,smb.port);assertEquals("Movies/Drama/Film.mkv",smb.full("Film.mkv"))
        assertEquals(2222,NetworkLocation.parse("sftp://server:2222/media").port)
        for(value in listOf("smb://server","sftp://user:password@server/media","https://server/media?token=x","https://server/../private","ftp://server:99999/folder"))assertTrue(value,runCatching {NetworkLocation.parse(value)}.isFailure)
        for(name in listOf("..","../secret","a\\b","bad\r\nname"))assertTrue(runCatching {NetworkLocation.child("",name)}.isFailure)
        assertFalse(NetworkAccount(label="Title",address="sftp://server",password="secret").toString().contains("secret"))
    }
    @Test fun sftpPinRejectsUnknownAndChangedHostKeys() {
        val key="host-public-key".toByteArray()
        val pin="SHA256:"+java.util.Base64.getEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(key))
        assertTrue(NetworkLocation.fingerprintMatches(pin,key));assertFalse(NetworkLocation.fingerprintMatches(pin,"another-key".toByteArray()))
        assertFalse(NetworkLocation.fingerprintMatches("",key));assertFalse(NetworkLocation.fingerprintMatches("SHA256:invalid!",key))
    }
    private fun connection(url:String,range:String?=null,method:String="GET")=(URI(url).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout=2000;readTimeout=2000;requestMethod=method;if(range!=null)setRequestProperty("Range",range)
    }
    @Test(timeout=5000) fun privateRelayServesExactRangesAndHeadWithoutOpeningTheFile() {
        val opens=java.util.concurrent.atomic.AtomicInteger();val closes=java.util.concurrent.atomic.AtomicInteger()
        val closed=java.util.concurrent.CountDownLatch(1);val bytes=ByteArray(100){it.toByte()}
        MediaRelay().use {relay->
            val url=relay.register(object:RemoteMedia {override val length=100L;override fun open(offset:Long):java.io.InputStream {opens.incrementAndGet();return object:java.io.ByteArrayInputStream(bytes,offset.toInt(),bytes.size-offset.toInt()){override fun close(){closes.incrementAndGet();super.close();closed.countDown()}}}},"movie.mkv")
            connection(url,"bytes=10-19").let {c->try {assertEquals(206,c.responseCode);assertEquals("bytes 10-19/100",c.getHeaderField("Content-Range"));assertArrayEquals(bytes.copyOfRange(10,20),c.inputStream.readBytes())}finally{c.disconnect()}}
            connection(url,method="HEAD").let {c->try {assertEquals(200,c.responseCode);assertEquals(100L,c.contentLengthLong)}finally{c.disconnect()}}
            // Receiving the last response byte can race the server thread's stream cleanup.
            assertTrue(closed.await(2,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1,opens.get());assertEquals(1,closes.get())
            connection(url,"bytes=100-").let {c->try{assertEquals(416,c.responseCode)}finally{c.disconnect()}}
            connection(url,method="POST").let {c->try{assertEquals(405,c.responseCode)}finally{c.disconnect()}}
            val u=URI(url);connection("http://${u.host}:${u.port}/wrong-token/movie.mkv").let {c->try{assertEquals(404,c.responseCode)}finally{c.disconnect()}}
        }
    }
    @Test(timeout=6000) fun closingAFileSessionNeverWaitsForTheRemoteProtocolOnTheCallerThread() {
        val reading=java.util.concurrent.CountDownLatch(1);val closing=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1);val endRead=java.util.concurrent.CountDownLatch(1)
        val relay=MediaRelay()
        val url=relay.register(object:RemoteMedia {
            override val length=100L
            override fun open(offset:Long)=object:java.io.InputStream() {
                override fun read():Int {reading.countDown();endRead.await(3,java.util.concurrent.TimeUnit.SECONDS);return -1}
                override fun close(){closing.countDown();release.await(3,java.util.concurrent.TimeUnit.SECONDS);endRead.countDown()}
            }
        },"movie.mkv")
        val client=Thread {runCatching {val c=connection(url);try {c.inputStream.readBytes()}finally{c.disconnect()}}}.apply {isDaemon=true;start()}
        try {
            assertTrue(reading.await(2,java.util.concurrent.TimeUnit.SECONDS))
            val at=System.nanoTime();relay.close()
            assertTrue("Network cleanup blocked its caller",(System.nanoTime()-at)/1_000_000<1000)
            assertTrue(closing.await(1,java.util.concurrent.TimeUnit.SECONDS))
        }finally {release.countDown();endRead.countDown();relay.close();client.join(1000)}
    }
    @Test fun adaptiveManifestsRetainTemplatesAndHideOriginalOrigins() {
        NativeHttpTransport(emptyMap(),listOf("https://example.org/movie.mpd")).use {transport->
            val hls=transport.hls("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin?token=x\"\nsegment.ts", "https://example.org/folder/list.m3u8".toHttpUrl())
            assertFalse(hls.contains("example.org"));assertTrue(hls.contains("127.0.0.1:"));assertTrue(hls.contains("/folder/key.bin?token=x"));assertTrue(hls.contains("/folder/segment.ts"))
            val mpd=transport.dash("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><BaseURL>assets/</BaseURL><Period><AdaptationSet><SegmentTemplate media="part-${'$'}Number${'$'}.m4s" initialization="init.mp4"/></AdaptationSet></Period></MPD>""","https://example.org/video/movie.mpd".toHttpUrl())
            assertFalse(mpd.contains("example.org"));assertTrue(mpd.contains("part-${'$'}Number${'$'}.m4s"));assertTrue(mpd.contains("/video/assets/init.mp4"))
        }
    }
    @Test fun manifestExternalEntitiesAndFileReferencesAreRejected() {
        NativeHttpTransport(emptyMap(),emptyList()).use {t->
            assertTrue(runCatching {t.dash("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///private'>]><MPD>&e;</MPD>","https://example.org/movie.mpd".toHttpUrl())}.isFailure)
            assertTrue(runCatching {t.hls("#EXTM3U\nfile:///private", "https://example.org/movie.m3u8".toHttpUrl())}.isFailure)
        }
    }
    @Test(timeout=10000) fun nativeHttpRedirectsStripCredentialsAndPreserveRangeSeeking() {
        val address=InetSocketAddress(InetAddress.getByName("127.0.0.1"),0)
        val first=HttpServer.create(address,0);val second=HttpServer.create(address,0)
        val seen=java.util.concurrent.CopyOnWriteArrayList<String?>();val body="0123456789".toByteArray()
        second.createContext("/movie.mkv") {e->seen+=e.requestHeaders.getFirst("Authorization")
            e.responseHeaders.add("Content-Length","10")
            if(e.requestMethod=="HEAD")e.sendResponseHeaders(200,-1)
            else {val offset=e.requestHeaders.getFirst("Range")?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull()?:0
                if(offset>0)e.responseHeaders.add("Content-Range","bytes $offset-9/10")
                e.sendResponseHeaders(if(offset>0)206 else 200,(10-offset).toLong());e.responseBody.use {it.write(body,offset,10-offset)}};e.close()
        }
        first.createContext("/movie.mkv") {e->e.responseHeaders.add("Location","http://127.0.0.1:${second.address.port}/movie.mkv");e.sendResponseHeaders(302,-1);e.close()}
        first.start();second.start()
        try {val origin="http://127.0.0.1:${first.address.port}/movie.mkv"
            NativeHttpTransport(mapOf("Authorization" to "Bearer unit-test"),listOf(origin)).use {t->
                connection(t.expose(origin),"bytes=5-9").let {c->try {assertEquals(206,c.responseCode);assertEquals("56789",c.inputStream.bufferedReader().readText())}finally{c.disconnect()}}
            }
            assertTrue(seen.isNotEmpty());assertTrue(seen.all {it==null})
        }finally{first.stop(0);second.stop(0)}
    }
    @Test fun nativeFontConfigUsesOnlyExplicitSystemRootsAndPrivateCache() {
        val document=SafePlayerXml.parse(NativePlayerFiles.config(listOf("/system/fonts","/product/fonts"),"/private/cache & fonts"))
        assertEquals("/system/fonts",document.getElementsByTagName("dir").item(0).textContent)
        assertEquals("/private/cache & fonts",document.getElementsByTagName("cachedir").item(0).textContent)
    }
    @Test fun mediaXmlRejectsDeepOrExternalInputOnAllXmlImplementations() {
        assertEquals("MPD",SafePlayerXml.parse("<MPD><Period/></MPD>").documentElement.tagName)
        assertTrue(runCatching {SafePlayerXml.parse("<x>".repeat(65)+"</x>".repeat(65))}.isFailure)
        assertTrue(runCatching {SafePlayerXml.parse("<!DOCTYPE MPD SYSTEM 'https://private.example'><MPD/>")}.isFailure)
    }
    @Test fun subtitleTimingHasTheSameMeaningAcrossBothEngines() {
        val rate=1.042f;val offset=1500L;val cue=60.0
        val standardVideo=(cue+offset/1000.0)/rate
        val nativeVideo=cue*SubtitleTiming.nativeSpeed(rate)+SubtitleTiming.nativeDelay(offset,rate)
        assertEquals(standardVideo,nativeVideo,0.000001)
        assertEquals(standardVideo-30.0,cue*SubtitleTiming.nativeSpeed(rate)+SubtitleTiming.nativeDelay(offset,rate,30000),0.000001)
        assertEquals(1f,SubtitleTiming.rate(Float.NaN));assertEquals(0.1f,SubtitleTiming.rate(-1f))
    }
    @Test fun archiveLanguageAliasesBeatAnExactReleaseInAnotherLanguage() {
        val output=java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(output).use {z->for(name in listOf("Show.S01E02.Release.eng.srt","Show.S01E02.Other.fre.srt")){z.putNextEntry(java.util.zip.ZipEntry(name));z.write("subtitle".toByteArray());z.closeEntry()}}
        val selected=SubtitleFiles.archive(output.toByteArray(),SubtitleFiles.Target("Show.S01E02.Release",1,2,"fr"))
        assertEquals("Show.S01E02.Other.fre.srt",selected?.first)
    }
    @Test fun malformedMicroDvdFrameNumbersDoNotCrashSubtitleLoading() {
        assertNull(SubtitleFiles.microDvd("{0}{0}25\n{9999999999999999999999999}{10000000000000000000000000}Text",25f))
    }
}
