package app.ember.tv.data

import app.ember.tv.state.DiscoverSection

/** Missing choices follow content availability; explicit choices survive content changes. */
object DiscoverVisibility {
    fun visible(choices: Map<String, Boolean>, catalogs: Boolean, collections: Boolean, kids: Boolean): List<DiscoverSection> =
        DiscoverSection.entries.filter { section ->
            (!kids || section == DiscoverSection.CATALOGS) && (choices[section.name] ?: when (section) {
                DiscoverSection.EXPLORE -> true
                DiscoverSection.CATALOGS -> catalogs
                DiscoverSection.COLLECTIONS -> collections
            })
        }

    fun selected(requested: DiscoverSection, visible: List<DiscoverSection>): DiscoverSection? =
        requested.takeIf { it in visible } ?: visible.firstOrNull()
}
