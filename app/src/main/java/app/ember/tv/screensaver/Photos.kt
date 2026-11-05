package app.ember.tv.screensaver

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/**
 * Your own pictures, read from the TV's photo library (which includes most USB drives).
 * Many Android TVs have no file picker, so folders are listed from the library instead.
 */
object Photos {
    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    fun granted(context: Context) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Folders that hold pictures, with how many each has, largest first. */
    fun folders(context: Context): List<Pair<String, Int>> {
        if (!granted(context)) return emptyList()
        val counts = HashMap<String, Int>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            val col = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val name = c.getString(col) ?: continue
                counts[name] = (counts[name] ?: 0) + 1
            }
        }
        return counts.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    /** Pictures from one folder, or from all of them when [folder] is null. Newest first. */
    fun slides(context: Context, folder: String?, max: Int = 200): List<Slide> {
        if (!granted(context)) return emptyList()
        val out = ArrayList<Slide>()
        val selection = folder?.let { "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?" }
        val args = folder?.let { arrayOf(it) }
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
            selection, args,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (c.moveToNext() && out.size < max) {
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol))
                out += Slide(image = uri.toString(), title = c.getString(nameCol)?.substringBeforeLast('.') ?: "", credit = folder ?: "")
            }
        }
        return out
    }
}
