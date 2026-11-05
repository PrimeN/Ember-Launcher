package app.ember.tv

import app.ember.tv.data.HeroArtwork
import app.ember.tv.data.Meta
import app.ember.tv.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class PolishEnhancementsTest {
    private val high=RenderOptions(device="high",quality="high")
    private val durations=listOf(TvTokens.Motion.Image,TvTokens.Motion.Page,TvTokens.Motion.Panel,TvTokens.Motion.PanelExit,TvTokens.Motion.Hero,TvTokens.Motion.Text,TvTokens.Motion.Rail)
    @Test fun lowAndEconomyNeverRunNewCosmeticAnimations() {
        listOf(RenderOptions(device="low"),RenderOptions(quality="economy")).forEach {options->
            durations.forEach {assertEquals(0,PolishMotion.duration(it,options,false))}
        }
    }
    @Test fun reducedMotionWinsOverHighQualityAndDeviceSettings() {
        durations.forEach {assertEquals(0,PolishMotion.duration(it,high,true))}
    }
    @Test fun normalTransitionsStayShortAndUseTheSameRolesInBalancedAndHigh() {
        durations.forEach {duration->
            assertTrue(duration in 100..240)
            assertEquals(duration,PolishMotion.duration(duration,RenderOptions(),false))
            assertEquals(duration,PolishMotion.duration(duration,high,false))
        }
        assertTrue(TvTokens.Motion.PanelExit<=TvTokens.Motion.Panel)
        assertTrue(TvTokens.Motion.Image<=TvTokens.Motion.Page)
    }
    @Test fun negativeTransitionTimesCannotReachAnimators() {
        assertEquals(0,PolishMotion.duration(-1,high,false))
    }
    @Test fun nativeWindowAnimationTimesMatchComposePanelTokens() {
        val roots=listOf(File("src/main/res"),File("app/src/main/res"))
        val root=roots.firstOrNull {it.isDirectory} ?: error("Android resources missing")
        val factory=DocumentBuilderFactory.newInstance().apply {isNamespaceAware=true}
        fun duration(name:String)=factory.newDocumentBuilder().parse(File(root,"anim/$name.xml"))
            .documentElement.getAttributeNS("http://schemas.android.com/apk/res/android","duration").toInt()
        assertEquals(TvTokens.Motion.Panel,duration("ember_panel_enter"))
        assertEquals(TvTokens.Motion.PanelExit,duration("ember_panel_exit"))
    }
    @Test fun sharedRolesRetainCompactReadableLabelsAndAVisibleHeadingHierarchy() {
        assertTrue(TvTokens.Type.Page>TvTokens.Type.Dialog)
        assertTrue(TvTokens.Type.Dialog>TvTokens.Type.Section)
        assertTrue(TvTokens.Type.Section>TvTokens.Type.Label)
        assertTrue(TvTokens.Type.Menu>=14);assertTrue(TvTokens.Type.NativeButton>=13f)
        assertTrue(TvTokens.Space.Page>TvTokens.Space.XXL)
        assertTrue(TvTokens.Shape.Panel>TvTokens.Shape.Card)
    }
    @Test fun missingArtworkCannotReuseAnOldSuccessState() {
        assertEquals(ArtworkPhase.MISSING,artworkPhase(false,true,false))
        assertEquals(ArtworkPhase.MISSING,artworkPhase(false,false,false))
    }
    @Test fun failedArtworkStaysInTheFallbackEvenAfterAnEarlierSuccess() {
        assertEquals(ArtworkPhase.MISSING,artworkPhase(true,true,true))
        assertEquals(ArtworkPhase.MISSING,artworkPhase(true,false,true))
    }
    @Test fun presentArtworkReservesAPlaceholderUntilDecoded() {
        assertEquals(ArtworkPhase.LOADING,artworkPhase(true,false,false))
        assertEquals(ArtworkPhase.READY,artworkPhase(true,true,false))
    }
    @Test fun suppliedFocalPointsAreClampedAndIncompleteOrNonFiniteValuesIgnored() {
        assertEquals(HeroFocus(0f,1f),HeroFraming.supplied(-3f,2f))
        assertNull(HeroFraming.supplied(null,.5f));assertNull(HeroFraming.supplied(.5f,null))
        assertNull(HeroFraming.supplied(Float.NaN,.5f));assertNull(HeroFraming.supplied(.5f,Float.POSITIVE_INFINITY))
    }
    @Test fun portraitFallbacksFitWithoutMagnifyingOrStretchingIntoAWholeBanner() {
        val frame=HeroFraming.plan(600,900,1920f,840f)
        assertTrue(frame.fit);assertEquals(1f,frame.xBias,0f)
    }
    @Test fun veryWideArtworkFitsRatherThanDiscardingMostOfTheComposition() {
        assertTrue(HeroFraming.plan(3840,1080,1920f,1080f).fit)
        assertTrue(HeroFraming.plan(4000,800,1920f,840f).fit)
    }
    @Test fun ordinaryHeroLandscapesCanCropWithinTheRetentionBudget() {
        assertFalse(HeroFraming.plan(1920,1080,1920f,840f).fit)
        assertFalse(HeroFraming.plan(1920,1080,1920f,1080f).fit)
    }
    @Test fun horizontalFocalPointsMoveTowardTheTitleSafeSideWhenThereIsRoom() {
        val focus=HeroFocus(.65f,.32f)
        val frame=HeroFraming.plan(2200,1000,1920f,1080f,focus)
        assertFalse(frame.fit)
        val scale=1080f/1000
        val overflow=2200*scale-1920
        val left=(frame.xBias+1)/2*overflow
        assertEquals(.72f,(focus.x*2200*scale-left)/1920,.001f)
    }
    @Test fun edgeSubjectsStayWithinTheImageInsteadOfExposingBlankPixels() {
        val left=HeroFraming.plan(2200,1000,1920f,1080f,HeroFocus(0f,.3f))
        val right=HeroFraming.plan(2200,1000,1920f,1080f,HeroFocus(1f,.3f))
        assertEquals(-1f,left.xBias,0f);assertEquals(1f,right.xBias,0f)
    }
    @Test fun verticalFramingProtectsTheSelectedRegionBelowTheTopEdge() {
        val point=HeroFocus(.65f,.45f)
        val frame=HeroFraming.plan(1920,1080,1920f,840f,point)
        assertFalse(frame.fit)
        val top=(frame.yBias+1)/2*(1080-840)
        assertEquals(.40f,(point.y*1080-top)/840,.001f)
    }
    @Test fun glassProjectionUsesTheSameFocalPlacementAndAspectRatioAsTheHero() {
        val point=HeroFocus(.65f,.32f)
        val projection=HeroFraming.projection(2200,1000,1920f,1080f,point)!!
        assertEquals(.72f,(projection.left+point.x*projection.width)/1920,.001f)
        assertEquals(2.2f,projection.width/projection.height,.001f)
        val portrait=HeroFraming.projection(600,900,1920f,840f)!!
        assertEquals(1920f,portrait.left+portrait.width,.001f)
        assertTrue(portrait.left>0)
    }
    @Test fun glassProjectionRejectsInvalidBoundsBeforeDrawing() {
        assertNull(HeroFraming.projection(0,0,1920f,840f))
        assertNull(HeroFraming.projection(1920,1080,0f,840f))
        assertNull(HeroFraming.projection(1920,1080,Float.NaN,840f))
    }
    @Test fun glassTracksTheActualNestedLibraryOrDetailsViewportRatherThanAWindowFraction() {
        val view=HeroViewport(84f,100f,1836f,760f)
        val local=HeroFraming.projection(1920,1080,view.width,view.height)!!
        val glass=HeroFraming.glassProjection(1920,1080,view)!!
        assertEquals(local.left+view.left,glass.left,.001f)
        assertEquals(local.top+view.top,glass.top,.001f)
        assertEquals(local.width,glass.width,.001f);assertEquals(local.height,glass.height,.001f)
        assertNull(HeroFraming.glassProjection(1920,1080,view.copy(top=Float.NaN)))
    }
    @Test fun invalidBoundsAndNonFinitePointsNeverReachRenderingAsNan() {
        listOf(0 to 0,-1 to 1080,1920 to -1).forEach {(w,h)->assertTrue(HeroFraming.plan(w,h,1920f,840f).fit)}
        assertTrue(HeroFraming.plan(1920,1080,Float.POSITIVE_INFINITY,840f).fit)
        assertTrue(HeroFraming.plan(1920,1080,1920f,Float.NaN).fit)
        val frame=HeroFraming.plan(2200,1080,1920f,840f,HeroFocus(Float.NaN,Float.POSITIVE_INFINITY))
        assertTrue(frame.xBias.isFinite());assertTrue(frame.yBias.isFinite())
    }
    @Test fun allCropPlansRespectImageBoundsAndTheRetentionLimit() {
        for(w in listOf(300,640,1920,3840))for(h in listOf(400,720,1080,2160))for(x in listOf(0f,.5f,1f)) {
            val frame=HeroFraming.plan(w,h,1920f,840f,HeroFocus(x,.3f))
            assertTrue(frame.xBias in -1f..1f);assertTrue(frame.yBias in -1f..1f)
            if(!frame.fit) {
                val scale=maxOf(1920f/w,840f/h)
                assertTrue(minOf(1920/(w*scale),840/(h*scale))>=.74f)
            }
        }
    }
    @Test fun flatArtworkRetainsConservativeFramingWithoutInventingASubject() {
        assertEquals(HeroFocus(),HeroFraming.estimate(IntArray(64*36){0xFF777777.toInt()},64,36))
    }
    @Test fun detailEstimateRespondsToTheImageAndStaysConservative() {
        fun image(left:Int)=IntArray(64*36){index->
            val x=index%64;val y=index/64
            if(x in left..left+10 && y in 6..16 && (x+y)%2==0)0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        }
        val left=HeroFraming.estimate(image(6),64,36)
        val right=HeroFraming.estimate(image(46),64,36)
        assertTrue(left.x<right.x)
        listOf(left,right).forEach {assertTrue(it.x in .25f.. .85f);assertTrue(it.y in .18f.. .55f)}
    }
    @Test fun invalidPixelBuffersAreRejectedBeforeIndexing() {
        try {HeroFraming.estimate(IntArray(10),64,36);fail("Invalid buffer accepted")}catch(_:IllegalArgumentException) { }
        try {HeroFraming.estimate(IntArray(1),1,1);fail("Invalid dimensions accepted")}catch(_:IllegalArgumentException) { }
    }
    @Test fun aPublicArtworkReplacementDoesNotReuseAnotherImagesFocalPoint() {
        val meta=Meta(id="tt1234",type="movie",background="https://fixture.example/original.jpg",backgroundFocusX=.2f,backgroundFocusY=.4f)
        val replaced=HeroArtwork(backgrounds=listOf("https://fixture.example/other.jpg")).applyTo(meta)
        assertNull(replaced.backgroundFocusX);assertNull(replaced.backgroundFocusY)
        val same=HeroArtwork(backgrounds=listOf(meta.background!!)).applyTo(meta)
        assertEquals(meta.backgroundFocusX,same.backgroundFocusX);assertEquals(meta.backgroundFocusY,same.backgroundFocusY)
    }
    @Test fun nativeFirstArtworkKeepsItsOwnCompositionPoint() {
        val meta=Meta(id="movie",type="movie",serverRef="plex|fixture|1",background="https://fixture.example/native.jpg",backgroundFocusX=.2f,backgroundFocusY=.4f)
        val decorated=HeroArtwork(backgrounds=listOf("https://fixture.example/public.jpg")).applyTo(meta)
        assertEquals(meta.background,decorated.background);assertEquals(meta.backgroundFocusX,decorated.backgroundFocusX)
    }
    @Test fun lowUsesSuppliedMetadataWithoutPixelAnalysisAndKeepsSafetyFields()=runBlocking {
        val meta=Meta(id="tt1234",type="movie",backgroundFocusX=.3f,backgroundFocusY=.4f,ageRating="PG",genres=listOf("Animation"),adult=false)
        val art=framedArtwork(ReadyArtwork(meta,null),RenderOptions(device="low"))
        assertEquals(HeroFocus(.3f,.4f),art.focus);assertEquals(meta,art.meta)
        assertEquals(ReadyArtwork(meta.copy(backgroundFocusX=null,backgroundFocusY=null),null),
            framedArtwork(ReadyArtwork(meta.copy(backgroundFocusX=null,backgroundFocusY=null),null),RenderOptions(device="low")))
    }
    @Test fun focalMetadataSurvivesSerializingWithoutChangingContentOrPlaybackIdentity() {
        val meta=Meta(id="fixture",type="movie",name="Film",serverRef="plex|fixture|1",backgroundFocusX=.3f,backgroundFocusY=.4f,ageRating="PG",watched=true)
        assertEquals(meta,Json.decodeFromString<Meta>(Json.encodeToString(meta)))
    }
    @Test fun enlargedTextKeepsItsExistingHeroWidthWhileStandardTextReservesMoreArtSpace() {
        assertTrue(HeroLayout.textFraction(1f)<.62f)
        assertEquals(.8f,HeroLayout.textFraction(1.5f),0f)
        assertTrue(HeroLayout.height(500f,1.5f)>=HeroLayout.height(500f,1f))
    }
}
