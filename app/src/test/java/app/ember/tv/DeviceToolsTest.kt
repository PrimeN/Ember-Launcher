package app.ember.tv

import app.ember.tv.device.*
import app.ember.tv.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class DeviceToolsTest {
    @Test fun permissionOrExportRestrictionsNeverBecomeLaunchableShortcuts() {
        assertTrue(DeviceMenus.accessible(true, true, true, true))
        assertFalse(DeviceMenus.accessible(false, true, true, true))
        assertFalse(DeviceMenus.accessible(true, false, true, true))
        assertFalse(DeviceMenus.accessible(true, true, false, true))
        assertFalse(DeviceMenus.accessible(true, true, true, false))
    }
    @Test fun aRejectedManufacturerEntryTriesTheNextDetectedAlternativeAndStopsOnSuccess() {
        val tried = mutableListOf<String>()
        assertTrue(openFirstDeviceTarget(listOf("blocked", "fallback", "unused")) { target ->
            tried += target
            if (target == "blocked") throw SecurityException("fixture")
        })
        assertEquals(listOf("blocked", "fallback"), tried)
        assertFalse(openFirstDeviceTarget(listOf("gone", "disabled")) { throw IllegalStateException("fixture") })
        assertFalse(openFirstDeviceTarget(emptyList<String>()) { error("No target should launch") })
    }
    @Test fun hiddenAndTunerInputsAreExcludedButVisiblePassthroughInputsAreKept() {
        assertTrue(DeviceInputs.eligible(true, false))
        assertFalse(DeviceInputs.eligible(true, true))
        assertFalse(DeviceInputs.eligible(false, false))
        assertFalse(DeviceInputs.eligible(false, true))
    }
    @Test fun manufacturerMenuDetectionDistinguishesInputsPictureAndEngineering() {
        assertEquals(DeviceMenuKind.ENGINEERING, DeviceMenus.kind("mediatek.factorymenu.ui", "mediatek.factorymenu.ui.MainmenuActivity"))
        assertEquals(DeviceMenuKind.DEVICE, DeviceMenus.kind("com.droidlogic.tv.settings", "com.droidlogic.tv.settings.MainSettings"))
        assertEquals(DeviceMenuKind.DEVICE, DeviceMenus.kind("com.formovie.gsettings", "com.formovie.gsettings.ui.activity.MainActivity"))
        assertEquals(DeviceMenuKind.ENGINEERING, DeviceMenus.kind("com.fengmi.testsuite", "com.fengmi.testsuite.EngActivity"))
        assertEquals(DeviceMenuKind.DEVICE, DeviceMenus.kind("com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity"))
        assertEquals(DeviceMenuKind.INPUTS, DeviceMenus.kind("com.google.android.tvlauncher", "com.google.android.tvlauncher.inputs.InputsPanelActivity"))
        assertEquals(DeviceMenuKind.INPUTS, DeviceMenus.kind("com.mediatek.wwtv.tvcenter", "com.mediatek.wwtv.tvcenter.nav.inputpanel.InputsPanelActivity"))
        assertEquals(DeviceMenuKind.PICTURE, DeviceMenus.kind("com.xgimi.settings", "com.xgimi.settings.PictureActivity"))
        assertEquals(DeviceMenuKind.INPUTS, DeviceMenus.kind("com.formovie.fmmediaselect", "com.formovie.fmmediaselect.MainActivity"))
    }
    @Test fun directResetAgingAndUpdateActivitiesAreNotSuggestedAsMenus() {
        listOf("FactoryResetActivity", "EraseActivity", "BurnInActivity", "AgingActivity", "FirmwareUpgradeActivity").forEach {
            assertNull(DeviceMenus.kind("com.mediatek.engineermode", "com.mediatek.engineermode.$it"))
        }
        assertNull(DeviceMenus.kind("com.mediatek.engineermode", "com.mediatek.engineermode.audio.AudioTest"))
        assertNull(DeviceMenus.kind("com.xgimi.settings", "com.xgimi.settings.UnrelatedActivity"))
    }
    @Test fun uhdModeSelectionPreservesAspectAndNearestCurrentCadence() {
        val current = CalibrationMode(1, 1920, 1080, 59.94f)
        val uhd60 = CalibrationMode(3, 3840, 2160, 59.94f)
        val supported = listOf(current, CalibrationMode(2, 3840, 2160, 24f), uhd60,
            CalibrationMode(4, 4096, 2160, 60f), CalibrationMode(5, 7680, 4320, 60f))
        assertEquals(uhd60, CalibrationMath.chooseMode(current, supported))
        assertEquals(current, CalibrationMath.chooseMode(current, listOf(current)))
        assertEquals(current, CalibrationMath.chooseMode(current, emptyList()))
    }
    @Test fun a24fpsStepIsHeldUntilTheNextFrameRegardlessOfRedrawFrequency() {
        val first = CalibrationMath.position(10_000_000, 24)
        assertEquals(0f, first, 0f)
        assertEquals(first, CalibrationMath.position(40_000_000, 24), 0f)
        assertTrue(CalibrationMath.position(42_000_000, 24) > first)
        assertEquals(0f, CalibrationMath.position(4_000_000_000, 24), 0f)
        assertEquals(0f, CalibrationMath.position(-1, 24), 0f)
        assertEquals(CalibrationMath.position(1_000_000_000, 24), CalibrationMath.position(1_000_000_000, 60), 0f)
    }
    @Test fun patternNavigationWrapsInBothDirections() {
        assertEquals(CalibrationPattern.entries.last(), CalibrationPattern.entries.first().neighbour(-1))
        assertEquals(CalibrationPattern.entries.first(), CalibrationPattern.entries.last().neighbour(1))
    }
    @Test fun sdrOrHdr10MustNotMasqueradeAsDolbyVision() {
        assertFalse(CalibrationMath.clipMatches(CalibrationClip.DOLBY_VISION, "video/hevc", 6))
        assertTrue(CalibrationMath.clipMatches(CalibrationClip.DOLBY_VISION, "video/dolby-vision", 6))
        assertFalse(CalibrationMath.clipMatches(CalibrationClip.HDR10, "video/dolby-vision", 6))
        assertTrue(CalibrationMath.clipMatches(CalibrationClip.HDR10, "video/hevc", 6))
        assertFalse(CalibrationMath.clipMatches(CalibrationClip.HDR10, "video/hevc", 7))
        assertFalse(CalibrationMath.clipMatches(CalibrationClip.SDR, "video/hevc", 6))
        assertFalse(CalibrationMath.clipMatches(CalibrationClip.SDR, "video/hevc", 7))
        assertTrue(CalibrationMath.clipMatches(CalibrationClip.SDR, "video/avc", null))
        assertFalse(CalibrationMath.clipMatches(CalibrationClip.SDR, null, null))
        assertFalse(CalibrationMath.clipMatches(CalibrationClip.SDR, "audio/mp4a-latm", null))
    }
    @Test fun dolbyVisionTestsCannotSilentlyDowngradeToABaseLayerDecoder() {
        assertTrue(CalibrationMath.allowDecoder(CalibrationClip.DOLBY_VISION, "video/dolby-vision"))
        assertFalse(CalibrationMath.allowDecoder(CalibrationClip.DOLBY_VISION, "video/hevc"))
        assertFalse(CalibrationMath.allowDecoder(CalibrationClip.DOLBY_VISION, "video/avc"))
        assertTrue(CalibrationMath.allowDecoder(CalibrationClip.HDR10, "video/hevc"))
        assertTrue(CalibrationMath.allowDecoder(CalibrationClip.SDR, "video/avc"))
    }
    @Test fun allDeviceSearchResultsLeadToComposedSections() {
        val ids = setOf("calibration-patterns", "calibration-video", "device-menus", "device-inputs")
        listOf("calibration patterns", "Dolby Vision test", "HDMI AV").forEach { query ->
            val hit = SettingDefinitions.matching(query).first { it.tab == SettingsTab.DEVICE_TOOLS }
            assertTrue(hit.id in ids)
        }
        assertEquals(SettingsTab.DEVICE_TOOLS, SettingDefinitions.matching("engineering").single().tab)
        assertEquals("engineering-menus", SettingDefinitions.matching("engineering").single().id)
        assertTrue(SettingsCategory.SYSTEM.pages.contains(SettingsTab.DEVICE_TOOLS))
        assertTrue(SETTINGS_INDEX.single { it.first == SettingsTab.DEVICE_TOOLS }.second.contains("engineering"))
        assertTrue(SettingsCategory.SYSTEM.pages.contains(SettingsTab.DEVICE_TOOLS))
        assertTrue(SETTINGS_INDEX.any { it.first == SettingsTab.DEVICE_TOOLS })
    }
    @Test fun calibrationActivityIsPrivateAndManufacturerVisibilityIsNarrow() {
        val manifest = File("src/main/AndroidManifest.xml").takeIf { it.exists() } ?: File("app/src/main/AndroidManifest.xml")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
        val ns = "http://schemas.android.com/apk/res/android"
        val activities = doc.getElementsByTagName("activity")
        val calibration = (0 until activities.length).map { activities.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(ns, "name") == ".device.CalibrationActivity" }
        assertEquals("false", calibration.getAttributeNS(ns, "exported"))
        val packages = doc.getElementsByTagName("package")
        val visible = (0 until packages.length).map { (packages.item(it) as org.w3c.dom.Element).getAttributeNS(ns, "name") }
        assertTrue(visible.containsAll(DeviceMenus.packages))
        val permissions = doc.getElementsByTagName("uses-permission")
        val grants = (0 until permissions.length).map { (permissions.item(it) as org.w3c.dom.Element).getAttributeNS(ns, "name") }
        assertFalse("android.permission.QUERY_ALL_PACKAGES" in grants)
        assertFalse("android.permission.WRITE_SECURE_SETTINGS" in grants)
    }
}
