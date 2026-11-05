package app.ember.tv.data

/** Profile permission is distinct from hiding an app on either launcher surface. */
class AppAccess(
    apps: List<AppEntry>, allowedPackages: Set<String>?,
    private val homeHidden: Set<String>, private val pageHidden: Set<String>,
) {
    private val permitted = apps.filter { allowedPackages == null || it.pkg in allowedPackages }
    /** Home leaves out apps removed from Home and hidden apps; hidden apps stay launchable from "show hidden". */
    val home: List<AppEntry> get() = permitted.filter { it.pkg !in homeHidden && it.pkg !in pageHidden }
    val hiddenOnPage: Int get() = permitted.count { it.pkg in pageHidden }
    fun page(showHidden: Boolean): List<AppEntry> = permitted.filter { showHidden || it.pkg !in pageHidden }
    fun canLaunch(pkg: String): Boolean = permitted.any { it.pkg == pkg }
}
