package app.ember.tv

import app.ember.tv.data.ArtworkDiskCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtworkPartitionTest {
    @get:Rule val temp=TemporaryFolder()
    private fun store()=ArtworkDiskCache(temp.newFolder(),null,1024*1024,1000,partition={it.substringBefore(':')})
    @Test fun retiringOneRealmPreservesOtherRealmsAndRejectsItsOldWrites()=runBlocking {
        val disk=store();val old=disk.stampFor("account-a:poster")
        assertTrue(disk.write("account-a:poster",byteArrayOf(1)))
        assertTrue(disk.write("account-b:poster",byteArrayOf(2)))
        disk.clearPartition("account-a")
        assertNull(disk.read("account-a:poster"));assertFalse(disk.write("account-a:poster",byteArrayOf(3),old))
        assertArrayEquals(byteArrayOf(2),disk.read("account-b:poster"))
        assertTrue(disk.write("account-a:poster",byteArrayOf(4)));assertArrayEquals(byteArrayOf(4),disk.read("account-a:poster"))
    }
    @Test fun anotherRealmsRetirementDoesNotInvalidateAnInFlightTicket()=runBlocking {
        val disk=store();val ticket=disk.stampFor("account-b:poster")
        disk.clearPartition("account-a")
        assertTrue(disk.current(ticket));assertTrue(disk.write("account-b:poster",byteArrayOf(2),ticket))
    }
    @Test fun clearingAllCachesStillInvalidatesEveryRealm()=runBlocking {
        val disk=store();val ticket=disk.stampFor("account-b:poster")
        disk.clear();assertFalse(disk.current(ticket));assertFalse(disk.write("account-b:poster",byteArrayOf(2),ticket))
    }
}
