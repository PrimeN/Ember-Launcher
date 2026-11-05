package app.ember.tv.ui

import app.ember.tv.data.Meta

/** Stable identities win; IMDb-only providers can match a public credit by name and known year. */
internal fun otherPersonTitles(current:Meta,titles:List<Meta>):List<Meta> = titles.filterNot {candidate->
    if((current.type=="movie")!=(candidate.type=="movie"))false
    else when {
        current.tmdbId!=null && candidate.tmdbId!=null -> current.tmdbId==candidate.tmdbId
        current.imdb!=null && candidate.imdb!=null -> current.imdb==candidate.imdb
        else -> current.name.equals(candidate.name,true) && current.releaseInfo?.take(4)?.toIntOrNull()?.let {year->
            year==candidate.releaseInfo?.take(4)?.toIntOrNull()
        }==true
    }
}
