package tv.zapparr.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Build

/** A launchable app: its TV banner if it has one, otherwise its icon. */
class AppEntry(
    val packageName: String,
    val label: String,
    val banner: Drawable?,
    val icon: Drawable,
    private val component: ComponentName,
    private val leanback: Boolean,
) {
    fun launchIntent(): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(if (leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
        .setComponent(component)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

/** Lists the apps the TV can launch: TV (leanback) activities first, then phone-only ones. */
class AppRepository(private val context: Context) {

    /** Slow on a budget box (labels and banners come from other APKs): call it off the main thread. */
    fun load(): List<AppEntry> {
        val pm = context.packageManager
        val apps = LinkedHashMap<String, AppEntry>()
        for (leanback in listOf(true, false)) {
            val category = if (leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER
            for (info in pm.launchable(category)) {
                val activity = info.activityInfo
                val pkg = activity.packageName
                if (pkg == context.packageName || pkg in apps) continue
                apps[pkg] = AppEntry(
                    packageName = pkg,
                    label = info.loadLabel(pm).toString(),
                    banner = activity.loadBanner(pm),
                    icon = info.loadIcon(pm),
                    component = ComponentName(pkg, activity.name),
                    leanback = leanback,
                )
            }
        }
        return apps.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    private fun PackageManager.launchable(category: String): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            queryIntentActivities(intent, 0)
        }
    }
}
