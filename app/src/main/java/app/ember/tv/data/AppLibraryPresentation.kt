package app.ember.tv.data

import java.util.Locale

enum class AppLibrarySort(val label: String) { CUSTOM("Custom order"), NAME("Name A–Z"), REVERSE_NAME("Name Z–A") }

/** Sorting the library is a view choice; it never rewrites the user's saved custom app order. */
object AppLibraryPresentation {
    fun items(permitted: List<AppEntry>, query: String, sort: AppLibrarySort): List<AppEntry> {
        val term = query.trim()
        val matches = permitted.distinctBy { it.pkg }.filter {
            term.isBlank() || it.label.contains(term, ignoreCase = true) || it.pkg.contains(term, ignoreCase = true)
        }
        val names = compareBy<AppEntry> { it.label.lowercase(Locale.ROOT) }.thenBy { it.pkg }
        return when (sort) {
            AppLibrarySort.CUSTOM -> matches
            AppLibrarySort.NAME -> matches.sortedWith(names)
            AppLibrarySort.REVERSE_NAME -> matches.sortedWith(names.reversed())
        }
    }
}
