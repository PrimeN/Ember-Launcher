package app.ember.tv.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Coalesces automatic refresh groups. Explicit user actions bypass this queue. */
internal class UiRefreshQueue(
    private val scope: CoroutineScope,
    private val lowPower: () -> Boolean,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val firstPresentation = CompletableDeferred<Unit>()
    private val active = MutableStateFlow(false)
    private val running = MutableStateFlow(0)
    // submit/input/visibility calls belong to the UI thread, as do ViewModel refresh methods.
    private val jobs = mutableMapOf<String, Job>()
    private var lastInput = now()
    private var visibleGeneration = 0

    fun presented() { firstPresentation.complete(Unit) }
    fun setActive(value: Boolean) {
        if (value && !active.value) { visibleGeneration++; interaction() }
        active.value = value
    }
    fun interaction() { lastInput = now() }

    suspend fun awaitPresentation() {
        firstPresentation.await()
        active.first { it }
    }

    fun submit(key: String, waitForIdle: Boolean = true, refresh: suspend () -> Unit): Job {
        jobs[key]?.takeIf { it.isActive }?.let { return it }
        return scope.launch(start = CoroutineStart.LAZY) {
            awaitPresentation()
            var deadline = now() + 10_000L // Continuous input cannot indefinitely prevent freshness.
            var generation = visibleGeneration
            suspend fun awaitTurn() {
                active.first { it }
                if (!waitForIdle) return
                while (true) {
                    active.first { it }
                    // Time spent in playback/another app is not part of the input budget.
                    if (generation != visibleGeneration) {
                        generation = visibleGeneration
                        deadline = now() + 10_000L
                    }
                    val time = now()
                    val remaining = minOf((if (lowPower()) 700L else 250L) - (time - lastInput), deadline - time)
                    if (remaining <= 0) return
                    pause(remaining)
                }
            }
            awaitTurn()
            while (true) {
                val count = running.value
                if (count < (if (lowPower()) 1 else 2)) {
                    if (running.compareAndSet(count, count + 1)) break
                    continue
                }
                running.first { it != count }
            }
            try { awaitTurn(); refresh() }
            finally { running.update { it - 1 } }
        }.also { jobs[key] = it; it.start() }
    }
}
