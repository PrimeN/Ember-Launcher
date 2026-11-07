package app.ember.tv

import android.content.pm.PackageInstaller
import app.ember.tv.data.updateInstallMessage
import org.junit.Assert.assertTrue
import org.junit.Test

/** The installer's answers become messages instead of silence (#99). */
class UpdateInstallTest {
    @Test fun everyInstallerOutcomeSaysWhatHappened() {
        assertTrue(updateInstallMessage(PackageInstaller.STATUS_SUCCESS).contains("installed"))
        assertTrue(updateInstallMessage(PackageInstaller.STATUS_FAILURE_ABORTED).contains("won't download again"))
        assertTrue(updateInstallMessage(PackageInstaller.STATUS_FAILURE_STORAGE).contains("storage"))
        assertTrue(updateInstallMessage(PackageInstaller.STATUS_FAILURE_CONFLICT).contains("signature"))
        assertTrue(updateInstallMessage(12345).contains("try again"))
    }
}
