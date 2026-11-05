package app.ember.tv.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The last Home rows saved on the TV, so Home shows them the moment the launcher starts and then
 * refreshes from the addons and trackers in the background. Artwork comes from the image cache.
 * Media server rows are not saved (their links carry sign-in tokens).
 */
object HomeSnapshot {
    @Serializable
    data class Row(val addonName: String, val title: String, val items: List<Meta>, val catalogKey: String? = null)

    @Serializable
    data class Data(
        val rows: List<Row> = emptyList(),
        val live: List<Row> = emptyList(),
        val smart: List<Row> = emptyList(),
        val continueItems: List<Meta> = emptyList(),
        val savedAt: Long = 0,
        val generation:String="",
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private fun file(context: Context) = File(context.filesDir, "home-snapshot.json")

    /** Reads the saved rows; null if there are none or the file cannot be read. Fast enough for start-up. */
    @Synchronized fun load(context: Context): Data? = runCatching {
        val f = file(context)
        if (!f.exists()) null else {
            val old = json.decodeFromString(Data.serializer(), f.readText())
            if(old.generation!=ConfigurationGeneration.current(context.filesDir))return@runCatching null
            val safe = old.copy(rows = old.rows.map { it.copy(catalogKey = it.catalogKey?.let(CatalogIdentity::migrate)) }, live = old.live.map { it.copy(catalogKey = it.catalogKey?.let(CatalogIdentity::migrate)) })
            if (safe != old) AtomicFile.write(f, json.encodeToString(Data.serializer(), safe)).getOrThrow()
            File(f.parentFile, f.name + ".tmp").delete()
            safe
        }
    }.getOrNull()

    /** Writes the rows (call off the main thread). Long rows are cut to what Home shows first. */
    @Synchronized fun save(context: Context, data: Data) {
        runCatching {
            val trimmed = data.copy(
                rows = data.rows.map { it.copy(items = it.items.take(30), catalogKey = it.catalogKey?.let(CatalogIdentity::migrate)) },
                live = data.live.map { it.copy(items = it.items.take(30), catalogKey = it.catalogKey?.let(CatalogIdentity::migrate)) },
                smart = data.smart.map { it.copy(items = it.items.take(30), catalogKey = it.catalogKey?.let(CatalogIdentity::migrate)) },
                continueItems = data.continueItems.take(30),
            )
            val f = file(context)
            ConfigurationGeneration.writeIfCurrent(context.filesDir,data.generation) {
                AtomicFile.write(f,json.encodeToString(Data.serializer(),trimmed)).getOrThrow()
            }.getOrThrow()
        }
    }

    @Synchronized fun clear(context: Context) {
        val target = file(context)
        target.delete(); File(target.parentFile, target.name + ".tmp").delete()
    }

    fun CatalogRow.toSnap() = Row(addonName, title, items, catalogKey)
    fun Row.toRow() = CatalogRow(addonName, title, items, catalogKey = catalogKey)
}
