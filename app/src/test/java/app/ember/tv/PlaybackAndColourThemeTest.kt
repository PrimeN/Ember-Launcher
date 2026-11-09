package app.ember.tv

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.ember.tv.data.*
import app.ember.tv.ui.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackAndColourThemeTest {
    private fun stream(quality:String="4K",hdr:String="DV",audio:String="Atmos",server:String?=null)=StreamOption(
        addonName=if(server==null)"Test add-on" else "Plex",name="Source",title="Source",
        url="https://example.test/video",infoHash=null,fileIdx=null,quality=quality,hdr=hdr,codec="HEVC",audio=audio,
        source="",releaseGroup="",sizeGb=10.0,seeders=null,cached=true,serverRef=server)

    @Test fun noSourceIsDisabledAndCheckingIsDifferentFromConfirmedEmpty() {
        val empty=detailsPlaybackPresentation("Resume 00:01:39",DetailsPlaybackTarget.CHOOSE,null,MediaSourcePresentation(0,false),null)
        assertFalse(empty.enabled)
        assertEquals("No source available",empty.label)
        assertNull(empty.detail)
        val checking=detailsPlaybackPresentation("Play",DetailsPlaybackTarget.CHOOSE,null,MediaSourcePresentation(0,true),null)
        assertFalse(checking.enabled)
        assertEquals("Finding sources…",checking.label)
    }

    @Test fun arrivingAddOnSourceEnablesResumeAndShowsItsActualOptions() {
        val source=stream()
        val target=detailsPlaybackTarget(true,true,0,true)
        val action=detailsPlaybackPresentation("Resume 00:01:39",target,source,MediaSourcePresentation(1,true),null)
        assertEquals(DetailsPlaybackTarget.SOURCE,target)
        assertTrue(action.enabled)
        assertEquals("Resume 00:01:39",action.label)
        assertEquals("Test add-on · 4K · DV · Atmos",action.detail)
        assertEquals("Test add-on",detailsPlaybackPresentation("Play",target,stream("","",""),MediaSourcePresentation(1,false),null).detail)
    }

    @Test fun manualModeAndRulesRejectingAutomaticChoiceKeepTheSourceChooserAvailable() {
        val source=stream()
        assertNull(PlayPicker.pick(listOf(source),emptyList(),PlayRules(maxQuality="HD")))
        for(automatic in listOf(false,true)) {
            val target=detailsPlaybackTarget(automatic,false,0,false)
            val action=detailsPlaybackPresentation("Play",target,null,MediaSourcePresentation(2,false),null)
            assertEquals(DetailsPlaybackTarget.CHOOSE,target)
            assertTrue(action.enabled)
            assertEquals("Choose a source · 2 options",action.detail)
        }
        val manual=detailsPlaybackPresentation("Play",detailsPlaybackTarget(false,true,0,false),source,MediaSourcePresentation(1,false),null)
        assertEquals("Choose a source · 1 option",manual.detail)
    }

    @Test fun singleInstalledServiceRetainsPlaybackAndShowsItsName() {
        val target=detailsPlaybackTarget(true,false,1,false,true)
        val action=detailsPlaybackPresentation("Play",target,null,MediaSourcePresentation(1,false),"Service")
        assertEquals(DetailsPlaybackTarget.SERVICE,target)
        assertTrue(action.enabled)
        assertEquals("Service",action.detail)
    }

    @Test fun bestSourceStillHonoursServerPriorityAndQualityCap() {
        val addon=stream()
        val server=stream("FHD",server="server|item")
        assertEquals(server,PlayPicker.pick(listOf(addon),listOf(server),PlayRules()))
        assertEquals(addon,PlayPicker.pick(listOf(addon),listOf(server),PlayRules(serverFirst=false)))
        assertEquals(server,PlayPicker.pick(listOf(addon),listOf(server),PlayRules(maxQuality="FHD")))
    }

    @Test fun modernPaletteIsAppendedWithoutChangingSavedColoursOrBrandAliases() {
        assertEquals(listOf("Ember","Shadow Ember","Blue ember","Green flame","Rose ember"),Accents.take(5).map{it.first})
        assertEquals("Modern Ember",Accents[ColorThemes.MODERN_EMBER].first)
        for(i in 0..4)assertEquals(i,ColorThemes.artworkIndex(i))
        assertEquals(2,ColorThemes.artworkIndex(ColorThemes.MODERN_EMBER))
        assertEquals(Color(0xFF111215),emberColorScheme(0,false,false).background)
        assertEquals(Color(0xFFFF7A2F),emberColorScheme(0,false,false).primary)
    }

    private fun contrast(a:Color,b:Color):Double {
        val x=a.luminance().toDouble();val y=b.luminance().toDouble()
        return (maxOf(x,y)+.05)/(minOf(x,y)+.05)
    }

    @Test fun modernDayNightAndOledKeepReadableTextAndDistinctColourSurfaces() {
        for(day in listOf(false,true))for(oled in listOf(false,true)) {
            val c=emberColorScheme(ColorThemes.MODERN_EMBER,day,oled)
            listOf(c.onBackground to c.background,c.onSurface to c.surface,c.onSurfaceVariant to c.surfaceVariant,
                c.onPrimary to c.primary,c.onSecondary to c.secondary,c.onTertiary to c.tertiary,
                c.onPrimaryContainer to c.primaryContainer,c.onSecondaryContainer to c.secondaryContainer,
                c.onTertiaryContainer to c.tertiaryContainer).forEach { (text,background)->assertTrue("Contrast ${contrast(text,background)}",contrast(text,background)>=4.5) }
            assertNotEquals(c.primary,c.secondary)
            assertNotEquals(c.secondary,c.tertiary)
            if(day)assertEquals(ModernEmberColors.DayBackground,c.background)
            else assertEquals(if(oled)Color.Black else ModernEmberColors.Background,c.background)
        }
    }

    @Test fun shadowEmberReusesTheSavedGreySlotAndSearchFindsItsNewName() {
        assertEquals(1,ColorThemes.SHADOW_EMBER)
        assertEquals(1,ColorThemes.artworkIndex(ColorThemes.SHADOW_EMBER))
        assertEquals("Shadow Ember",Accents[ColorThemes.SHADOW_EMBER].first)
        assertEquals("accent_colour",SettingDefinitions.matching("Shadow Ember").single().id)
        assertEquals("accent_colour",SettingDefinitions.matching("dark grey").single().id)
        assertEquals("1",SettingDefinitions.colourTheme.options.single {it.second=="Shadow Ember"}.first)
        assertTrue(ShadowEmberColors.Accent.luminance()<Color(0xFFB0A49C).luminance())
    }

    @Test fun shadowDayNightAndOledKeepAllPaletteRolesReadable() {
        for(day in listOf(false,true))for(oled in listOf(false,true)) {
            val c=emberColorScheme(ColorThemes.SHADOW_EMBER,day,oled)
            listOf(c.onBackground to c.background,c.onSurface to c.surface,c.onSurfaceVariant to c.surfaceVariant,
                c.onPrimary to c.primary,c.onSecondary to c.secondary,c.onTertiary to c.tertiary,
                c.onPrimaryContainer to c.primaryContainer,c.onSecondaryContainer to c.secondaryContainer,
                c.onTertiaryContainer to c.tertiaryContainer).forEach {(text,background)->
                assertTrue("Shadow contrast ${contrast(text,background)}",contrast(text,background)>=4.5)
            }
            if(!day) {
                assertEquals(if(oled)Color.Black else ShadowEmberColors.Background,c.background)
                assertTrue(c.surface.luminance()<.025f)
                assertTrue(c.surfaceVariant.luminance()>c.surface.luminance())
            }
        }
    }

    @Test fun shadowSettingsTintsStayDarkWithoutLosingTextContrast() {
        for(oled in listOf(false,true))for(amount in listOf(0f,.05f,.18f,.24f,.34f,1f)) {
            val dark=shadowSurfaceTint(amount,false,oled)
            assertTrue(contrast(Color(0xFFE8EAED),dark)>=4.5)
            assertTrue(dark.luminance()<.08f)
            assertTrue(contrast(Color(0xFF1F1F1F),shadowSurfaceTint(amount,true,oled))>=4.5)
        }
    }

    @Test fun colourSearchAndBothSettingsMenusShareTheSavedThemeChoice() {
        val hit=SettingDefinitions.matching("Modern Ember").single()
        assertEquals("accent_colour",hit.id)
        assertTrue(hit.id in appearanceKeys("modern"))
        assertTrue(hit.id in appearanceKeys("light"))
        assertEquals(ColorThemes.MODERN_EMBER.toString(),hit.options.single{it.second=="Modern Ember"}.first)
    }

    @Test fun mediaButtonsUseNavigationFocusAndSelectionWithoutFillingDisabledActions() {
        for(focus in listOf(ModernEmberColors.Focus,ModernEmberColors.DayPrimary)) {
            val selection=focus.copy(alpha=.34f)
            assertEquals(androidx.compose.ui.graphics.Color.Transparent,detailsActionFill(true,false,false,focus,selection))
            assertEquals(focus,detailsActionFill(true,true,false,focus,selection))
            assertEquals(selection,detailsActionFill(true,false,true,focus,selection))
            assertEquals(androidx.compose.ui.graphics.Color.Transparent,detailsActionFill(false,true,true,focus,selection))
        }
    }

    @Test fun modernEmberFollowsGoogleTvsSystemPanels() {
        // #146: indigo-navy panels, rows a step lighter, a near-white focus pill, cyan for what's chosen.
        val c=emberColorScheme(ColorThemes.MODERN_EMBER,day=false,oled=false)
        fun hsv(color:Color):FloatArray {
            val r=color.red;val g=color.green;val b=color.blue
            val max=maxOf(r,g,b);val d=max-minOf(r,g,b)
            val h=when {d==0f->0f;max==r->60f*(((g-b)/d)%6f);max==g->60f*((b-r)/d+2f);else->60f*((r-g)/d+4f)}
            return floatArrayOf((h+360f)%360f,if(max==0f)0f else d/max,max)
        }
        for(panel in listOf(c.background,c.surface,c.surfaceVariant)) {
            val h=hsv(panel)
            assertTrue("indigo-navy, not grey or purple: ${h[0]}",h[0] in 225f..245f)
            assertTrue("dark",h[2]<.40f)
        }
        assertTrue(c.surface.luminance()>c.background.luminance())
        assertTrue(c.surfaceVariant.luminance()>c.surface.luminance())
        assertTrue("focus pill is near white",ModernEmberColors.Focus.luminance()>.75f)
        assertTrue(contrast(ModernEmberColors.OnFocus,ModernEmberColors.Focus)>=7.0)
        val cyan=hsv(ModernEmberColors.Cyan)
        assertTrue("selection is light cyan-blue: ${cyan[0]}",cyan[0] in 190f..205f)
        assertEquals(ModernEmberColors.Cyan,c.tertiary)
    }
}
