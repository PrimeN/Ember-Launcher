package app.ember.tv.data

import kotlinx.serialization.Serializable

/**
 * A profile (Settings > Profiles): what Home, Discover, Search and the menu show for one person.
 * A Kids profile shows only the catalogs and apps you allow, no tracker rows or watchlist, and
 * asks for the PIN to open Settings or to switch to another profile.
 */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val kids: Boolean = false,
    /** Continue watching, Up next, watchlist, tracker rows and media server rows. */
    val personal: Boolean = true,
    val live: Boolean = true,
    /** Catalog keys allowed; null for all. */
    val catalogs: Set<String>? = null,
    /** App packages allowed; null for all. */
    val apps: Set<String>? = null,
    /** Index into [COLORS] for the round avatar. */
    val color: Int = 0,
    /** Parent-selected content locks; independent of catalog browse filters. */
    val lockedGenre: String? = null,
    val maxTvRating: String? = null,
    /** Legacy saves remain shared; newly created profiles explicitly select local. */
    val activityMode:String=LocalActivity.SHARED,
    val kidsSetupComplete:Boolean=false,
    val kidsSetupRequired:Boolean=false,
) {
    companion object {
        const val MAIN = "main"
        val COLORS = listOf(0xFFFFB547, 0xFF6CC0FF, 0xFF6FE0B0, 0xFFFF8AA8, 0xFFB39DFF, 0xFFFF8A65)
        /** Apps a Kids profile leaves out by default: settings, app stores, browsers, file managers, launchers. */
        val KIDS_BLOCKED = Regex("""settings|vending|store|browser|chrome|firefox|webview|files|filemanager|explorer|launcher|installer|downloader|terminal|adb""", RegexOption.IGNORE_CASE)
        val KIDS_WORDS = Regex("""kid|family|children|cartoon|animation|junior|disney""", RegexOption.IGNORE_CASE)
    }
}

object Pin {
    /** Stored as a salted SHA-256, never as the digits. */
    fun hash(pin: String, salt: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest("$salt:$pin".toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
