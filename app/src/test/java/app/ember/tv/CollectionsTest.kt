package app.ember.tv

import app.ember.tv.data.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CollectionsTest {
    // The shape of a Collections export (older "catalogSources" folders and newer "sources").
    private val export = """
        [
          {"id":"c1","title":"Streaming Services","pinToTop":true,"showAllTab":true,"viewMode":"FOLLOW_LAYOUT","focusGlowEnabled":true,
           "folders":[
             {"id":"f1","title":"Netflix","tileShape":"LANDSCAPE","hideTitle":false,"coverImageUrl":"https://example.com/n.gif",
              "catalogSources":[{"addonId":"org.example.addon","catalogId":"netflix_movies","type":"movie"}]},
             {"id":"f2","title":"Studio","tileShape":"wide",
              "sources":[{"provider":"tmdb","tmdbSourceType":"COMPANY","title":"A24","tmdbId":41077,"mediaType":"MOVIE","filters":{"voteCountGte":50}}]}
           ]}
        ]
    """.trimIndent()

    @Test
    fun parsesCollectionExport() {
        val list = Collections.parse(export)!!
        assertEquals(1, list.size)
        val c = list[0]
        assertEquals(true, c.pinToTop)
        assertEquals(listOf("f1", "f2"), c.folders.map { it.id })
        assertEquals("wide", c.folders[0].shape)
        assertEquals("wide", c.folders[1].shape)
        assertEquals("org.example.addon", c.folders[0].allSources[0].addonId)
        assertEquals("tmdb", c.folders[1].allSources[0].provider)
        assertEquals(41077, c.folders[1].allSources[0].tmdbId)
    }

    @Test
    fun singleCollectionAndJunk() {
        assertEquals(1, Collections.parse("""{"id":"x","title":"X","folders":[{"id":"a","title":"A","catalogSources":[{"addonId":"a","catalogId":"b","type":"movie"}]}]}""")!!.size)
        assertNull(Collections.parse("not json"))
        assertNull(Collections.parse("{\"filters\":[]}"))
    }
}
