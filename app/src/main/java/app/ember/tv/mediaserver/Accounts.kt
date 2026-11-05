package app.ember.tv.mediaserver

import android.content.Context
import app.ember.tv.tracker.TokenStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** A signed-in Jellyfin or Emby server. Plex is stored separately because one sign-in covers all its servers. */
@Serializable
data class ServerAccount(
    val key: String,
    /** "jellyfin" or "emby" */
    val kind: String,
    val name: String,
    val url: String,
    val userId: String,
    val token: String,
    val userName: String = "",
)

/** Media server sign-ins, kept in the same encrypted store as tracker tokens. */
interface ServerSecrets {
    fun read(key: String): String?
    fun write(key: String,value: String): Boolean
    fun remove(key: String)
}
class Accounts(private val secrets: ServerSecrets) {
    companion object { private val sessionLock = Any() }
    constructor(context: Context):this(object:ServerSecrets {
        private val store=TokenStore(context)
        override fun read(key: String)=store.get(key)
        override fun write(key: String,value: String)=store.putEncrypted(key,value)
        override fun remove(key: String)=store.remove(key)
    })
    private val json = Json { ignoreUnknownKeys = true }

    /** A stable id for this TV, sent to Plex and Jellyfin so they list it as one device. */
    fun deviceId(): String = synchronized(sessionLock) {
        secrets.read("ms.deviceId") ?: UUID.randomUUID().toString().also { check(secrets.write("ms.deviceId",it)) { "Encrypted device storage is unavailable." } }
    }

    var plexToken: String?
        get() = secrets.read("plex.token")
        set(v) = synchronized(sessionLock) {
            if (v == null) secrets.remove("plex.token") else check(secrets.write("plex.token",v)) { "Encrypted sign-in storage is unavailable. Existing sign-in was kept." }
            secrets.remove("plex.home")
        }

    internal var plexHome: PlexHomeSession?
        get() = runCatching { json.decodeFromString<PlexHomeSession>(secrets.read("plex.home") ?: return null) }.getOrNull()
        set(v) = synchronized(sessionLock) {
            if (v == null) secrets.remove("plex.home") else check(secrets.write("plex.home", json.encodeToString(v))) {
                "Encrypted sign-in storage is unavailable. Existing Plex user was kept."
            }
        }
    val plexAccessToken: String? get() {
        val selected = secrets.read("plex.home") ?: return plexToken
        // An unreadable selected session must not silently grant the owner's broader access.
        return runCatching { json.decodeFromString<PlexHomeSession>(selected).token.takeIf { it.isNotBlank() } }.getOrNull()
    }
    val plexUserName: String? get() = plexHome?.name ?: if (secrets.read("plex.home") != null) "User unavailable" else null
    val plexUserId: String? get() = plexHome?.id

    fun servers(): List<ServerAccount> =
        runCatching { json.decodeFromString<List<ServerAccount>>(secrets.read("ms.servers") ?: "[]") }.getOrDefault(emptyList())

    fun saveServers(list: List<ServerAccount>) = synchronized(sessionLock) { check(secrets.write("ms.servers",json.encodeToString(list))) { "Encrypted server storage is unavailable. Existing accounts were kept." } }

    /** A playback visit keeps its authenticated user even if settings select another account. RAM only. */
    internal fun playbackSnapshot(): Accounts = synchronized(sessionLock) {
        val values = listOf("plex.token", "plex.home", "ms.servers").mapNotNull { key -> secrets.read(key)?.let { key to it } }.toMap() +
            ("ms.deviceId" to (secrets.read("ms.deviceId") ?: UUID.randomUUID().toString()))
        Accounts(object : ServerSecrets {
            override fun read(key: String) = values[key]
            override fun write(key: String, value: String) = false
            override fun remove(key: String) { error("Playback account snapshots are read-only.") }
        })
    }
}
