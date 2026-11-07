package app.ember.tv.data

/**
 * What the catalog artwork cache is signed with (#50): only what changes a catalog's icon or
 * label. It must not include a catalog's [AddonInfo] as a whole: that carries the raw JSON of
 * every catalog the addon offers, so the old `toString()` repeated the manifest once per catalog
 * and froze the TV on addons with many catalogs.
 */
internal object CatalogIconSignature {
    fun of(
        catalogs: List<CatalogDef>, names: Map<String, String>, home: Map<String, Boolean>,
        discoverOff: Set<String>, live: Map<String, Boolean>,
    ): String = buildString {
        fun field(value: String?) { val v = value.orEmpty(); append(v.length).append(':').append(v) }
        catalogs.forEach { c ->
            field(c.addon.url); field(c.addon.name); field(c.addon.manifestId)
            field(c.type); field(c.id); field(c.name)
            append('\n')
        }
        append('|'); names.toSortedMap().forEach { (k, v) -> field(k); field(v) }
        append('|'); home.toSortedMap().forEach { (k, v) -> field(k); append(if (v) '1' else '0') }
        append('|'); discoverOff.sorted().forEach(::field)
        append('|'); live.toSortedMap().forEach { (k, v) -> field(k); append(if (v) '1' else '0') }
    }
}
