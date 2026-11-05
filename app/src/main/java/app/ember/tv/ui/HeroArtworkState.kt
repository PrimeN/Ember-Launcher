package app.ember.tv.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import app.ember.tv.data.ApiKeys
import app.ember.tv.data.HeroArtwork
import app.ember.tv.data.HeroArtworkRepository
import app.ember.tv.data.mediaKey
import app.ember.tv.data.Meta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A small UI mirror makes already-enriched artwork available at the instant a scene is selected. */
internal object HeroArtworkState {
    private var repository: HeroArtworkRepository? = null
    private val memory = LinkedHashMap<String, HeroArtwork>(16, .75f, true)
    private val identityPermits = Semaphore(2)
    private val serverIds = LinkedHashMap<String, Pair<Long, Pair<String?, Long?>>>(16, .75f, true)
    private suspend fun identified(context: Context, meta: Meta): Meta {
        if (app.ember.tv.data.HeroIdentity.of(meta) != null) return meta
        val ref = app.ember.tv.mediaserver.ServerRef.of(meta)?.value ?: return meta
        if (ApiKeys.get("TMDB_API_KEY").isBlank() && ApiKeys.get("FANART_API_KEY").isBlank()) return meta
        return identityPermits.withPermit {
            val now = System.currentTimeMillis()
            val cached = synchronized(serverIds) { serverIds[ref]?.takeIf { now - it.first in 0..60_000 }?.second }
            if(cached!=null)return@withPermit meta.copy(imdbId=cached.first ?: meta.imdbId,tmdbId=cached.second ?: meta.tmdbId)
            val ids = try { app.ember.tv.mediaserver.MediaServers(context).titleIds(ref) }
                catch(e:kotlinx.coroutines.CancellationException){throw e}
                catch(_:Exception){null to null}
            currentCoroutineContext().ensureActive()
            synchronized(serverIds) {
                serverIds[ref] = now to ids
                while(serverIds.size > 96)serverIds.remove(serverIds.keys.first())
            }
            meta.copy(imdbId = ids.first ?: meta.imdbId, tmdbId = ids.second ?: meta.tmdbId)
        }
    }
    private fun key(meta: Meta, locale: String, revision: Int, mode: String) = "${meta.mediaKey()}:${meta.type}:${meta.tmdbId}:${meta.imdb}:$locale:$revision:$mode"
    fun peek(meta: Meta?, locale: String, revision: Int, mode: String): HeroArtwork = synchronized(memory) {
        meta?.let { memory[key(it, locale, revision, mode)] } ?: HeroArtwork()
    }
    suspend fun load(context: Context, meta: Meta, locale: String, revision: Int, mode: String): HeroArtwork {
        val result = withContext(Dispatchers.IO) {
            val repo = synchronized(this@HeroArtworkState) {
                repository ?: HeroArtworkRepository(File(context.applicationContext.cacheDir, "hero-art-v1")).also { repository = it }
            }
            repo.resolve(identified(context, meta), locale, ApiKeys.get("TMDB_API_KEY"), ApiKeys.get("FANART_API_KEY"), mode)
        }
        currentCoroutineContext().ensureActive()
        if (ApiKeys.revision == revision) synchronized(memory) {
            memory[key(meta, locale, revision, mode)] = result
            while (memory.size > 96) memory.remove(memory.keys.first())
        }
        return result
    }
}

@Composable
internal fun rememberHeroDecoration(meta: Meta?): HeroArtwork {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]?.toLanguageTag() ?: "en"
    val revision = ApiKeys.revision
    val mode = LocalHeroArtworkMode.current
    val identity = LocalNavigationFocus.current
    val active = LocalSceneInteractive.current
    val id = meta?.mediaKey()
    var art by remember(identity, id, locale, revision, mode) {
        mutableStateOf(HeroArtworkState.peek(meta, locale, revision, mode))
    }
    LaunchedEffect(identity, id, meta?.tmdbId, meta?.imdb, locale, revision, mode, active) {
        if (meta != null && active) {
            delay(250) // Decorative enrichment never races every repeated D-pad press.
            art = HeroArtworkState.load(context, meta, locale, revision, mode)
        }
    }
    return art
}
