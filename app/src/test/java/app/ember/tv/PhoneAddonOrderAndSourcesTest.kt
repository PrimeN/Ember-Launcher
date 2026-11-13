package app.ember.tv

import app.ember.tv.phone.ContentPhone
import app.ember.tv.phone.PhoneContent
import app.ember.tv.phone.PhoneWidget
import org.junit.Assert.*
import org.junit.Test

/** #275: the phone shows the addon order and a widget's other catalogs. */
class PhoneAddonOrderAndSourcesTest {
    private val widget = PhoneWidget("w-1", "Mix", "row", "poster", 20, "Popular + 1 more", "20 titles", more = listOf("Top <rated>"))

    @Test fun addonsCanBeMovedFromThePhone() {
        val page = ContentPhone.render(PhoneContent(emptyList(), emptyList(), listOf("a1" to "1. Cinemeta", "a2" to "2. Other")), "lease", "tools")
        assertTrue(page.contains("<select name=change>"))
        assertTrue(page.contains("<option value=up>Move up</option>"))
        assertTrue(page.contains("<option value=down>Move down</option>"))
        assertTrue(page.indexOf("1. Cinemeta") < page.indexOf("2. Other"))
    }

    @Test fun widgetEditorListsAndRemovesExtraCatalogs() {
        val content = PhoneContent(listOf("s1" to "Trending"), emptyList(), emptyList(), widgets = listOf(widget))
        val edit = ContentPhone.widgetEditor(content, "lease", "w-1")
        assertTrue(edit.contains("name=\"dropMore:0\""))
        assertTrue("labels are escaped", edit.contains("Top &lt;rated&gt;"))
        assertTrue(edit.contains("name=addMore"))
        val full = widget.copy(more = List(app.ember.tv.data.HomeWidgets.MAX_SOURCES - 1) { "Extra $it" })
        assertFalse("no more room", ContentPhone.widgetEditor(content.copy(widgets = listOf(full)), "lease", "w-1").contains("name=addMore"))
    }
}
