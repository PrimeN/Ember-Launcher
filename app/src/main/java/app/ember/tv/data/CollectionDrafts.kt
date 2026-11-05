package app.ember.tv.data

import app.ember.tv.discover.ExploreQuery
import java.util.UUID

object CollectionDrafts {
    fun collection() = MediaCollection(UUID.randomUUID().toString(), "")
    fun folder() = CollectionFolder(UUID.randomUUID().toString(), "", sources = emptyList())
    fun catalog(def: CatalogDef, genre: String? = null, extras: Map<String, String> = def.defaultExtras) = CollectionSource(
        addonId = def.addon.manifestId, catalogId = def.id, type = def.type, title = def.label,
        addonKey = AddonAccess.key(def.addon.url), genre = genre, extraArgs = extras)
    fun discover(name: String, query: ExploreQuery, profile: String) = CollectionSource(
        provider = "ember", title = name.trim(), exploreQuery = query, ownerProfile = profile)
    fun duplicate(value: MediaCollection) = value.copy(id = UUID.randomUUID().toString(), title = value.title + " (copy)", folders = value.folders.map { duplicate(it) })
    fun duplicate(value: CollectionFolder) = value.copy(id = UUID.randomUUID().toString())
    fun numericId(text: String): Long? = text.trim().toLongOrNull()?.takeIf { it > 0 }
        ?: Regex("(?:^|/)([0-9]+)(?:[-/?#]|$)").findAll(text.trim()).lastOrNull()?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }
    fun move(sources: List<CollectionSource>, index: Int, delta: Int): List<CollectionSource> = sources.toMutableList().apply {
        val target = index + delta
        if (index in indices && target in indices) add(target, removeAt(index))
    }
}
