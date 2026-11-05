package app.ember.tv.data

/** Stable group IDs make category choices portable without modifying the bundled/custom packs. */
internal object DetailedBadgeCategories {
    val groups = listOf(
        "gq" to "Release quality", "gr" to "Resolution", "gv" to "Visual",
        "ga" to "Audio", "gc" to "Channels", "gs" to "Streaming",
        "gl" to "Languages", "gvc" to "Video codec", "gst" to "Special tags",
    )

    fun toggle(hidden: Set<String>, id: String): Set<String> = when {
        groups.none { it.first == id } -> hidden
        id in hidden -> hidden - id
        else -> hidden + id
    }

    fun filter(mode: String, hidden: Set<String>, badges: List<BadgeFilter>): List<BadgeFilter> =
        if (mode != Badges.MODERN || hidden.isEmpty()) badges else badges.filter { it.groupId !in hidden }

    /** Native fallback chips must not reintroduce categories hidden in Detailed. More keeps full details. */
    fun textTags(s: StreamOption, hidden: Set<String>, pictureSound: Boolean = true, languageLimit: Int = Int.MAX_VALUE,
        badges: List<BadgeFilter> = emptyList()): List<String> {
        val covered = badges.mapNotNull { it.groupId }.toMutableSet().apply {
            // These Visual badges also carry audio; do not repeat that audio as a Basic chip.
            if (badges.any { it.groupId == "gv" && it.name in combinedAudioDv }) add("ga")
        }
        val languageBadges = badges.filter { it.groupId == "gl" }.map { languageKey(it.name) }.toSet()
        val languages = s.info?.languages.orEmpty().filter { languageKey(it) !in languageBadges }
        val languageText = if (languages.size > languageLimit) languages.take(languageLimit).joinToString(" · ") + " +${languages.size - languageLimit}"
            else languages.joinToString(" · ")
        return listOfNotNull(
            s.hdr.takeIf { pictureSound && "gv" !in hidden && "gv" !in covered },
            s.codec.takeIf { "gvc" !in hidden && "gvc" !in covered },
            // The fallback audio string can combine codec and channel count; the separate enabled image category remains visible.
            s.audio.takeIf { pictureSound && "ga" !in hidden && "gc" !in hidden && "ga" !in covered && "gc" !in covered },
            languageText.takeIf { "gl" !in hidden }, s.source.takeIf { "gq" !in hidden && "gq" !in covered },
            s.seeders?.let { "$it seeders" }, s.releaseGroup,
        ).filter(String::isNotBlank) + if (pictureSound && "ga" in covered && "gc" !in covered && "gc" !in hidden)
            StreamInfoParser.audioChannels(s.audio) else if (pictureSound && "gc" in covered && "ga" !in covered && "ga" !in hidden)
            StreamInfoParser.audioTags(s.audio) else emptyList()
    }

    private val combinedAudioDv = setOf("Atmos+DV", "TrueHD+DV", "DD++DV", "DD+DV")

    /** Artwork uses ISO labels while structured metadata commonly uses full names or locale tags. */
    private val languageAliases = listOf(
        "english eng en", "french fre fra fr", "german ger deu de", "italian ita it",
        "spanish spa esp es castellano", "latino lat", "portuguese por bra pt", "japanese jpn ja",
        "korean kor ko", "chinese chi zho zh mandarin cantonese", "russian rus ru", "arabic ara ar",
        "hindi hin hi", "turkish tur tr", "polish pol pl", "ukrainian ukr uk", "indonesian ind id",
        "thai tha th", "vietnamese vie vi", "greek gre ell el", "czech cze ces cs", "slovak svk slk sk",
        "slovenian slo slv sl", "swedish swe sv", "multi mul dual dualaudio"
    ).flatMap { group -> val words = group.split(' '); words.map { it to words.first() } }.toMap()
    private fun languageKey(value: String): String {
        val normalized = value.lowercase(java.util.Locale.ROOT).trim()
        return languageAliases[normalized] ?: languageAliases[normalized.substringBefore('-').substringBefore('_').substringBefore(' ')] ?: normalized
    }
}
