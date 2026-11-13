package app.ember.tv

import app.ember.tv.data.AddonCast
import app.ember.tv.data.Person
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** #265: the addon's cast keeps faces. */
class AddonCastTest {
    @Test fun richCastFromTheAddonKeepsPhotosAndCharacters() {
        val meta = Json.parseToJsonElement("""{"cast":["Plain Name"],"app_extras":{"cast":[{"name":"Ann Actor","character":"Hero","photo":"https://img/ann.jpg"},
            {"name":"Bo Actor","character":"Villain","profile_path":"/bo.jpg"}],"directors":[{"name":"Di Rector","photo":"https://img/di.jpg"}]}}""")
        val people = AddonCast.people(meta)
        assertEquals(listOf("Di Rector", "Ann Actor", "Bo Actor"), people.map { it.name })
        assertEquals("Hero", people[1].role); assertEquals("https://image.tmdb.org/t/p/w185/bo.jpg", people[2].photo)
        assertTrue(people[0].isCrew)
        assertTrue("plain names only: nothing rich to use", AddonCast.people(Json.parseToJsonElement("""{"cast":["A","B"]}""")).isEmpty())
    }

    @Test fun tmdbFacesFillTheAddonsCastByName() {
        val addon = listOf(Person("Ann Actor", null), Person("Zed Unknown", null), Person("Di Rector", "Director", isCrew = true))
        val tmdb = listOf(Person("ann actor", "Hero", "https://tmdb/ann.jpg", tmdbId = 7), Person("Di Rector", "Director", "https://tmdb/di.jpg", isCrew = true))
        val merged = AddonCast.combine(addon, tmdb)
        assertEquals(listOf("Ann Actor", "Zed Unknown", "Di Rector"), merged.map { it.name })
        assertEquals("https://tmdb/ann.jpg", merged[0].photo); assertEquals("Hero", merged[0].role); assertEquals(7L, merged[0].tmdbId)
        assertNull(merged[1].photo); assertEquals("https://tmdb/di.jpg", merged[2].photo)
        val own = listOf(Person("Ann Actor", "Lead", "https://addon/ann.jpg"))
        assertEquals("the addon's own photo stays", "https://addon/ann.jpg", AddonCast.combine(own, tmdb).first().photo)
    }

    @Test fun aShortAddonCastIsFollowedByTheRestOfTmdbs() {
        // #268: three names from the addon, the full cast from TMDB after them.
        val addon = listOf(Person("A One", null), Person("B Two", null), Person("C Three", null))
        val tmdb = (listOf("B Two", "A One", "C Three") + (4..12).map { "Extra $it" }).map { Person(it, "Role", "https://tmdb/$it.jpg") }
        val all = AddonCast.combine(addon, tmdb)
        assertEquals(12, all.size)
        assertEquals(listOf("A One", "B Two", "C Three", "Extra 4"), all.take(4).map { it.name })
        assertTrue(AddonCast.wantsTmdb(addon.map { it.copy(photo = "x") }))
        assertFalse(AddonCast.wantsTmdb((1..8).map { Person("P$it", "R", "https://p/$it.jpg") }))
        assertTrue(AddonCast.wantsTmdb(null))
    }
}
