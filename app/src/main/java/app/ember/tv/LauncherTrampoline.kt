package app.ember.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Where the app-list entries (one activity-alias per icon colour) lead: it opens MainActivity by
 * its own name and closes. Android finishes every activity started through an alias when that
 * alias is switched off, so if MainActivity were opened through one, changing the accent colour
 * (which switches the icon alias) would close Ember. Started this way, it keeps running.
 */
class LauncherTrampoline : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }
}
