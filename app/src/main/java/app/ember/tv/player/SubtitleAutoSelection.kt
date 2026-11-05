package app.ember.tv.player

/** A manual choice wins over delayed provider results until a new video starts. */
internal class SubtitleAutoSelection {
    private var done = false
    fun reset() { done = false }
    fun manual() { done = true }
    fun claim(searched: Boolean, ready: Boolean): Boolean {
        if (done || !searched || !ready) return false
        done = true
        return true
    }
}
