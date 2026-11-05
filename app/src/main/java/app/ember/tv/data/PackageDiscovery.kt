package app.ember.tv.data

import android.content.Intent
import android.content.ActivityNotFoundException
import kotlinx.coroutines.CancellationException

/** A single vendor's broken package/resources must not discard every other installed app. */
internal fun <T, R> discoverPackageEntries(
    queries: List<() -> List<T>>,
    packageName: (T) -> String?,
    read: (T) -> R?,
    checkActive: () -> Unit = {},
): List<R> {
    val seen = HashSet<String>()
    val result = ArrayList<R>()
    var completed = 0
    var failure: Exception? = null
    for (query in queries) {
        checkActive()
        val candidates = try { query().also { completed++ } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = e; continue }
        for (candidate in candidates) {
            checkActive()
            try {
                val pkg = packageName(candidate)?.takeIf { it.isNotBlank() } ?: continue
                if (pkg in seen) continue
                val entry = read(candidate) ?: continue
                // A broken TV entry must not suppress a working normal-launcher entry for this app.
                result += entry
                seen += pkg
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { /* Keep the rest of this query and allow another entry to retry. */ }
        }
    }
    // A failed query is different from a successful query that found no installed apps.
    if (completed == 0) throw IllegalStateException("Package discovery is temporarily unavailable", failure)
    return result
}

internal fun <T> firstUsableMetadata(vararg choices: () -> T?): T? {
    for (read in choices) {
        val value = try { read() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        if (value != null) return value
    }
    return null
}

/** Expected Android launch denials are recoverable; programming errors and cancellation still escape. */
internal fun launchInstalledApp(start: () -> Unit, unavailable: () -> Unit): Boolean {
    try { start(); return true }
    catch (e: RuntimeException) {
        if (e !is ActivityNotFoundException && e !is SecurityException) throw e
        unavailable()
        return false
    }
}

internal fun appDiscoveryFresh(last: Long?, now: Long): Boolean = last != null && now - last in 0L until 30_000L

/** An update temporarily removes its old package; refresh once the replacement has been added. */
internal fun appInventoryChanged(action: String?, replacing: Boolean): Boolean = when (action) {
    Intent.ACTION_PACKAGE_REMOVED -> !replacing
    Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_CHANGED -> true
    else -> false
}
