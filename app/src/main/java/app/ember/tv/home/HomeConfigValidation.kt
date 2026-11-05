package app.ember.tv.home

import java.security.MessageDigest
import java.util.Base64

internal object HomeConfigValidation {
    fun homes(values:Map<String,PersonalHome>,profiles:Set<String>) {
        require(values.size<=profiles.size && values.keys.all {it in profiles})
        require(values.values.sumOf {p->p.artwork.values.sumOf {it.data.length.toLong()}}<=6_000_000)
        values.values.forEach {p->
            require(p.folders.size<=24 && p.folders.map {it.id}.distinct().size==p.folders.size)
            require(p.folders.all {it.id.isNotBlank() && it.id.length<=100 && it.name.isNotBlank() && it.name.length<=40 && it.packages.size<=200})
            require(p.artwork.size<=100 && p.dismissed.size<=300 && p.mutedApps.size<=500)
            p.artwork.values.forEach {art->
                require(art.data.length<=AppArtImport.MAX_ENCODED && art.id.matches(Regex("[0-9a-f]{64}")))
                val bytes=Base64.getDecoder().decode(art.data)
                val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
                require(hash==art.id)
            }
        }
    }
    fun extras(value:LauncherExtras) {
        require(value.destination in setOf("home","app","input","presentation") && value.target.length<=512 && value.slideSeconds in 5..120)
        require(value.lockedApps.size<=500)
        require(value.slides.size<=40 && value.slides.map {it.id}.distinct().size==value.slides.size)
        require(value.slides.all {it.id.isNotBlank() && it.id.length<=100 && it.name.length<=100 && it.uri.length<=4096 && it.uri.substringBefore(':') in setOf("file","content") && !it.uri.contains('\u0000')})
    }
}
