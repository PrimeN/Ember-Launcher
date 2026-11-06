package app.ember.tv.tracker

import app.ember.tv.data.ApiKeys

data class TmdbInfo(val title: String, val year: Int?, val poster: String?, val overview: String?)

/** Looks up titles and posters. Trackers rarely send artwork, so this fills the gaps. */
object Tmdb {
    private val cache = object : LinkedHashMap<String, Pair<Long,TmdbInfo>>(16,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,Pair<Long,TmdbInfo>>?) = size > 128
    }

    private val key get() = ApiKeys.get("TMDB_API_KEY")
    val available: Boolean get() = key.isNotBlank()

    suspend fun lookup(kind: Kind, id: Long): TmdbInfo? = lookupUsing(kind, id, key) { Http.metadata(it) }

    internal suspend fun lookupUsing(kind: Kind, id: Long, credential: String,
        fetch: suspend (String) -> kotlinx.serialization.json.JsonElement): TmdbInfo? {
        if (credential.isBlank()) return null
        val type = if (kind == Kind.MOVIE) "movie" else "tv"
        val cacheKey = "$type:$id:${app.ember.tv.data.CachePolicy.digest(credential)}:${app.ember.tv.data.CachePolicy.epoch(app.ember.tv.data.CacheKind.METADATA)}"
        synchronized(cache) { cache[cacheKey]?.takeIf { app.ember.tv.data.CachePolicy.fresh(app.ember.tv.data.CacheKind.METADATA,it.first) }?.let { return it.second } }
        val info = runCatching {
            val j = fetch("https://api.themoviedb.org/3/$type/$id?api_key=${app.ember.tv.discover.ExploreRepository.enc(credential)}")
            val date = j.str("release_date") ?: j.str("first_air_date")
            TmdbInfo(
                title = j.str("title") ?: j.str("name") ?: return@runCatching null,
                year = date?.take(4)?.toIntOrNull(),
                poster = j.str("poster_path")?.let { "https://image.tmdb.org/t/p/w342$it" },
                overview = j.str("overview"),
            )
        }.getOrNull()
        synchronized(cache) { if (info != null) cache[cacheKey] = System.currentTimeMillis() to info }
        return info
    }

    /**
     * Directors (or a series' creators), the top ten cast with photos, and the best YouTube trailer.
     * Finds the TMDB title from [tmdbId], or from [imdbId] when that is all there is.
     */
    suspend fun credits(movie: Boolean, tmdbId: Long?, imdbId: String?): app.ember.tv.data.Credits? {
        if (!available) return null
        return runCatching {
            val type = if (movie) "movie" else "tv"
            val id = tmdbId ?: imdbId?.let { imdb ->
                val found = Http.metadata("https://api.themoviedb.org/3/find/$imdb?api_key=$key&external_source=imdb_id")
                found.arr(if (movie) "movie_results" else "tv_results").list().firstOrNull().long("id")
            } ?: return null
            val j = Http.metadata("https://api.themoviedb.org/3/$type/$id?api_key=$key&append_to_response=credits,videos")
            val photo = { p: String? -> p?.let { "https://image.tmdb.org/t/p/w185$it" } }
            val crew = if (movie) {
                val all = j.obj("credits").arr("crew").list()
                all.filter { it.str("job") == "Director" }
                    .map { app.ember.tv.data.Person(it.str("name") ?: "", "Director", photo(it.str("profile_path")), isCrew = true, tmdbId = it.long("id")) } +
                    // Screenplay, writer, story and novel credits, once per person.
                    all.filter { it.str("department") == "Writing" && it.str("job") in setOf("Screenplay", "Writer", "Story", "Novel", "Author") }
                        .distinctBy { it.long("id") ?: it.str("name") }.take(4)
                        .map { app.ember.tv.data.Person(it.str("name") ?: "", "Writer", photo(it.str("profile_path")), isCrew = true, tmdbId = it.long("id")) }
            } else {
                j.arr("created_by").list()
                    .map { app.ember.tv.data.Person(it.str("name") ?: "", "Creator", photo(it.str("profile_path")), isCrew = true, tmdbId = it.long("id")) }
            }
            val cast = j.obj("credits").arr("cast").list().take(10).map {
                app.ember.tv.data.Person(it.str("name") ?: "", it.str("character")?.takeIf { c -> c.isNotBlank() }, photo(it.str("profile_path")), tmdbId = it.long("id"))
            }
            // Official trailers first, then any trailer, then a teaser.
            val videos = j.obj("videos").arr("results").list().filter { it.str("site") == "YouTube" && it.str("key") != null }
            val trailer = (
                videos.firstOrNull { it.str("type") == "Trailer" && it.bool("official") == true }
                    ?: videos.firstOrNull { it.str("type") == "Trailer" }
                    ?: videos.firstOrNull { it.str("type") == "Teaser" }
                ).str("key")
            app.ember.tv.data.Credits((crew + cast).filter { it.name.isNotBlank() }.distinctBy { it.name + it.role }, trailer)
        }.getOrNull()
    }

    /** IMDb id for a TMDB title. Stream addons look titles up by IMDb id. */
    suspend fun imdbFor(kind: Kind, id: Long): String? {
        if (!available) return null
        val type = if (kind == Kind.MOVIE) "movie" else "tv"
        return runCatching {
            Http.metadata("https://api.themoviedb.org/3/$type/$id/external_ids?api_key=${key}").str("imdb_id")
        }.getOrNull()?.takeIf { it.startsWith("tt") }
    }

    /**
     * "Hidden gems": well-rated movies that few people have rated.
     * Thresholds: rating 7.3+, 300 to 3,000 votes,
     * no documentaries or TV movies. A random page keeps the row from going stale.
     */
    suspend fun hiddenGems(): List<TrackedItem> {
        if (!available) return emptyList()
        val page = (1..5).random()
        val url = "https://api.themoviedb.org/3/discover/movie?api_key=${key}" +
            "&sort_by=vote_average.desc&vote_average.gte=7.3&vote_count.gte=300&vote_count.lte=3000" +
            "&without_genres=99,10770&page=$page"
        return Http.metadata(url).arr("results").list().mapNotNull { r ->
            val id = r.long("id") ?: return@mapNotNull null
            TrackedItem(
                key = "tmdb:gem:$id",
                title = r.str("title") ?: return@mapNotNull null,
                year = r.str("release_date")?.take(4)?.toIntOrNull(),
                kind = Kind.MOVIE,
                poster = r.str("poster_path")?.let { "https://image.tmdb.org/t/p/w342$it" },
                tmdbId = id,
                source = "TMDB",
            )
        }
    }
}
