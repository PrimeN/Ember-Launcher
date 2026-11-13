package app.ember.tv.discover

/** Each recovery step relaxes only the named group; never discards a saved search wholesale. */
object ExploreRecovery {
    data class Choice(val label: String, val query: ExploreQuery)
    fun choices(q: ExploreQuery): List<Choice> = buildList {
        if(q.providers.isNotEmpty())add(Choice("Any streaming service",q.copy(providers=emptyList(),includeRentBuy=false)))
        if(q.genres.isNotEmpty())add(Choice("Any genre",q.copy(genres=emptySet(),allGenres=false)))
        if(q.fromYear!=null || q.toYear!=null)add(Choice("Any year",q.copy(fromYear=null,toYear=null)))
        if(q.minRating!=null || q.maxRating!=null)add(Choice("Any rating",q.copy(minRating=null,maxRating=null)))
        if(q.actors.isNotEmpty())add(Choice("Any cast",q.copy(actors=emptyList())))
        if(q.directors.isNotEmpty())add(Choice("Any director",q.copy(directors=emptyList())))
        if(q.companies.isNotEmpty())add(Choice("Any studio",q.copy(companies=emptyList())))
        if(q.oscars!=OscarMode.ANY || q.bestPicture)add(Choice("Any awards",q.copy(oscars=OscarMode.ANY,bestPicture=false,award="oscars")))
        if(q.sort==ExploreSort.RATING && (q.minMovieVotes>0 || q.minTvVotes>0))add(Choice("Include titles with fewer votes",q.copy(minMovieVotes=0,minTvVotes=0)))
    }
    fun explanation(q: ExploreQuery, region: String, limited: Boolean): String = buildString {
        append(if(limited) "No matches in the available provider pages." else "No titles matched every selected filter.")
        if(q.providers.isNotEmpty())append(" Streaming availability is for ${region.uppercase()}; providers may not list every title in that region.")
        if(q.oscars!=OscarMode.ANY)append(" Missing award records cannot count as matches.")
        append(" Your criteria are kept. Try relaxing one filter group.")
    }
}
