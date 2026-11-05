package app.ember.tv.player

import android.content.Context
import android.system.Os
import java.io.File

/** The upstream fontconfig build has no Android app-specific default config.
 * Point it at read-only system fonts and a private, non-media cache before loading libmpv.
 */
internal object NativePlayerFiles {
    @Synchronized fun prepare(context:Context) {
        val directory=File(context.noBackupFilesDir,"player-fontconfig").apply {check(isDirectory||mkdirs())}
        val cache=File(context.cacheDir,"player-fonts").apply {check(isDirectory||mkdirs())}
        val roots=listOf("/system/fonts","/product/fonts","/system/product/fonts").filter {File(it).isDirectory}
        check(roots.isNotEmpty()) {"System fonts are unavailable."}
        val file=File(directory,"fonts.conf")
        val text=config(roots,cache.absolutePath)
        if(!file.isFile||file.readText()!=text)file.writeText(text)
        Os.setenv("FONTCONFIG_FILE",file.absolutePath,true)
        Os.setenv("FONTCONFIG_PATH",directory.absolutePath,true)
    }
    fun config(roots:List<String>,cache:String):String {
        fun xml(value:String)=value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
        return "<?xml version=\"1.0\"?><fontconfig>"+roots.joinToString(""){"<dir>${xml(it)}</dir>"}+
            "<cachedir>${xml(cache)}</cachedir><alias><family>sans-serif</family><prefer><family>Roboto</family><family>Noto Sans</family></prefer></alias></fontconfig>"
    }
}
