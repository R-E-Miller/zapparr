package tv.zapparr.home

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.tv.TvContract.WatchNextPrograms
import androidx.core.content.ContextCompat
import java.net.URISyntaxException

/**
 * Other apps' Watch Next entries, from TvProvider. Reading them needs [PERMISSION]; without it, or
 * on a box without TvProvider, there are none. Apps that opt out (not searchable) aren't listed.
 */
object WatchNext {

    const val PERMISSION = "android.permission.READ_TV_LISTINGS"

    fun allowed(context: Context) = ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Every entry Zapparr may see. Off the main thread: it asks another process. */
    fun read(context: Context): List<WatchNextRow> {
        if (!allowed(context)) return emptyList()
        return try {
            // No selection or sort order: TvProvider refuses both from apps like Zapparr.
            context.contentResolver.query(WatchNextPrograms.CONTENT_URI, null, null, null, null)?.use { rows(context, it) }.orEmpty()
        } catch (e: RuntimeException) { // SecurityException, or no TvProvider on this box
            emptyList()
        }
    }

    /**
     * The app's own intent for the entry, made safe to start: another app wrote it, so it may not
     * reach Zapparr's own screens or borrow Zapparr's access to anything. Null if there is none.
     */
    fun intent(context: Context, row: WatchNextRow): Intent? {
        val intent = row.intentUri?.let {
            try {
                Intent.parseUri(it, Intent.URI_INTENT_SCHEME)
            } catch (e: URISyntaxException) {
                null
            }
        } ?: return context.packageManager.run {
            getLeanbackLaunchIntentForPackage(row.packageName) ?: getLaunchIntentForPackage(row.packageName)
        }
        intent.selector = null
        intent.flags = intent.flags and GRANTS.inv()
        val target = intent.component?.packageName ?: intent.`package` ?: intent.resolveActivity(context.packageManager)?.packageName
        return if (target == null || target == context.packageName) null else intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun rows(context: Context, cursor: Cursor): List<WatchNextRow> {
        val labels = HashMap<String, String>()
        val rows = ArrayList<WatchNextRow>()
        while (cursor.moveToNext()) {
            val pkg = cursor.text(WatchNextPrograms.COLUMN_PACKAGE_NAME) ?: continue
            rows += WatchNextRow(
                packageName = pkg,
                appName = labels.getOrPut(pkg) { label(context, pkg) },
                title = cursor.text(WatchNextPrograms.COLUMN_TITLE).orEmpty(),
                season = cursor.text(WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER),
                episode = cursor.text(WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER),
                // The thumbnail is usually the wide one, as the card is.
                image = cursor.text(WatchNextPrograms.COLUMN_THUMBNAIL_URI) ?: cursor.text(WatchNextPrograms.COLUMN_POSTER_ART_URI),
                intentUri = cursor.text(WatchNextPrograms.COLUMN_INTENT_URI),
                type = cursor.number(WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE)?.toInt() ?: 0,
                browsable = cursor.number(WatchNextPrograms.COLUMN_BROWSABLE) != 0L,
                at = cursor.number(WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS) ?: 0,
                position = cursor.number(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS) ?: 0,
                duration = cursor.number(WatchNextPrograms.COLUMN_DURATION_MILLIS) ?: 0,
            )
        }
        return rows
    }

    private fun label(context: Context, pkg: String) = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun Cursor.text(column: String): String? {
        val i = getColumnIndex(column)
        return if (i < 0 || isNull(i)) null else getString(i)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun Cursor.number(column: String): Long? {
        val i = getColumnIndex(column)
        return if (i < 0 || isNull(i)) null else getLong(i)
    }

    private const val GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
}
