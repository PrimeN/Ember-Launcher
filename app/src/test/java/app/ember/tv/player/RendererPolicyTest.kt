package app.ember.tv.player

import org.junit.Assert.*
import org.junit.Test

/** Compatibility playback starts on gpu-next and falls back to gpu at most once. */
class RendererPolicyTest {
    @Test fun gpuNextIsTheDefaultAndStableStaysExplicit() {
        assertEquals("gpu-next", app.ember.tv.player.PlaybackConfig().compatibilityRenderer)
        assertEquals("gpu-next", RendererPolicy.initial("auto"))
        assertEquals("gpu-next", RendererPolicy.initial("future-or-invalid"))
        assertEquals("gpu", RendererPolicy.initial("gpu"))
    }

    @Test fun aRendererThatCannotStartRetriesOnceOnStable() {
        val state = RendererSession(RendererPolicy.initial("gpu-next")); val calls = mutableListOf<String>()
        state.initialize({ mode -> calls += mode; if (mode == "gpu-next") throw IllegalStateException("Unavailable") }, { calls += "recreate" })
        assertEquals(listOf("gpu-next", "recreate", "gpu"), calls); assertTrue(state.recovered); assertFalse(state.fallback())
        state.select("gpu-next"); assertFalse(state.recovered); assertTrue(state.fallback())
        try { RendererSession("gpu").initialize({ throw IllegalStateException("Unavailable") }, { fail("No retry for stable") }); fail("Expected failure") } catch (_: IllegalStateException) {}
    }

    @Test fun aDriverFailureAlsoFallsBackButAudioOnlyNeverDoes() {
        val calls = mutableListOf<String>(); val state = RendererSession("gpu-next")
        state.initialize({ r -> calls += r; if (r == "gpu-next") throw UnsatisfiedLinkError("Driver unavailable") }, { calls += "recreate" })
        assertEquals(listOf("gpu-next", "recreate", "gpu"), calls)
        val audio = RendererSession("gpu-next", videoEnabled = false)
        assertFalse(audio.fallback()); assertFalse(audio.recovered)
    }
}
