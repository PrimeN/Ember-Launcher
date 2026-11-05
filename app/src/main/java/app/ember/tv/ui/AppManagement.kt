package app.ember.tv.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

internal object AppManagement {
    fun packageUri(pkg: String, launcher: String): String? =
        pkg.takeIf { it!=launcher && it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) }?.let { "package:$it" }

    /** Android owns confirmation, policy restrictions and system-app/update handling. */
    fun uninstall(context: Context, pkg: String) {
        val uri=packageUri(pkg,context.packageName)?.let(Uri::parse) ?: return
        try { context.startActivity(Intent(Intent.ACTION_DELETE,uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch(_:ActivityNotFoundException){details(context,uri)}
        catch(_:SecurityException){details(context,uri)}
    }
    private fun details(context:Context,uri:Uri) {
        try {context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
        catch(_:ActivityNotFoundException){Toast.makeText(context,"App management is unavailable on this device.",Toast.LENGTH_LONG).show()}
        catch(_:SecurityException){Toast.makeText(context,"App management is restricted on this device.",Toast.LENGTH_LONG).show()}
    }
}
