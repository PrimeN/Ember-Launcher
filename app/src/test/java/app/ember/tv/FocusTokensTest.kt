package app.ember.tv

import app.ember.tv.ui.TvTokens
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** #35: focus growth and control corners come from TvTokens, not per-screen numbers. */
class FocusTokensTest {
    private val sources = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        .walkTopDown().filter { it.extension == "kt" }.toList()

    @Test fun focusLevelsAreOrdered() {
        val levels = listOf(TvTokens.Focus.None, TvTokens.Focus.Inline, TvTokens.Focus.Control, TvTokens.Focus.Card, TvTokens.Focus.Round)
        assertEquals(levels.sorted(), levels)
        assertEquals(1f, TvTokens.Focus.None)
    }

    @Test fun noRawFocusScaleOutsideTheTokens() {
        val raw = Regex("""focusedScale\s*[=:]\s*(Float\s*=\s*)?[0-9]""")
        val offenders = sources.filter { it.name != "TvTokens.kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line -> if (raw.containsMatchIn(line)) "${f.name}:${i + 1}" else null }
        }
        assertTrue("Use TvTokens.Focus: $offenders", offenders.isEmpty())
    }

    @Test fun focusableRadiiUseShapeTokens() {
        val offenders = sources.flatMap { f ->
            val text = f.readText()
            Regex("""\bFocusable\(""").findAll(text).mapNotNull { m ->
                var depth = 1; var j = m.range.last + 1
                while (j < text.length && depth > 0) { if (text[j] == '(') depth++ else if (text[j] == ')') depth--; j++ }
                if (Regex("""\bradius\s*=\s*\d""").containsMatchIn(text.substring(m.range.last + 1, j))) "${f.name}@${m.range.first}" else null
            }.toList()
        }
        assertTrue("Use TvTokens.Shape: $offenders", offenders.isEmpty())
    }

    /** #37: nothing on screen is smaller than 12 sp. */
    @Test fun noTextBelowTwelveSp() {
        val small = Regex("""fontSize\s*=\s*(?:if\s*\([^)]*\)\s*)?([0-9]|1[01])(\.[0-9]+)?\.sp""")
        val offenders = sources.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line -> if (small.containsMatchIn(line)) "${f.name}:${i + 1}" else null }
        }
        assertTrue("Use TvTokens.Type (12 sp or more): $offenders", offenders.isEmpty())
        assertTrue(TvTokens.Type.Caption >= 12 && TvTokens.Type.Detail >= 12)
    }
}
