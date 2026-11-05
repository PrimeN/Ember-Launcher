package app.ember.tv

import app.ember.tv.data.Person
import app.ember.tv.discover.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.*

class PersonFilmographyTest {
    private val data="""{"cast":[
        {"id":1,"media_type":"movie","title":"Film","release_date":"2022-03-01","popularity":3,"poster_path":"/film.jpg"},
        {"id":1,"media_type":"tv","name":"Show","first_air_date":"2024-02-01","popularity":5},
        {"id":1,"media_type":"movie","title":"Duplicate","release_date":"2022-03-01","popularity":2},
        {"id":2,"media_type":"movie","title":"Adult","adult":true,"popularity":100},
        {"id":3,"media_type":"person","name":"Not media"}],
        "crew":[{"id":4,"media_type":"movie","title":"Directed","job":"Director"}]}"""
    @Test fun creditListUsesStableMediaIdentityAndCachesTheResponseAcrossRoles()=runBlocking {
        var calls=0
        val repo=ExploreRepository(key={"fixture"},get={url,_->
            calls++;assertTrue(url.contains("person/42/combined_credits"));Json.parseToJsonElement(data)
        })
        val ref=ExploreRef(42,"Actor")
        val cast=repo.filmography(ref,false)
        assertEquals(listOf("Show","Film"),cast.map {it.name})
        assertEquals(listOf("series","movie"),cast.map {it.type})
        assertTrue(cast.all {it.tmdbId==1L && it.adult==false})
        assertEquals("https://image.tmdb.org/t/p/w342/film.jpg",cast.last().poster)
        assertEquals(listOf("Directed"),repo.filmography(ref,true).map {it.name})
        assertEquals(1,calls)
    }
    @Test fun castAndCrewUseNewestReleaseDateBeforePopularityAndPutUnknownDatesLast()=runBlocking {
        val credits="""[
            {"id":1,"media_type":"movie","title":"Older popular film","release_date":"2020-01-01","popularity":999},
            {"id":2,"media_type":"tv","name":"New show","first_air_date":"2025-01-02","popularity":1},
            {"id":3,"media_type":"movie","title":"Same-year film","release_date":"2025-01-01","popularity":500},
            {"id":4,"media_type":"movie","title":"Undated","release_date":"","popularity":200},
            {"id":5,"media_type":"movie","title":"Invalid date","release_date":"unknown","popularity":100}]
        """
        val repo=ExploreRepository(key={"fixture"},get={_,_->Json.parseToJsonElement("{\"cast\":$credits,\"crew\":$credits}")})
        for(crew in listOf(false,true)) {
            val names=repo.filmography(ExploreRef(42,"Person"),crew).map {it.name}
            assertEquals(listOf("New show","Same-year film","Older popular film","Undated","Invalid date"),names)
        }
    }
    @Test fun knownPersonIdWinsButAmbiguousOrFuzzyNamesNeedConfirmation() {
        val people=listOf(ExploreRef(1,"Sam"),ExploreRef(2,"Sam"))
        assertNull(ExploreRepository.personIdentity(Person("Sam",null),people))
        assertNull(ExploreRepository.personIdentity(Person("Sam",null),listOf(ExploreRef(3,"Samuel"))))
        assertEquals(1L,ExploreRepository.personIdentity(Person("sam",null),people.take(1))?.id)
        assertEquals(99L,ExploreRepository.personIdentity(Person("Sam",null,tmdbId=99),people)?.id)
    }
    @Test fun missingKeyDoesNotMakeNetworkRequests()=runBlocking {
        var calls=0
        val repo=ExploreRepository(key={""},get={_,_->calls++;error("Unexpected request")})
        assertTrue(runCatching {repo.filmography(ExploreRef(1,"Actor"),false)}.isFailure)
        assertEquals(0,calls)
    }
    @Test fun failedResponseIsNotCachedAndCanBeRetried()=runBlocking {
        var calls=0
        val repo=ExploreRepository(key={"fixture"},get={_,_->
            calls++;Json.parseToJsonElement(if(calls==1)"{\"success\":false}" else data)
        })
        assertTrue(runCatching {repo.filmography(ExploreRef(1,"Actor"),false)}.isFailure)
        assertEquals(2,repo.filmography(ExploreRef(1,"Actor"),false).size)
        assertEquals(2,calls)
    }
    @Test fun creditsAreBoundedEvenWhenAPersonHasAHugeFilmography()=runBlocking {
        val huge=(1..140).joinToString(",") {"{\"id\":$it,\"media_type\":\"movie\",\"title\":\"Film $it\"}"}
        val repo=ExploreRepository(key={"fixture"},get={_,_->Json.parseToJsonElement("{\"cast\":[$huge],\"crew\":[]}")})
        assertEquals(100,repo.filmography(ExploreRef(1,"Actor"),false).size)
    }
}
