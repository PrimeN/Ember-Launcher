package app.ember.tv.ui

import app.ember.tv.DetailsUi
import app.ember.tv.data.StreamOption

internal enum class DetailsPlaybackTarget { SOURCE, SERVICE, CHOOSE }
/** Keep the primary action present, including when automatic source choice is switched off. */
internal fun detailsPlaybackTarget(automatic:Boolean,best:Boolean,offers:Int,loading:Boolean,serviceInstalled:Boolean=true):DetailsPlaybackTarget = when {
    !automatic -> DetailsPlaybackTarget.CHOOSE
    best -> DetailsPlaybackTarget.SOURCE
    offers==1 && !loading && serviceInstalled -> DetailsPlaybackTarget.SERVICE
    else -> DetailsPlaybackTarget.CHOOSE
}

/** The label and availability describe the same action that will run on OK. */
internal data class DetailsPlaybackPresentation(
    val label:String, val detail:String?, val enabled:Boolean,
)

internal fun detailsPlaybackPresentation(
    playbackLabel:String, target:DetailsPlaybackTarget, best:StreamOption?,
    availability:MediaSourcePresentation, serviceName:String?, reason:String?=null,
):DetailsPlaybackPresentation {
    if(best==null && availability.total==0) return DetailsPlaybackPresentation(
        if(availability.loading)"Finding sources…" else "No source available",null,false)
    val detail=when(target) {
        DetailsPlaybackTarget.SOURCE -> best?.let {
            listOf(it.addonName,it.quality,it.hdr,it.audio).filter(String::isNotBlank).distinct().joinToString(" · ")
        }
        DetailsPlaybackTarget.SERVICE -> serviceName
        // #145: say why the Play button couldn't pick, so the rule can be changed.
        DetailsPlaybackTarget.CHOOSE -> reason?.let {"Play button: $it · choose from ${availability.total}"}
            ?: ("Choose a source · ${availability.total} option"+if(availability.total==1)"" else "s")
    }
    return DetailsPlaybackPresentation(playbackLabel,detail,true)
}

/** Index of the actual keyed Sources header, including conditional episode/cast sections. */
internal fun detailsSourceHeaderIndex(d:DetailsUi):Int = 1+
    (if(d.loadingEpisodes)1 else 0)+(if(d.seasons.size>1)1 else 0)+
    (if(d.episodes.isNotEmpty())1 else 0)+(if(!d.credits?.people.isNullOrEmpty())1 else 0)

internal fun detailsSynopsis(d:DetailsUi):String? = d.episode?.overview?.takeIf(String::isNotBlank)
    ?: d.meta.description?.takeIf(String::isNotBlank)
