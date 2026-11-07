package app.ember.tv.tracker

private val IMDB = Regex("^tt\\d+$")

/**
 * Keys in the same shape as WatchedKeys: "m:" for movies or "s:" for shows, then "i:" + IMDb id
 * or "t:" + TMDB id, and "@season.episode" for one episode (#83).
 */
internal fun watchedKeys(movie: Boolean, imdb: String?, tmdb: Long?, season: Int?, episode: Int?): List<String> {
    val kind = if (movie) "m" else "s"
    val suffix = if (!movie && season != null && episode != null) "@$season.$episode" else ""
    return listOfNotNull(
        imdb?.trim()?.takeIf { IMDB.matches(it) }?.let { "$kind:i:$it$suffix" },
        tmdb?.takeIf { it > 0 }?.let { "$kind:t:$it$suffix" },
    )
}

/** What your trackers say you have watched, kept on the TV between starts (#83). One key per line. */
internal class TrackerWatchedFile(private val file: java.io.File) {
    fun read(): Set<String> = runCatching { if (file.exists()) file.readLines().filterTo(HashSet()) { it.isNotBlank() } else emptySet() }
        .getOrDefault(emptySet())

    fun write(keys: Set<String>) {
        runCatching {
            val tmp = java.io.File(file.path + ".tmp")
            tmp.writeText(keys.joinToString("\n"))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    fun clear() { file.delete() }
}

/** Watched keys with the activity stamp they were read at (first line "#stamp"). */
internal class StampedKeysFile(private val file: java.io.File) {
    fun read(): Pair<String, Set<String>>? = runCatching {
        if (!file.exists()) return null
        val lines = file.readLines()
        val first = lines.firstOrNull() ?: return null
        if (!first.startsWith("#")) return null
        first.removePrefix("#") to lines.drop(1).filterTo(HashSet()) { it.isNotBlank() }
    }.getOrNull()

    fun write(stamp: String, keys: Set<String>) {
        runCatching {
            val tmp = java.io.File(file.path + ".tmp")
            tmp.writeText("#" + stamp + "\n" + keys.joinToString("\n"))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    fun clear() { file.delete() }
}
