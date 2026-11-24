package app.ember.tv.data

import app.ember.tv.state.DiscoverSection

/** Missing choices follow content availability; explicit choices survive content changes. */
object DiscoverVisibility {
    /** [spark]: shown unless turned off, when there is a TMDB key (#435). */
    fun visible(choices: Map<String, Boolean>, catalogs: Boolean, collections: Boolean, kids: Boolean, spark: Boolean = false): List<DiscoverSection> =
        DiscoverSection.entries.filter { section ->
            (!kids || section == DiscoverSection.CATALOGS) && (choices[section.name] ?: when (section) {
                DiscoverSection.EXPLORE -> true
                DiscoverSection.SPARK -> spark
                DiscoverSection.CATALOGS -> catalogs
                DiscoverSection.COLLECTIONS -> collections
            })
        }

    fun selected(requested: DiscoverSection, visible: List<DiscoverSection>): DiscoverSection? =
        requested.takeIf { it in visible } ?: visible.firstOrNull()
}
