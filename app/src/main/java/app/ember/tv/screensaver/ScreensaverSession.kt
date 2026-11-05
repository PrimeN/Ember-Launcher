package app.ember.tv.screensaver

import kotlinx.coroutines.*

/** One cancellable slideshow job; stopped/late loaders cannot update a detached view. */
class ScreensaverSession(
    private val scope: CoroutineScope,
    private val load: suspend () -> List<Slide>,
    private val present: (Slide?) -> Unit,
    private val intervalMs: Long = 12_000,
    private val waitNext: suspend (Long) -> Unit = { delay(it) },
) {
    private var job: Job? = null
    fun start() {
        stop()
        job = scope.launch {
            var slides = emptyList<Slide>()
            var index = 0
            while (isActive) {
                if (index >= slides.size) {
                    slides = runCatching { load() }.getOrDefault(emptyList()).filter { it.image.isNotBlank() }.distinctBy { it.image }
                    ensureActive()
                    index = 0
                }
                present(slides.getOrNull(index++))
                waitNext(intervalMs)
            }
        }
    }
    fun stop() { job?.cancel(); job = null }
}

object ScreensaverSlides {
    fun select(sources: Set<String>, load: (String) -> List<Slide>): List<Slide> =
        listOf(ScreensaverFeed.HOME, ScreensaverFeed.ART, ScreensaverFeed.SPACE, ScreensaverFeed.PHOTOS).filter { it in sources }
            .flatMap { runCatching { load(it) }.getOrDefault(emptyList()) }
            .filter { it.image.isNotBlank() }.distinctBy { it.image }
}

/** A launch key-up must not dismiss the new window before it receives a fresh press. */
class ScreensaverExit(private val dismissKeys: Set<Int>) {
    private val down = mutableSetOf<Int>()
    fun handle(key: Int, isDown: Boolean, cancelled: Boolean = false): Boolean {
        if (key !in dismissKeys) return false
        if (isDown) { down += key; return false }
        return down.remove(key) && !cancelled
    }
}
