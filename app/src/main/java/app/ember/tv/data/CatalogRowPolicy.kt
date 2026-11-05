package app.ember.tv.data

/** Filter cached rows before displaying a new profile or changed addon settings. */
internal fun visibleCatalogRows(
    rows: List<CatalogRow>, profile: Profile, addons: List<String>,
    switches: Map<String, Boolean>, defaultsOff: Boolean = false, live: Boolean = false,
): List<CatalogRow> = rows.filter { row ->
    val key = row.catalogKey?.let(CatalogIdentity::migrate)
    key != null && addons.any { CatalogIdentity.belongs(key, it) } &&
        (profile.catalogs == null || key in profile.catalogs.map(CatalogIdentity::migrate)) &&
        (!live || profile.live) &&
        (if (!live && profile.kids && profile.catalogs != null) true else switches.entries.firstOrNull { CatalogIdentity.migrate(it.key) == key }?.value ?: !defaultsOff)
}.mapNotNull {row->
    if(!KidsContentPolicy.restricted(profile))row
    else row.copy(items=row.items.filter {KidsContentPolicy.allows(profile,it)}).takeIf {it.items.isNotEmpty()}
}
