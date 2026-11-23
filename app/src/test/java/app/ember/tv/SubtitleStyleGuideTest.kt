package app.ember.tv

import app.ember.tv.data.SubtitleConfig
import app.ember.tv.ui.SubtitleStyleGuide
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleStyleGuideTest {
    @Test fun theStyleRowSummarisesEveryStepOfTheGuide() {
        // #395: one row on the page, five steps in the guide.
        assertEquals(listOf("Size", "Outline", "Colour", "Position", "Font"), SubtitleStyleGuide.steps)
        val c = SubtitleConfig(size = "large", background = "box", color = "yellow", position = "high")
        assertEquals("Large · Dark box · Yellow · Higher position · Default", SubtitleStyleGuide.summary(c, "Default"))
    }
}
