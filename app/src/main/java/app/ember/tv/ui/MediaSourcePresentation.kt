package app.ember.tv.ui

import app.ember.tv.DetailsUi

/** Report availability across enabled sources, without empty warnings for each provider. */
internal data class MediaSourcePresentation(val total: Int, val loading: Boolean) {
    val message: String? get() = when {
        total > 0 -> null
        loading -> "Checking streaming options…"
        else -> "No streaming option available for this title."
    }
}

internal fun mediaSourcePresentation(details: DetailsUi, enabledOffers: Int) = MediaSourcePresentation(
    details.streams.size + details.serverStreams.size + enabledOffers,
    details.loadingStreams || details.loadingServer || details.loadingOffers || details.loadingEpisodes,
)
