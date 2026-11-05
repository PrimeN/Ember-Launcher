package app.ember.tv.data

import java.io.File

/** Cache epoch, not a user preference. Restoring settings starts a new local generation. */
internal object ConfigurationGeneration {
    private fun file(directory:File)=File(directory,"configuration-generation")
    fun current(directory:File):String=runCatching {
        val marker=file(directory)
        if(!marker.exists())"" else if(marker.length()>128)"invalid" else marker.readText().trim().let {
            if(runCatching {java.util.UUID.fromString(it).toString()==it}.getOrDefault(false))it else "invalid"
        }
    }.getOrDefault("invalid")
    @Synchronized fun advance(directory:File) {
        AtomicFile.write(file(directory),java.util.UUID.randomUUID().toString()).getOrElse {
            throw Backup.BackupException("Could not finish restoring the configuration.")
        }
    }
    @Synchronized fun writeIfCurrent(directory:File,generation:String,write:()->Unit):Result<Unit> = runCatching {
        if(current(directory)==generation)write()
    }
}
