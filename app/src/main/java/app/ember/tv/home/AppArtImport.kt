package app.ember.tv.home

import app.ember.tv.data.toHex
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

internal object AppArtImport {
    const val MAX_ENCODED=350_000
    fun decode(bytes:ByteArray):Bitmap {
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384 && bounds.outWidth.toLong()*bounds.outHeight<=64_000_000) {"Choose a smaller image."}
        var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>512)sample*=2
        return requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888})) {"That image could not be read."}
    }
    @Suppress("DEPRECATION")
    fun read(context:Context,uri:Uri):AppArt {
        require(uri.scheme=="content") {"Choose an image from the file picker."}
        val bytes=requireNotNull(context.contentResolver.openInputStream(uri)).use {it.readBytesBounded(4*1024*1024)}
        val bitmap=decode(bytes)
        val encoded=try {ByteArrayOutputStream().also {require(bitmap.compress(Bitmap.CompressFormat.WEBP,85,it))}.toByteArray()}finally{bitmap.recycle()}
        val data=Base64.getEncoder().encodeToString(encoded);require(data.length<=MAX_ENCODED) {"Choose a simpler image."}
        val id=MessageDigest.getInstance("SHA-256").digest(encoded).toHex()
        return AppArt(id,data)
    }
    private fun java.io.InputStream.readBytesBounded(max:Int):ByteArray {
        val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true){val count=read(buffer);if(count<0)break;require(out.size()+count<=max){"Image must be smaller than 4 MB."};out.write(buffer,0,count)}
        return out.toByteArray()
    }
}
