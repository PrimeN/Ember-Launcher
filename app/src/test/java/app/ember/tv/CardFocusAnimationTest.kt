package app.ember.tv

import app.ember.tv.data.CardFocusStyle
import app.ember.tv.data.CustomPerformance
import app.ember.tv.ui.RenderOptions
import app.ember.tv.ui.deviceRenderOptions
import org.junit.Assert.*
import org.junit.Test

class CardFocusAnimationTest {
    @Test fun highPerformanceRetainsPosterAndAppRequestedZoom() {
        val options=RenderOptions()
        assertEquals(1.05f,options.cardFocusScale(1.05f,"subtle",false),0f)
        assertEquals(1.1f,options.cardFocusScale(1.1f,"subtle",false),0f)
        assertEquals(1.03f,options.cardFocusScale(1.1f,"lift",false),0f)
        assertEquals(1.1f,options.cardFocusScale(1.1f,"pulse",false),0f)
    }
    @Test fun lowModeKeepsLightweightZoomWithoutExpensivePulseOrGlow() {
        val options=RenderOptions(device="low")
        for(style in listOf("subtle","lift","glow","pulse")) {
            assertEquals(1.02f,options.cardFocusScale(1.1f,style,false),0f)
            assertFalse(CardFocusStyle.animated(style,true,false))
        }
    }
    @Test fun automaticSlowFrameReductionRetainsBasicFeedbackAndArtworkQuality() {
        val options=deviceRenderOptions("standard",true,"high","auto",false,512,4L*1024*1024*1024,observedSlow=true)
        assertFalse(options.animationsEnabled)
        assertEquals(1.02f,options.cardFocusScale(1.1f,"subtle",false),0f)
        assertEquals("high",options.quality)
        assertFalse(options.heroAnalysis)
        assertEquals(0,options.prefetchCount)
    }
    @Test fun explicitMotionOffAndAccessibilityStillTakePriority() {
        val automatic=RenderOptions(custom=CustomPerformance(animations=false),automaticEffectsReduced=true)
        for(options in listOf(RenderOptions(),RenderOptions(device="low"),automatic)) {
            assertEquals(1f,options.cardFocusScale(1.1f,"off",false),0f)
            assertEquals(1f,options.cardFocusScale(1.1f,"subtle",true),0f)
        }
        val manual=RenderOptions(device="custom",custom=CustomPerformance(animations=false))
        assertEquals(1f,manual.cardFocusScale(1.1f,"subtle",false),0f)
        assertFalse(manual.automaticEffectsReduced)
    }
    @Test fun scaleRequestsCannotShrinkOrProduceInvalidGeometry() {
        val options=RenderOptions()
        for(value in listOf(Float.NaN,Float.NEGATIVE_INFINITY,Float.POSITIVE_INFINITY,-1f,0f))
            assertEquals(1f,options.cardFocusScale(value,"subtle",false),0f)
        assertEquals(1.1f,options.cardFocusScale(100f,"subtle",false),0f)
    }
}
