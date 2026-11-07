package app.ember.tv.light

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.net.Uri
import androidx.core.net.toUri
import app.ember.tv.home.PersonalHome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.URI
import java.text.Normalizer
import java.util.Locale

internal data class LightMedia(
    val id: String, val pkg: String, val title: String, val description: String,
    val artwork: String?, val intent: String?, val progress: Float?, val engaged: Long,
    val continuing: Boolean, val duration: Long = 0, val position: Long = 0, val rank: Long = 0,
    /** The app's channel it came from ("Recently added"); empty for Continue watching. */
    val channel: String = "",
)

/** Ember's Home policy. Platform cursor reading is handled by AndroidTvProgramSource. */
internal object LightMediaProvider {
    const val PERMISSION = "android.permission.READ_TV_LISTINGS"
    val watchUri: Uri get() = TvContract.WatchNextPrograms.CONTENT_URI
    val previewUri: Uri get() = TvContract.PreviewPrograms.CONTENT_URI
    val channelUri: Uri get() = TvContract.Channels.CONTENT_URI
    private const val DAY = 86_400_000L
    private const val ROTATION = 2 * 60 * 60 * 1000L
    private val cache = TvProgramCache { android.os.SystemClock.elapsedRealtime() }

    fun invalidate() = cache.invalidate()
    fun granted(context: Context) = runCatching {
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Unicode words form a presentation identity; stored dismissal IDs use platform row IDs. */
    fun normalize(title: String): String = buildString {
        val words = Normalizer.normalize(title, Normalizer.Form.NFKC).lowercase(Locale.ROOT).codePoints().iterator()
        var separator = false
        while (words.hasNext()) {
            val code = words.nextInt()
            if (Character.isLetterOrDigit(code)) {
                if (separator && isNotEmpty()) append(' ')
                appendCodePoint(code)
                separator = false
            } else if (isNotEmpty()) separator = true
        }
    }

    fun validArtwork(value: String?): String? {
        if (value.isNullOrEmpty() || value.length > 8192) return null
        val scheme = runCatching { URI(value).scheme?.lowercase(Locale.ROOT) }.getOrNull()
        return value.takeIf { scheme in setOf("https", "http", "content", "android.resource") }
    }

    fun eligible(type: Int, browsable: Boolean, position: Long, duration: Long, engaged: Long, now: Long): Boolean {
        if (!browsable || type !in setOf(
                TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE,
                TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT,
                TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEW,
            )) return false
        if (engaged > 0) {
            val age = now.toDouble() - engaged.toDouble()
            if (age < -DAY || age > 60.0 * DAY) return false
        }
        return type != TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE || duration <= 0 || position.toDouble() / duration < .95
    }

    fun visible(items: List<LightMedia>, allowed: Set<String>, children: Boolean, watch: Boolean, previews: Boolean): List<LightMedia> {
        if (children) return emptyList()
        return items.filter { media -> media.pkg in allowed && (if (media.continuing) watch else previews) }
    }

    // This persisted token format is retained so an existing dismissal remains effective.
    fun engagementToken(item: LightMedia) = item.engaged.takeIf { it > 0 } ?: ((item.position + 1) * 31 + item.duration)

    fun personalized(items: List<LightMedia>, home: PersonalHome): List<LightMedia> = items.filter { item ->
        when {
            home.dismissed[item.id] == engagementToken(item) -> false
            !item.continuing -> item.pkg !in home.mutedApps
            home.hideShortClips && item.duration in 1 until 600_000L -> false
            home.hideCredits && item.duration >= 600_000L && item.position > 0 && item.duration - item.position <= 120_000L -> false
            else -> true
        }
    }

    fun resumeChannel(name: String): Boolean {
        val words = normalize(name)
        return words == "resume" || words.contains("continue watching") || words.contains("in progress")
    }

    private data class Title(val owner: String, val words: String, val resume: Boolean)

    /** Fill resume slots by recency, then deal recommendations one app at a time. */
    fun select(items: List<LightMedia>, now: Long): List<LightMedia> {
        val identities = HashSet<Title>()
        val resume = ArrayList<LightMedia>()
        val apps = LinkedHashMap<String, MutableList<LightMedia>>()
        for (item in items) {
            if (!identities.add(Title(item.pkg, normalize(item.title), item.continuing))) continue
            if (item.continuing) resume.add(item) else apps.getOrPut(item.pkg) { ArrayList() }.add(item)
        }
        val output = ArrayList<LightMedia>(24)
        val included = HashMap<String, Int>()
        for (item in resume.sortedByDescending { it.engaged }) {
            val count = included[item.pkg] ?: 0
            if (count == 3) continue
            output.add(item)
            included[item.pkg] = count + 1
            if (output.size == 12) break
        }
        val queues = apps.values.map { it.sortedByDescending { item -> item.rank }.take(8) }
        val turn = now / ROTATION
        var recommendations = 0
        for (round in 0..1) {
            for (queue in queues) {
                if (round >= queue.size) continue
                val index = Math.floorMod(turn + round, queue.size.toLong()).toInt()
                output.add(queue[index])
                recommendations++
                if (recommendations == 12) return output
            }
        }
        return output
    }

    suspend fun read(context: Context, allowed: Set<String>, watch: Boolean, previews: Boolean, personal: PersonalHome = PersonalHome()): List<LightMedia> = withContext(Dispatchers.IO) {
        if ((!watch && !previews) || !granted(context)) return@withContext emptyList()
        val request = TvReadRequest((allowed - context.packageName).toSet(), watch, previews)
        if (request.packages.isEmpty()) return@withContext emptyList()
        val raw = cache.get(request) {
            val reader = TvProgramReader(AndroidTvProgramSource(context.contentResolver), context.packageName)
            reader.read(request, System.currentTimeMillis())
        }
        currentCoroutineContext().ensureActive()
        if (!granted(context)) return@withContext emptyList()
        val permitted = visible(raw, request.packages, false, watch, previews)
        select(personalized(permitted, personal), System.currentTimeMillis())
    }

    /**
     * One app's own rows for Light's App rows (#62): Continue watching first, then each of its
     * channels under the channel's name, in the app's order.
     */
    suspend fun shelf(context: Context, pkg: String): List<Pair<String, List<LightMedia>>> = withContext(Dispatchers.IO) {
        if (pkg == context.packageName || !granted(context)) return@withContext emptyList()
        val request = TvReadRequest(setOf(pkg), watch = true, previews = true, allChannels = true)
        val raw = cache.get(request) {
            TvProgramReader(AndroidTvProgramSource(context.contentResolver), context.packageName).read(request, System.currentTimeMillis())
        }
        currentCoroutineContext().ensureActive()
        shelfGroups(raw.filter { it.pkg == pkg })
    }

    fun shelfGroups(items: List<LightMedia>, continueTitle: String = "Continue watching"): List<Pair<String, List<LightMedia>>> {
        val watching = items.filter { it.continuing }.sortedByDescending { it.engaged }.distinctBy { normalize(it.title) to it.description }
        val channels = LinkedHashMap<String, MutableList<LightMedia>>()
        items.filterNot { it.continuing }.forEach { channels.getOrPut(it.channel.trim().ifBlank { "Recommended" }) { ArrayList() }.add(it) }
        return buildList {
            if (watching.isNotEmpty()) add(continueTitle to watching.take(12))
            channels.forEach { (name, list) -> add(name to list.distinctBy { it.id }.take(12)) }
        }
    }

    /** Discard grants, selectors and foreign explicit destinations before opening a provider link. */
    fun episodeIntent(item: LightMedia): Intent? {
        if (item.pkg.isBlank()) return null
        val encoded = item.intent?.takeIf { it.isNotBlank() && it.length <= 8192 } ?: return null
        return runCatching {
            val intent = if (encoded.startsWith("intent:") || encoded.startsWith("#Intent"))
                Intent.parseUri(encoded, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, encoded.toUri())
            val scheme = intent.data?.scheme?.lowercase(Locale.ROOT)
            if (scheme in setOf("file", "javascript", "data")) return null
            if (intent.component?.packageName?.let { it != item.pkg } == true) return null
            if (intent.`package`?.let { it != item.pkg } == true) return null
            intent.apply {
                selector = null
                clipData = null
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                setPackage(item.pkg)
            }
        }.getOrNull()
    }
}
