package app.ember.tv

import app.ember.tv.home.*
import org.junit.Test
import org.junit.After
import org.junit.Assert.*
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class HomeServiceReliabilityTest {
    @After fun reset(){HomeServiceRuntime.disconnected();HomeServiceIssue.entries.forEach(HomeServiceRuntime::recovered)}
    @Test fun callbackConfigurationFailuresAreContainedAndLoggingDoesNotFlood() {
        val first=mutableListOf<Boolean>()
        val guard=HomeCallbackGuard({_,_,new->first+=new})
        repeat(2){assertFalse(guard.attempt(HomeServiceIssue.CONFIGURATION){throw SecurityException("fixture")})}
        assertEquals(listOf(true,false),first)
        assertTrue(guard.attempt(HomeServiceIssue.CONFIGURATION){})
        assertFalse(guard.attempt(HomeServiceIssue.CONFIGURATION){throw IllegalStateException("fixture")})
        assertEquals(listOf(true,false,true),first)
    }
    @Test fun aDiagnosticFailureCannotCrashTheFrameworkCallback() {
        val guard=HomeCallbackGuard({_,_,_->throw IllegalStateException("logger fixture")})
        assertFalse(guard.attempt(HomeServiceIssue.WINDOW_EVENT){throw SecurityException("fixture")})
    }
    @Test fun connectionAndConfigurationFailureRemainDistinctUntilRecovery() {
        HomeServiceRuntime.connected()
        HomeServiceRuntime.problem(HomeServiceIssue.CONFIGURATION)
        assertTrue(HomeServiceRuntime.health.value.connected)
        assertEquals(HomeServiceIssue.CONFIGURATION,HomeServiceRuntime.health.value.issue)
        HomeServiceRuntime.recovered(HomeServiceIssue.WINDOW_EVENT)
        assertEquals(HomeServiceIssue.CONFIGURATION,HomeServiceRuntime.health.value.issue)
        HomeServiceRuntime.recovered(HomeServiceIssue.CONFIGURATION)
        assertNull(HomeServiceRuntime.health.value.issue)
        HomeServiceRuntime.disconnected()
        assertFalse(HomeServiceRuntime.health.value.connected)
    }
    @Test fun firstHomeAtEarlyBootActuallyLaunches() {
        val gate=HomeLaunchGate();var calls=0
        assertTrue(gate.open(100){calls++;true})
        assertEquals(1,calls)
    }
    @Test fun failedHomeLaunchRemainsImmediatelyRetryable() {
        val gate=HomeLaunchGate();var calls=0
        assertFalse(gate.open(100){calls++;false})
        assertTrue(gate.open(101){calls++;true})
        assertEquals(2,calls)
    }
    @Test fun onlySuccessfulLaunchesAreDebounced() {
        val gate=HomeLaunchGate();var calls=0
        assertTrue(gate.open(100){calls++;true})
        assertTrue(gate.open(999){calls++;error("must not relaunch")})
        assertEquals(1,calls)
        assertTrue(gate.open(1_100){calls++;true})
        assertEquals(2,calls)
    }
    @Test fun failedKeyLaunchDoesNotConsumeEitherHalfOfHome() {
        val keys=HomeKeySequence()
        assertFalse(keys.down(false,true){false})
        assertFalse(keys.up())
        assertFalse(keys.down(true,true){error("a repeat must not retry")})
    }
    @Test fun successfulHomeConsumesABalancedPairWithoutRepeatingTheLaunch() {
        val keys=HomeKeySequence();var calls=0
        assertTrue(keys.down(false,true){calls++;true})
        assertTrue(keys.down(true,true){calls++;false})
        assertTrue(keys.up());assertFalse(keys.up());assertEquals(1,calls)
    }
    @Test fun interruptionsAndDisabledModesNeverLeaveAStaleHandledHome() {
        val keys=HomeKeySequence()
        assertTrue(keys.down(false,true){true});keys.reset();assertFalse(keys.up())
        assertFalse(keys.down(false,false){error("must not launch")});assertFalse(keys.up())
    }
    @Test fun manufacturerHomeIsDetectedWithoutTreatingItsSettingsDialogAsHome() {
        val match=HomeWindowMatcher("app.ember.tv",setOf("stock.home"),setOf(HomeWindowTarget("vendor.projector","vendor.projector.Home")))
        assertTrue(match.matches("vendor.projector","vendor.projector.Home"))
        assertFalse(match.matches("vendor.projector","vendor.projector.Settings"))
        assertFalse(match.matches("vendor.projector",null))
        assertTrue("vendor.projector" in match.packages)
    }
    @Test fun ownLauncherAndUnrelatedAppsCannotCauseRedirectionLoops() {
        val match=HomeWindowMatcher("app.ember.tv",setOf("app.ember.tv","stock.home"),emptySet())
        assertFalse(match.matches("app.ember.tv","MainActivity"))
        assertFalse("app.ember.tv" in match.packages)
        assertFalse(match.matches("other.launcher","Home"))
        assertFalse(match.matches(null,null))
        assertTrue(match.matches("stock.home","LegacyHome"))
    }
    @Test fun discoveredLegacyHomeAlsoIgnoresNonHomeWindows() {
        val match=HomeWindowMatcher("app.ember.tv",setOf("stock.home"),setOf(HomeWindowTarget("stock.home","HomeActivity")))
        assertTrue(match.matches("stock.home","HomeActivity"))
        assertFalse(match.matches("stock.home","SettingsDialog"))
    }
    @Test fun androidBindingContractAndLimitedCapabilitiesRemainDeclared() {
        val root=listOf(File("src/main"),File("app/src/main")).first {File(it,"AndroidManifest.xml").isFile}
        val factory=DocumentBuilderFactory.newInstance().apply {isNamespaceAware=true}
        val manifest=factory.newDocumentBuilder().parse(File(root,"AndroidManifest.xml"))
        val ns="http://schemas.android.com/apk/res/android"
        val services=manifest.getElementsByTagName("service")
        val service=(0 until services.length).map {services.item(it) as org.w3c.dom.Element}.single {it.getAttributeNS(ns,"name")==".home.HomeButtonService"}
        assertEquals("true",service.getAttributeNS(ns,"enabled"));assertEquals("true",service.getAttributeNS(ns,"exported"))
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE",service.getAttributeNS(ns,"permission"))
        val categories=manifest.getElementsByTagName("queries").item(0) as org.w3c.dom.Element
        assertTrue((0 until categories.getElementsByTagName("category").length).any {
            (categories.getElementsByTagName("category").item(it) as org.w3c.dom.Element).getAttributeNS(ns,"name")=="android.intent.category.HOME"
        })
        val config=factory.newDocumentBuilder().parse(File(root,"res/xml/home_button_service.xml")).documentElement
        assertEquals("false",config.getAttributeNS(ns,"canRetrieveWindowContent"))
        assertEquals("true",config.getAttributeNS(ns,"canRequestFilterKeyEvents"))
        assertEquals("typeWindowStateChanged",config.getAttributeNS(ns,"accessibilityEventTypes"))
    }
}
