package app.ember.tv.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.io.File

/** Injectable secret boundary lets round-trip/failure tests use fixtures, never real credentials. */
internal interface BackupSecrets {
    val encrypted:Boolean
    fun read():Map<String,String>
    fun replace(values:Map<String,String>)
}

internal data class BackupPlan(
    val preferences:Map<String,Map<String,Any>>,
    val tokens:Map<String,String>,
    val files:Map<String,String>,
)

internal object BackupFormat {
    val preferenceFiles=PreferenceKeys.stores
    val dataFiles=listOf("collections.json","badges-custom.json","library.json")
    private val json=Json {ignoreUnknownKeys=true}
    private fun expectedType(name:String,key:String)=PreferenceKeys.expectedType(name,key)
    private fun invalid(path:String):Nothing=throw Backup.BackupException("The backup contains invalid $path. Nothing was restored.")

    fun preferences(values:Map<String,*>):JsonObject=JsonObject(values.mapValues {(_,value)->
        val (type,encoded)=when(value) {
            is String->"s" to JsonPrimitive(value)
            is Boolean->"b" to JsonPrimitive(value)
            is Int->"i" to JsonPrimitive(value)
            is Long->"l" to JsonPrimitive(value)
            is Float->{if(!value.isFinite())invalid("preference number");"f" to JsonPrimitive(value)}
            is Set<*>->{if(value.any {it !is String})invalid("preference set");"set" to JsonArray(value.map {JsonPrimitive(it as String)})}
            else->invalid("preference value")
        }
        buildJsonObject {put("t",type);put("v",encoded)}
    })

    private fun decodePreferences(name:String,values:JsonObject):Map<String,Any> = values.mapValues {(key,entry)->
        val at="preference in $name"
        val obj=entry as? JsonObject ?: invalid(at)
        val value=obj["v"] ?: invalid(at)
        val primitive=value as? JsonPrimitive
        val type=(obj["t"] as? JsonPrimitive)?.contentOrNull
        expectedType(name,key)?.let {if(it!=type)invalid(at)}
        when(type) {
            "s"->primitive?.takeIf {it.isString}?.content ?: invalid(at)
            "b"->primitive?.takeUnless {it.isString}?.booleanOrNull ?: invalid(at)
            "i"->primitive?.takeUnless {it.isString}?.intOrNull ?: invalid(at)
            "l"->primitive?.takeUnless {it.isString}?.longOrNull ?: invalid(at)
            "f"->primitive?.takeUnless {it.isString}?.floatOrNull?.takeIf {it.isFinite()} ?: invalid(at)
            "set"->(value as? JsonArray)?.map {v->(v as? JsonPrimitive)?.takeIf {it.isString}?.content ?: invalid(at)}?.toSet() ?: invalid(at)
            else->invalid(at)
        }
    }.also {decoded->
        if(name=="ember")decoded.forEach {(key,value)->
            if (key in setOf("heroArtworkMode", "heroCatalog") && value !is String) invalid("saved settings $key")
            if(value is String)try {
                when(key) {
                    "screenMargin"->if(!ScreenMargins.valid(value))invalid("screen margin")
                    "cacheConfig"->json.decodeFromString<CacheConfig>(value).also { if (!it.valid()) invalid("cache durations") }
                    "customPerformance"->json.decodeFromString<CustomPerformance>(value)
                    "profiles"->json.decodeFromString<List<Profile>>(value).also { profiles ->
                        if (profiles.isEmpty() || profiles.any { it.id.isBlank() } ||
                            profiles.map { it.id }.distinct().size != profiles.size) invalid("profile IDs")
                    }
                    "playRules"->json.decodeFromString<PlayRules>(value)
                    "playbackConfig"->json.decodeFromString<app.ember.tv.player.PlaybackConfig>(value)
                    "subtitleConfig"->json.decodeFromString<SubtitleConfig>(value)
                    "metaConfig"->json.decodeFromString<MetaConfig>(value)
                    "posterConfig"->json.decodeFromString<PosterConfig>(value)
                    "wl"->json.decodeFromString<List<Meta>>(value)
                    "catalogHome","catalogLive","discoverSections"->json.decodeFromString<Map<String,Boolean>>(value)
                    "catalogNames","addonNames"->json.decodeFromString<Map<String,String>>(value)
                    else->when {
                        key.startsWith("explore:")->json.decodeFromString<List<app.ember.tv.discover.SavedExplore>>(value)
                        key.startsWith("search:")->json.decodeFromString<List<String>>(value)
                        key.startsWith("widgets:")->json.decodeFromString<List<HomeWidget>>(value)
                        else->Unit
                    }
                }
            } catch(_:Exception) {invalid("saved settings $key")}
        }
    }

    fun plan(payload:JsonObject):BackupPlan {
        if(payload["prefs.ember"] !is JsonObject || payload["tokens"] !is JsonObject || payload["files"] !is JsonObject)invalid("configuration sections")
        if(payload.keys.any {it.startsWith("prefs.") && it.removePrefix("prefs.") !in preferenceFiles})invalid("unsupported preference section")
        val prefs=preferenceFiles.associateWith {name->
            val value=payload["prefs.$name"] ?: JsonObject(emptyMap())
            decodePreferences(name,value as? JsonObject ?: invalid("preference section $name"))
        }
        val core=prefs.getValue("ember")
        val profiles=(core["profiles"] as? String)?.let {json.decodeFromString<List<Profile>>(it)}.orEmpty()
        try {
            (core["personalHomes"] as? String)?.let {raw->
                val homes=json.decodeFromString<Map<String,app.ember.tv.home.PersonalHome>>(raw)
                app.ember.tv.home.HomeConfigValidation.homes(homes,profiles.map {it.id}.toSet()+Profile.MAIN)
            }
            (core["launcherExtras"] as? String)?.let {
                val extras=json.decodeFromString<app.ember.tv.home.LauncherExtras>(it)
                app.ember.tv.home.HomeConfigValidation.extras(extras)
                if(extras.lockedApps.isNotEmpty() && (core["pinHash"] as? String).isNullOrBlank())invalid("app locks without a PIN")
            }
        } catch(_:Exception){invalid("Home customization")}
        if(profiles.any(KidsContentPolicy::restricted) && (core["pinHash"] as? String).isNullOrBlank())
            invalid("Kids content lock without a PIN")
        val tokens=(payload.getValue("tokens") as JsonObject).mapValues {(_,v)->
            (v as? JsonPrimitive)?.takeIf {it.isString}?.content ?: invalid("credential entry")
        }
        val files=(payload.getValue("files") as JsonObject).mapValues {(name,value)->
            if(name !in dataFiles)invalid("unsupported saved file")
            val text=(value as? JsonPrimitive)?.takeIf {it.isString}?.content ?: invalid("saved file $name")
            try {
                when(name) {
                    "collections.json"->CollectionCodec.decode(text)
                    "badges-custom.json"->json.decodeFromString<BadgePack>(text)
                    "library.json"->LocalLibrary.validateBackup(text)
                }
            } catch(_:Exception) {invalid("saved file $name")}
            text
        }
        return BackupPlan(prefs,tokens,files)
    }
}

/** Validate everything before mutation; detected save failures restore the previous logical state. */
internal class BackupPersistence(
    private val context:Context,
    private val secrets:BackupSecrets,
    private val writeFile:(File,ByteArray?)->Unit={file,bytes->
        if(bytes==null) {
            if(file.exists() && !file.delete())throw Backup.BackupException("Could not remove ${file.name}.")
        } else AtomicFile.write(file,bytes).getOrElse {throw Backup.BackupException("Could not save ${file.name}.")}
    },
) {
    private fun prefs(name:String)=context.getSharedPreferences(name,Context.MODE_PRIVATE)
    private fun currentPrefs()=BackupFormat.preferenceFiles.associateWith {name->
        prefs(name).all.mapValues {(_,value)->if(value is Set<*>)value.toSet() else value}
    }
    private fun currentFiles()=BackupFormat.dataFiles.associateWith {name->File(context.filesDir,name).takeIf {it.exists()}?.readBytes()}

    fun snapshot():JsonObject=buildJsonObject {
        WatchedStore.drain()
        currentPrefs().forEach {(name,values)->put("prefs.$name",BackupFormat.preferences(values))}
        put("tokens",JsonObject(secrets.read().mapValues {JsonPrimitive(it.value)}))
        put("files",JsonObject(currentFiles().mapNotNull {(name,bytes)->bytes?.let {name to JsonPrimitive(it.toString(Charsets.UTF_8))}}.toMap()))
    }.also {BackupFormat.plan(it)}

    private fun writePreferences(name:String,values:Map<String,*>) {
        val store=prefs(name)
        val edit=store.edit().clear()
        values.forEach {(key,value)->when(value) {
            is String->edit.putString(key,value)
            is Boolean->edit.putBoolean(key,value)
            is Int->edit.putInt(key,value)
            is Long->edit.putLong(key,value)
            is Float->edit.putFloat(key,value)
            is Set<*>->edit.putStringSet(key,value.filterIsInstance<String>().toSet())
            else->throw Backup.BackupException("Unsupported preference in $name.")
        }}
        // Restored values may predate the catalog identity migration: let the next Prefs migrate them.
        CatalogIdentity.forget(store)
        if(!edit.commit() || store.all!=values)throw Backup.BackupException("Could not save preferences in $name.")
    }

    fun restore(payload:JsonObject) {
        val plan=BackupFormat.plan(payload)
        WatchedStore.drain()
        val oldTokens=secrets.read()
        if((plan.tokens.isNotEmpty() || oldTokens.isNotEmpty()) && !secrets.encrypted)throw Backup.BackupException("Encrypted storage is unavailable. Restore was not applied.")
        val oldPrefs=currentPrefs();val oldFiles=currentFiles()
        try {
            plan.preferences.forEach {(name,values)->writePreferences(name,values)}
            secrets.replace(plan.tokens)
            if(secrets.read()!=plan.tokens)throw Backup.BackupException("Restored credentials could not be verified.")
            BackupFormat.dataFiles.forEach {name->writeFile(File(context.filesDir,name),plan.files[name]?.toByteArray(Charsets.UTF_8))}
            BackupFormat.dataFiles.forEach {name->
                val file=File(context.filesDir,name);val expected=plan.files[name]?.toByteArray(Charsets.UTF_8)
                if(if(expected==null)file.exists() else !file.isFile || !file.readBytes().contentEquals(expected))
                    throw Backup.BackupException("Could not verify saved file $name.")
            }
            ConfigurationGeneration.advance(context.filesDir)
        } catch(error:Exception) {
            var recovered=true
            oldPrefs.forEach {(name,values)->if(runCatching {writePreferences(name,values)}.isFailure)recovered=false}
            if(runCatching {secrets.replace(oldTokens);check(secrets.read()==oldTokens)}.isFailure)recovered=false
            oldFiles.forEach {(name,bytes)->if(runCatching {
                val file=File(context.filesDir,name)
                writeFile(file,bytes)
                check(if(bytes==null)!file.exists() else file.isFile && file.readBytes().contentEquals(bytes))
            }.isFailure)recovered=false}
            throw Backup.BackupException(if(recovered)"Restore failed. Previous settings and saved data were kept." else "Restore failed and some previous data could not be recovered. Keep your backup and retry.")
        }
        CacheManagement.preferencesRestored(context)
        WatchedStore.restored()
        // Rebuildable caches are excluded from backups; generation checks also reject late old writes.
        runCatching {HomeSnapshot.clear(context)}
        listOf("channels.bin","guide.bin","channels.json","guide.json").forEach {name->runCatching {File(context.filesDir,"iptv/$name").delete()}}
        runCatching {File(context.cacheDir,"discover").deleteRecursively()}
    }
}
