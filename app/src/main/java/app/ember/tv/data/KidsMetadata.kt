package app.ember.tv.data

import app.ember.tv.tracker.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*

internal fun addonAgeRating(meta:JsonElement?):String? = listOf("ageRating","certification","contentRating","mpaa","officialRating")
    .mapNotNull {meta.str(it)?.takeIf {it.isNotBlank()}}.let {values->
        values.filter {KidsContentPolicy.ratingRank(it)!=null}.maxByOrNull {KidsContentPolicy.ratingRank(it)!!} ?: values.firstOrNull()
    }

/** Only active locks request missing public metadata. Unknown items never pass a lock. */
internal class KidsMetadata(
    private val credential:()->String={ApiKeys.get("TMDB_API_KEY")},
    private val fetch:suspend(String)->JsonElement={Http.get(it)},
) {
    internal var nowMillis:()->Long={android.os.SystemClock.elapsedRealtime()}
    private data class Entry(val at:Long,val genres:List<String>?,val rating:String?,val adult:Boolean?)
    private val cache=object:LinkedHashMap<String,Entry>(128,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Entry>?)=size>1024
    }
    private val permits=Semaphore(2)
    private fun key(meta:Meta):String? {
        val id=HeroIdentity.of(meta) ?: return null
        val public=id.tmdb?.let {"t:$it"} ?: id.imdb?.let {"i:$it"} ?: return null
        return "${if(meta.type=="movie")"movie" else "tv"}:$public"
    }
    private fun cached(meta:Meta):Meta {
        val k=key(meta) ?: return meta
        val e=synchronized(cache){cache[k]}?.takeIf {runtimeFresh(it.at, nowMillis(), 30*60_000L)} ?: return meta
        return meta.copy(genres=meta.genres?.takeIf {it.isNotEmpty()} ?: e.genres,ageRating=meta.ageRating?.takeIf {KidsContentPolicy.ratingRank(it)!=null} ?: e.rating,
            adult=if(meta.adult==true || e.adult==true)true else meta.adult ?: e.adult)
    }
    suspend fun filter(profile:Profile,items:List<Meta>):List<Meta> {
        if(!KidsContentPolicy.restricted(profile))return items
        val base=items.map(::cached)
        // Do not hold Home/Search behind an unbounded number of metadata calls.
        withTimeoutOrNull(5_000L) {
            coroutineScope {
                base.filter {KidsContentPolicy.decision(profile,it)==KidsContentPolicy.Decision.NEEDS_METADATA}
                    .distinctBy {key(it) ?: it.mediaKey()}.take(40).map {meta->async {
                        val k=key(meta) ?: return@async
                        val recent=synchronized(cache){cache[k]}?.takeIf {runtimeFresh(it.at, nowMillis(), 60_000L)}
                        if(recent==null)permits.withPermit {
                            if(synchronized(cache){cache[k]}?.let {runtimeFresh(it.at, nowMillis(), 60_000L)}!=true)
                                withTimeoutOrNull(1_800L){lookup(meta)}
                        }
                    }}.awaitAll()
            }
        }
        currentCoroutineContext().ensureActive()
        return base.map(::cached).filter {KidsContentPolicy.allows(profile,it)}
    }
    internal suspend fun lookup(meta:Meta):Meta {
        val k=key(meta) ?: return meta
        val token=credential().takeIf {it.isNotBlank()} ?: return meta
        val id=HeroIdentity.of(meta) ?: return meta
        val type=if(meta.type=="movie")"movie" else "tv"
        val api=app.ember.tv.discover.ExploreRepository.enc(token)
        val value=try {
            val tmdb=id.tmdb ?: id.imdb?.let {imdb->
                fetch("https://api.themoviedb.org/3/find/$imdb?api_key=$api&external_source=imdb_id")
                    .arr(if(type=="movie")"movie_results" else "tv_results").list().firstOrNull().long("id")
            }
            tmdb?.let {fetch("https://api.themoviedb.org/3/$type/$it?api_key=$api&append_to_response=${if(type=="movie")"release_dates" else "content_ratings"}")}
        } catch(e:CancellationException){throw e} catch(_:Exception){null}
        val genres=value.arr("genres").list().mapNotNull {it.str("name")}.takeIf {it.isNotEmpty()}
        val certifications=if(type=="movie")value.obj("release_dates").arr("results").list().filter {it.str("iso_3166_1")=="US"}
            .flatMap {it.arr("release_dates").list()}.mapNotNull {it.str("certification")}
        else value.obj("content_ratings").arr("results").list().filter {it.str("iso_3166_1")=="US"}.mapNotNull {it.str("rating")}
        val rating=certifications.filter {KidsContentPolicy.ratingRank(it)!=null}.maxByOrNull {KidsContentPolicy.ratingRank(it)!!}
        val adult=((value as? JsonObject)?.get("adult") as? JsonPrimitive)?.booleanOrNull
        synchronized(cache){cache[k]=Entry(nowMillis(),genres,rating,adult)}
        return cached(meta)
    }
}
