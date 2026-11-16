package app.ember.tv.home

import app.ember.tv.data.AppEntry

/** Your own names for apps on Home: one line, without control characters. */
internal object AppNames {
    const val MAX = 300
    const val LENGTH = 40
    fun clean(name: String) = name.filterNot { it.isISOControl() }.trim().replace(Regex("\\s+"), " ").take(LENGTH)
    fun apply(apps: List<AppEntry>, names: Map<String, String>): List<AppEntry> =
        if (names.isEmpty()) apps else apps.map { app -> names[app.pkg]?.let { app.copy(label = it) } ?: app }
}
