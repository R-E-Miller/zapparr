package tv.zapparr.apps

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import tv.zapparr.R

fun Context.openApp(app: AppEntry) = start(app.launchIntent(), app.label)

fun Context.openAppInfo(app: AppEntry) = start(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    app.label,
)

/** The TV's own Settings: with the stock launcher gone, this is the way in. */
fun Context.openSettings() = start(
    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    getString(R.string.settings),
)

private fun Context.start(intent: Intent, label: String) {
    try {
        startActivity(intent)
    } catch (e: RuntimeException) { // ActivityNotFoundException, or SecurityException from a guarded activity
        Toast.makeText(this, getString(R.string.cannot_open, label), Toast.LENGTH_SHORT).show()
    }
}
