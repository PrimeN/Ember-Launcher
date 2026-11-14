package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.remote.RemoteSources
import org.junit.Assert.*
import org.junit.Test

class RemoteSourcesTest {
    private fun stream(name: String) = StreamOption("Addon", name, name, "https://media.example/$name?token=private", null, null,
        "FHD", "", "", "", "", "", null, null, true)
    private fun details(vararg streams: StreamOption) = DetailsUi(Meta("tt1", "movie", "Film"), streams = streams.toList())

    @Test fun aSourceKeepsItsIdentityWhenServerAndAddonResultsChangeOrder() {
        val choices = RemoteSources(); val a = stream("a"); val b = stream("b"); val server = stream("server")
        val old = details(a, b)
        val ref = choices.choices("main", old).first { it.source == b }.ref
        val current = old.copy(streams = listOf(b, a), serverStreams = listOf(server))
        assertEquals(b, choices.resolve("main", current, ref))
        assertEquals(ref, choices.choices("main", current).first { it.source == b }.ref)
        assertFalse(ref.contains("private")); assertFalse(ref.contains("media.example"))
    }

    @Test fun referencesCannotSelectAnotherTitleEpisodeOrProfile() {
        val choices = RemoteSources(); val source = stream("one"); val original = details(source)
        val ref = choices.choices("main", original).single().ref
        assertNull(choices.resolve("main", original.copy(meta = original.meta.copy(id = "tt2")), ref))
        val fresh = choices.choices("main", original).single().ref
        assertNotEquals(ref, fresh); assertNull(choices.resolve("child", original, fresh))
        val e1 = Episode("show:1:1", 1, 1, "First", null, null)
        val series = original.copy(meta = Meta("show", "series", "Show"), episode = e1)
        val episodeRef = choices.choices("main", series).single().ref
        assertNull(choices.resolve("main", series.copy(episode = e1.copy(id = "show:1:2", episode = 2)), episodeRef))
    }

    @Test fun removedOrReplacedSourcesAndClosedPagesRejectOldReferences() {
        val choices = RemoteSources(); val a = stream("a"); val b = stream("b"); val original = details(a, b)
        val ref = choices.choices("main", original).first().ref
        assertNull(choices.resolve("main", details(b), ref))
        assertNull(choices.resolve("main", original, ref))
        val fresh = choices.choices("main", original).first().ref
        assertNull(choices.resolve("main", null, fresh))
        assertNull(choices.resolve("main", original, fresh))
        assertNull(choices.resolve("main", original, "0"))
        assertNull(choices.resolve("main", original, a.url!!))
    }
}
