package app.ember.tv.data

import java.io.File
import java.nio.file.Files

/** Never follow links out of a disposable cache directory. */
internal object CacheFiles {
    fun size(file: File): Long {
        if (Files.isSymbolicLink(file.toPath()) || !file.exists()) return 0
        if (file.isFile) return file.length()
        return (file.listFiles() ?: error("Could not read cache size.")).sumOf(::size)
    }
    fun delete(file: File): Boolean {
        if (Files.isSymbolicLink(file.toPath())) return file.delete()
        if (!file.exists()) return true
        if (file.isFile) return file.delete()
        val children = file.listFiles() ?: return false
        var cleared = true
        children.forEach { if (!delete(it)) cleared = false }
        return file.delete() && cleared
    }
}
