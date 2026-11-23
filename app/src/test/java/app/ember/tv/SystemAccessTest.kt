package app.ember.tv

import app.ember.tv.system.AdBlockDns
import app.ember.tv.system.AdbPairPage
import app.ember.tv.ui.SettingsCategory
import app.ember.tv.ui.SettingsTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #408 System access and #409 ad blocking. */
class SystemAccessTest {
    @Test fun nextDnsProfileBecomesItsPrivateDnsHost() {
        assertEquals("abc123.dns.nextdns.io", AdBlockDns.host("abc123"))
        assertEquals("abc123.dns.nextdns.io", AdBlockDns.host(" ABC123 "))
        assertEquals("abc123.dns.nextdns.io", AdBlockDns.host("https://dns.nextdns.io/abc123"))
        assertEquals("dns.adguard-dns.com", AdBlockDns.host("dns.adguard-dns.com"))
        assertNull(AdBlockDns.host(""))
        assertNull(AdBlockDns.host("not a host; rm -rf"))
        assertNull(AdBlockDns.host("dns.nextdns.io/../x"))
    }

    @Test fun nextDnsIsAnOptionalApiKey() {
        val s = app.ember.tv.data.ApiKeys.SERVICES.single { it.name == AdBlockDns.SERVICE }
        assertEquals(listOf(AdBlockDns.FIELD), s.fields.map { it.id })
        assertFalse(app.ember.tv.data.ApiKeys.required(s))
        assertTrue(app.ember.tv.data.ApiKeys.LIMITS.containsKey(AdBlockDns.SERVICE))
    }

    @Test fun debuggingIdentityIsMadeOnceAndKept() {
        val dir = kotlin.io.path.createTempDirectory("adb").toFile()
        val (key, cert) = app.ember.tv.system.AdbIdentity.load(dir)
        val x509 = cert as java.security.cert.X509Certificate
        x509.verify(x509.publicKey)
        assertEquals("CN=Ember", x509.subjectX500Principal.name)
        val (again, sameCert) = app.ember.tv.system.AdbIdentity.load(dir)
        assertEquals(key, again)
        assertEquals(cert, sameCert)
    }

    @Test fun systemAccessIsASystemPage() {
        assertEquals(SettingsCategory.SYSTEM, SettingsCategory.of(SettingsTab.SYSTEM_ACCESS))
    }

    @Test fun pairingPageEscapesText() {
        assertEquals("&lt;b&gt; &amp; &quot;", AdbPairPage.esc("<b> & \""))
        assertTrue(AdbPairPage.page("Pair", "x").contains("<title>Pair</title>"))
    }
}
