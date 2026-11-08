package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.state.LookState
import app.ember.tv.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class EmberXInterfaceTest {
    @get:Rule val temp=TemporaryFolder()
    @After fun resetUi(){Ui.emberX=false;Ui.light=false;Ui.day=false;Ui.oled=false}
    @Test fun newThemeIsAppendOnlyAndUsesAnExistingBrandAlias() {
        assertEquals(listOf("Ember","Shadow Ember","Blue ember","Green flame","Rose ember","Modern Ember"),Accents.take(6).map {it.first})
        assertEquals("Cinema red",Accents[ColorThemes.CINEMA_RED].first)
        assertEquals(6,ColorThemes.normalize(6));assertEquals(0,ColorThemes.artworkIndex(6))
        assertEquals(0,ColorThemes.normalize(7));assertEquals(2,ColorThemes.artworkIndex(5))
    }
    @Test fun structuredAddonSizeAppearsEvenWhenItsDisplayTextOmitsSize() {
        val source=StreamParser.parse("Addon",kotlinx.serialization.json.Json.parseToJsonElement(
            """{"url":"https://example.test/video","name":"Movie","streamData":{"size":2147483648}}"""))!!
        assertNull(source.sizeGb)
        assertEquals(2147483648L,source.info?.sizeBytes)
        val pills=emberXSourcePrimaryPills(source,"90 min")
        assertEquals("90 min",pills.first())
        assertTrue(pills.last().matches(Regex("2[.,]0 GB")))
        assertEquals(2,pills.size)
    }
    @Test fun serverSizeAndUnavailableValuesKeepTheSamePriorityWithoutPlaceholders() {
        val source=StreamParser.parse("Server",kotlinx.serialization.json.Json.parseToJsonElement(
            """{"url":"https://example.test/video","name":"Movie"}"""))!!.copy(serverRef="plex|server|item",sizeGb=5.0,info=null)
        assertEquals("120 min",emberXSourcePrimaryPills(source,"120 min").first())
        assertTrue(emberXSourcePrimaryPills(source,null).single().matches(Regex("5[.,]0 GB")))
        assertTrue(emberXSourcePrimaryPills(source.copy(sizeGb=null)," ").isEmpty())
    }
    private fun contrast(a:Color,b:Color):Float {val x=a.luminance();val y=b.luminance();return (maxOf(x,y)+.05f)/(minOf(x,y)+.05f)}
    @Test fun cinemaPaletteKeepsIndependentDayNightAndOledModesReadable() {
        for(day in listOf(false,true))for(oled in listOf(false,true)) {
            val scheme=emberColorScheme(ColorThemes.CINEMA_RED,day,oled)
            assertEquals(if(day)Color(0xFFEEF0F3) else if(oled)Color.Black else Color(0xFF0B0B0D),scheme.background)
            if(!day)assertEquals(Color(0xFFE50914),scheme.primary)
            assertTrue(contrast(scheme.onPrimary,scheme.primary)>=4.5f)
            assertTrue(contrast(scheme.onSurface,scheme.surface)>=7f)
            assertTrue(contrast(scheme.onSurfaceVariant,scheme.surfaceVariant)>=4.5f)
        }
    }
    @Test fun interfaceAndColourAreIndependentAndPreserveOtherChoices() {
        val prefs=Prefs(PersistenceContext(temp.newFolder()))
        prefs.uiStyle="light";prefs.theme="day";prefs.menuPosition="side";prefs.playbackConfig=prefs.playbackConfig.copy(playerLook="simple")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val look=LookState(prefs,scope)
            look.updateUiStyle(InterfaceStyles.EMBER_X)
            assertTrue(Ui.emberX);assertFalse(Ui.light);assertFalse(Ui.isDay)
            assertEquals(InterfaceStyles.EMBER_X,prefs.uiStyle);assertEquals("side",prefs.menuPosition);assertEquals("day",prefs.theme)
            assertEquals("simple",prefs.playbackConfig.playerLook)
            look.updateAccent(ColorThemes.CINEMA_RED)
            assertTrue(Ui.emberX)
            look.updateUiStyle(InterfaceStyles.LIGHT)
            assertFalse(Ui.emberX);assertTrue(Ui.light);assertTrue(Ui.isDay)
        } finally {scope.cancel()}
    }
    @Test fun selectedThemeRoundTripsThroughExistingEncryptedBackupStores() {
        val source=PersistenceContext(temp.newFolder());Prefs(source).apply {uiStyle=InterfaceStyles.EMBER_X;accent=2}
        val payload=BackupPersistence(source,FixtureSecrets()).snapshot()
        val target=PersistenceContext(temp.newFolder());BackupPersistence(target,FixtureSecrets()).restore(payload)
        assertEquals(2,Prefs(target).accent)
        assertEquals(InterfaceStyles.EMBER_X,Prefs(target).uiStyle)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {LookState(Prefs(target),scope);assertTrue(Ui.emberX)} finally {scope.cancel()}
    }
    @Test fun mediaUsesASingleColumnWhenTwoPanesWouldBeTooTight() {
        assertTrue(EmberXLayout.splitDetails(960f,1f))
        assertFalse(EmberXLayout.splitDetails(640f,1f))
        assertFalse(EmberXLayout.splitDetails(960f,1.5f))
        assertFalse(EmberXLayout.splitDetails(Float.POSITIVE_INFINITY,1f))
        assertTrue(EmberXLayout.heroHeight(500f,1f)>350f)
        assertTrue(EmberXLayout.heroHeight(500f,1.5f)>EmberXLayout.heroHeight(500f,1f))
        assertTrue(EmberXLayout.posterCount(960f,1.5f)<EmberXLayout.posterCount(960f,1f))
    }
    @Test fun cinemaTransportReusesOnDemandControlsWithoutChangingLiveOrTrailerPolicy() {
        fun custom(launcher:String,look:String,live:Boolean,trailer:Boolean):Boolean {
            val style=app.ember.tv.player.PlayerInterfaces.resolve(app.ember.tv.player.PlaybackConfig(playerLook=look),launcher)
            return app.ember.tv.player.PlayerInterfaces.customControls(style,live,trailer)
        }
        assertTrue(custom(InterfaceStyles.EMBER_X,"simple",false,false))
        assertFalse(custom(InterfaceStyles.EMBER_X,"simple",true,false))
        assertFalse(custom(InterfaceStyles.EMBER_X,"simple",false,true))
        assertTrue(custom(InterfaceStyles.MODERN,"simple",false,false))
        assertTrue(custom(InterfaceStyles.MODERN,"advanced",false,false))
    }
    @Test fun settingsSearchFindsTheInterfaceAtItsActualSection() {
        val hit=SettingDefinitions.matching("Ember X").single {it.id=="interface"}
        assertEquals(SettingsTab.APPEARANCE,hit.tab);assertEquals("interface",hit.id)
        assertEquals(InterfaceStyles.EMBER_X,hit.options.single {it.second=="Ember X"}.first)
        assertEquals("accent_colour",SettingDefinitions.matching("Modern Ember").single().id)
        assertEquals("interface",SettingDefinitions.matching("EmberFlix").single().id)
        assertTrue("app_icons" in lookKeys(SettingsTab.HOME_ROWS, InterfaceStyles.EMBER_X))
    }
}
