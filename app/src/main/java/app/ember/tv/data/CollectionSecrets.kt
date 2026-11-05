package app.ember.tv.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Account-like extra fields remain usable without copying their values into collection JSON. */
internal object CollectionSecrets {
    private val json = Json { ignoreUnknownKeys = true }
    fun sensitive(name: String): Boolean = name.lowercase().replace(Regex("[^a-z]"), "") in
        setOf("token", "apikey", "password", "passwd", "secret", "authorization", "auth", "credential", "accesstoken", "refreshtoken", "username", "user", "login", "clientsecret", "sessionid")
    fun seal(list: List<MediaCollection>, write: (String, String) -> Boolean): List<MediaCollection> = list.map { collection ->
        collection.copy(folders = collection.folders.map { folder ->
            fun sealSource(source: CollectionSource): CollectionSource {
                val secrets = source.extraArgs.filterKeys(::sensitive)
                if (secrets.isEmpty()) return source
                val ref = "collections.args." + java.util.UUID.randomUUID()
                check(write(ref, json.encodeToString(secrets))) { "Encrypted source storage is unavailable." }
                return source.copy(extraArgs = source.extraArgs.filterKeys { !sensitive(it) }, secretArgsRef = ref)
            }
            folder.copy(sources = folder.sources?.map(::sealSource), catalogSources = folder.catalogSources.map(::sealSource))
        })
    }
    fun resolve(source: CollectionSource, read: (String) -> String?): Map<String, String> {
        val stored = source.secretArgsRef?.let { ref ->
            check(ref.startsWith("collections.args.")) { "Invalid private source reference." }
            json.decodeFromString<Map<String, String>>(read(ref) ?: error("Restore this source's encrypted settings or configure it again."))
        }.orEmpty()
        return stored + source.extraArgs
    }
}
