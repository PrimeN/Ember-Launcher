package app.ember.tv

import app.ember.tv.player.*
import org.junit.Assert.*
import org.junit.Test

class SmbShareBrowsingTest {
    @Test fun rootIsAllowedOnlyForShareEnumerationAndSaveStillRequiresAShare() {
        assertThrows(IllegalArgumentException::class.java){NetworkLocation.parse("smb://nas")}
        val root=NetworkLocation.parse("smb://nas",allowSmbRoot=true)
        assertEquals("",root.root);assertEquals(445,root.port)
        assertEquals("Movies",NetworkLocation.parse(root.smbShare("Movies")).root)
    }
    @Test fun shareLinksEncodeNamesAndPreservePortsAndIpv6() {
        val root=NetworkLocation.parse("smb://[2001:db8::1]:1445",allowSmbRoot=true)
        val address=root.smbShare("Films & TV")
        assertTrue(address.contains("Films%20&%20TV"))
        val parsed=NetworkLocation.parse(address)
        assertEquals(1445,parsed.port);assertEquals("Films & TV",parsed.root)
    }
    @Test fun enumerationSkipsHiddenPrinterIpcAndInvalidSharesAndDeduplicates() {
        val result=SmbShares.visible(listOf("Movies" to 0,"movies" to 0,"IPC$" to 3,"C$" to 0,"Printer" to 1,
            "../escape" to 0,"bad\\name" to 0,".." to 0,"Music" to 0,null to 0))
        assertEquals(listOf("Movies","Music"),result)
        assertEquals(256,SmbShares.visible((1..500).map {"Share$it" to 0}).size)
    }
    @Test fun browseAddressesStillRejectEmbeddedCredentialsQueriesAndUnsafePaths() {
        listOf("smb://user:password@nas","smb://nas?token=one","smb://nas/#secret","smb://nas/../private","smb://nas:0").forEach {
            assertThrows(it,IllegalArgumentException::class.java){NetworkLocation.parse(it,allowSmbRoot=true)}
        }
        assertNull(SmbShares.address("nas",0));assertNull(SmbShares.address("",445))
        assertEquals("smb://nas",SmbShares.address("nas",445))
    }
}
