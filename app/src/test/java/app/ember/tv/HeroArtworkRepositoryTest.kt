package app.ember.tv

import app.ember.tv.data.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class HeroArtworkRepositoryTest {
    private val movie = Meta(id="tt1",type="movie",name="Example",tmdbId=42)
    private val images = Json.parseToJsonElement("""{"images":{"backdrops":[{"file_path":"/a.jpg","width":1920,"height":1080}],"logos":[{"file_path":"/a.png","width":800,"height":200,"iso_639_1":"en"}]},"external_ids":{"imdb_id":"tt1","tvdb_id":999}}""")

    @Test fun absentKeysAndUnidentifiedCatalogTitlesDoNotStartRequests() = runBlocking {
        var calls=0
        val repo=HeroArtworkRepository(null,{calls++;images})
        assertTrue(repo.resolve(movie,"en","","").empty)
        assertTrue(repo.resolve(Meta(type="movie",name="Example"),"en","dummy","").empty)
        assertEquals(0,calls)
    }

    @Test fun imdbLookupUsesTypedResultsAndEncodesKeys() = runBlocking {
        val visited=mutableListOf<String>()
        val repo=HeroArtworkRepository(null,{url-> synchronized(visited){visited+=url}
            if(url.toHttpUrl().encodedPath.endsWith("find/tt1")) Json.parseToJsonElement("""{"movie_results":[{"id":42}],"tv_results":[{"id":7}]}""") else images })
        val result=repo.resolve(movie.copy(tmdbId=null),"fr-CA","dummy &?=","")
        assertFalse(result.empty);assertEquals(2,visited.size)
        assertTrue(visited[1].toHttpUrl().encodedPath.endsWith("/movie/42"))
        assertEquals("dummy &?=",visited[1].toHttpUrl().queryParameter("api_key"))
        assertEquals("fr,en,null",visited[1].toHttpUrl().queryParameter("include_image_language"))
    }

    @Test fun fanartTvUsesTvdbIdAndMovieUsesImdbId() = runBlocking {
        val visited=java.util.Collections.synchronizedList(mutableListOf<String>())
        val repo=HeroArtworkRepository(null,{url->visited+=url
            if(url.toHttpUrl().host=="webservice.fanart.tv") Json.parseToJsonElement("{}") else images })
        repo.resolve(movie,"en","dummy","fanart-dummy")
        repo.resolve(movie.copy(type="series"),"en","dummy","fanart-dummy")
        val paths=visited.map{it.toHttpUrl().encodedPath}
        assertTrue("/v3/movies/tt1" in paths);assertTrue("/v3/tv/999" in paths);assertFalse("/v3/tv/42" in paths)
    }

    @Test fun concurrentReadersShareASuccessfulLoad() = runBlocking {
        withTimeout(5000) {
            val calls=AtomicInteger();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val repo=HeroArtworkRepository(null,{calls.incrementAndGet();entered.complete(Unit);release.await();images})
            val a=async{repo.resolve(movie,"en","dummy","")};entered.await()
            val b=async{repo.resolve(movie,"en","dummy","")};yield();release.complete(Unit)
            assertEquals(a.await(),b.await());assertEquals(1,calls.get())
        }
    }

    @Test fun diskCacheSurvivesRepositoryRecreationWithoutWritingKeys() = runBlocking {
        val dir=Files.createTempDirectory("hero-art").toFile()
        try {
            val calls=AtomicInteger();val fetch:suspend(String)->kotlinx.serialization.json.JsonElement={calls.incrementAndGet();images}
            val a=HeroArtworkRepository(dir,fetch).resolve(movie,"en","secret-dummy-tmdb","")
            val b=HeroArtworkRepository(dir,fetch).resolve(movie,"en","secret-dummy-tmdb","")
            assertEquals(a,b);assertEquals(1,calls.get())
            assertTrue(dir.listFiles()!!.all{it.name.matches(Regex("[a-f0-9]{64}\\.json"))})
            assertFalse(dir.listFiles()!!.joinToString{it.readText()}.contains("secret-dummy"))
        } finally {dir.deleteRecursively()}
    }

    @Test fun localeOrCredentialChangeInvalidatesPublicArtworkCache() = runBlocking {
        val calls=AtomicInteger();val repo=HeroArtworkRepository(null,{calls.incrementAndGet();images})
        repo.resolve(movie,"en","one","");repo.resolve(movie,"en","two","");repo.resolve(movie,"fr","two","")
        assertEquals(3,calls.get())
    }

    @Test fun providerFailuresAreShortCachedAndRecoverWithoutErasingWorkingArtwork() = runBlocking {
        var clock=1000L;val fanartCalls=AtomicInteger();var working=false
        val repo=HeroArtworkRepository(null,{url->
            if(url.toHttpUrl().host=="webservice.fanart.tv") {
                fanartCalls.incrementAndGet();if(!working)throw IOException("offline")
                Json.parseToJsonElement("""{"hdmovielogo":[{"url":"https://assets.fanart.tv/logo.png","lang":"en"}]}""")
            } else images
        },{clock})
        assertFalse(repo.resolve(movie,"en","dummy","fanart").empty)
        working=true;repo.resolve(movie,"en","dummy","fanart");assertEquals(1,fanartCalls.get())
        clock+=60_001
        assertEquals("https://assets.fanart.tv/logo.png",repo.resolve(movie,"en","dummy","fanart").logo)
        assertEquals(2,fanartCalls.get())
    }

    @Test fun emptySuccessfulImagesAreCachedAndExpire() = runBlocking {
        var clock=1000L;val calls=AtomicInteger()
        val repo=HeroArtworkRepository(null,{calls.incrementAndGet();Json.parseToJsonElement("{}")},{clock})
        repo.resolve(movie,"en","dummy","");repo.resolve(movie,"en","dummy","");assertEquals(1,calls.get())
        clock+=3_600_001;repo.resolve(movie,"en","dummy","");assertEquals(2,calls.get())
    }

    @Test fun corruptedCacheAndClockRollbackFallBackToFreshLookupAndDiskIsBounded() = runBlocking {
        val dir=Files.createTempDirectory("hero-bounded").toFile()
        try {
            var clock=10_000L;val calls=AtomicInteger()
            val fetch:suspend(String)->kotlinx.serialization.json.JsonElement={calls.incrementAndGet();images}
            HeroArtworkRepository(dir,fetch,{clock},2).resolve(movie,"en","dummy","")
            dir.listFiles()!!.forEach{it.writeText("broken")}
            HeroArtworkRepository(dir,fetch,{clock},2).resolve(movie,"en","dummy","");assertEquals(2,calls.get())
            clock=9000
            val repo=HeroArtworkRepository(dir,fetch,{clock},2)
            repo.resolve(movie,"en","dummy","");assertEquals(3,calls.get())
            repo.resolve(movie.copy(tmdbId=43),"en","dummy","");repo.resolve(movie.copy(tmdbId=44),"en","dummy","")
            assertTrue(dir.listFiles()!!.count{it.extension=="json"}<=2)
            assertTrue(dir.listFiles()!!.none{it.extension=="tmp"})
        } finally {dir.deleteRecursively()}
    }

    @Test fun cancelledLookupDoesNotPublishLateOrPersistPartialData() = runBlocking {
        withTimeout(5000) {
            val dir=Files.createTempDirectory("hero-cancel").toFile()
            try {
                val gate=CompletableDeferred<Unit>();val started=CompletableDeferred<Unit>();val calls=AtomicInteger()
                val repo=HeroArtworkRepository(dir,{if(calls.incrementAndGet()==1){started.complete(Unit);withContext(NonCancellable){gate.await()}};images})
                val first=async{repo.resolve(movie,"en","dummy","")};started.await();first.cancel();gate.complete(Unit);first.join()
                assertTrue(first.isCancelled);assertTrue(dir.listFiles().orEmpty().isEmpty())
                assertFalse(repo.resolve(movie,"en","dummy","").empty);assertEquals(2,calls.get())
            } finally {dir.deleteRecursively()}
        }
    }
}
