package app.ember.tv.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Same-directory replacement for app data files: a failed or interrupted save keeps the last
 * complete file and leaves no temporary file behind. Used for collections, the library, the Home
 * snapshot, badge packs, restored backups and the encrypted IPTV caches.
 */
internal object AtomicFile {
    @Synchronized fun write(target: File, text: String): Result<Unit> = write(target, text.toByteArray(Charsets.UTF_8))

    @Synchronized fun write(target: File, bytes: ByteArray): Result<Unit> = replace(target) { temp ->
        FileOutputStream(temp).use { out -> out.write(bytes); out.fd.sync() }
    }

    /** [write] fills the temporary file (and should sync it); it then replaces [target] in one step. */
    internal fun replace(target: File, write: (File) -> Unit): Result<Unit> = runCatching {
        val parent = target.absoluteFile.parentFile!!
        parent.mkdirs()
        val temp = File.createTempFile(".${target.name}-", ".tmp", parent)
        try {
            write(temp)
            try { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temp.delete() }
    }
}
