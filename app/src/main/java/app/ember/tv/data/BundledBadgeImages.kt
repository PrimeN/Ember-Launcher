package app.ember.tv.data

/** Built-in images use explicit asset references; only PNGs inside badge folders are accepted. */
internal object BundledBadgeImages {
    const val SCHEME = "bundled:"

    fun path(url: String): String? = url.takeIf { it.startsWith(SCHEME) }
        ?.removePrefix(SCHEME)
        ?.takeIf { it.startsWith("badges/") && ".." !in it && '\\' !in it && it.endsWith(".png") }
}
