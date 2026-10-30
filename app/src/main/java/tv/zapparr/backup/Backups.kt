package tv.zapparr.backup

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Zapparr's backups on this box: each one saved in Zapparr's own storage (kept through updates)
 * and in Downloads/Zapparr, where a file manager or a USB drive can reach it and where it survives
 * Zapparr being uninstalled.
 */
class Backups(private val context: Context) {

    /** A backup found on the box: in Zapparr's own storage ([file]) or in Downloads ([uri]). */
    class Found(val name: String, val created: Long, val file: File?, val uri: Uri?)

    private val dir = File(context.filesDir, "backups")

    /** What a backup would hold now, with the server logins if [withLogins]. */
    fun snapshot(withLogins: Boolean): String {
        val names = Backup.FILES + if (withLogins) Backup.LOGINS else emptyList()
        val files = names.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all }
        return Backup.write(files, System.currentTimeMillis(), version())
    }

    /** Saves a backup now; returns its file name. Downloads is left out where Android can't reach it (before Android 10). */
    fun save(withLogins: Boolean): String {
        val json = snapshot(withLogins)
        val name = "zapparr-backup-" + SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date()) + ".json"
        dir.mkdirs()
        File(dir, name).writeText(json)
        // Only the newest are kept here; Downloads keeps whatever the user leaves there.
        dir.listFiles()?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                saveToDownloads(name, json)
            } catch (e: IOException) {
                // Zapparr's own copy is enough to restore from on this box.
            }
        }
        return name
    }

    /** The backups on this box, newest first; the same backup in both places is listed once. */
    fun list(): List<Found> {
        val own = dir.listFiles().orEmpty().filter { it.name.endsWith(".json") }.map { Found(it.name, it.lastModified(), it, null) }
        val downloads = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) inDownloads() else emptyList()
        val names = own.mapTo(HashSet()) { it.name }
        return (own + downloads.filter { it.name !in names }).sortedByDescending { it.name }
    }

    fun read(found: Found): String {
        found.file?.let { return it.readText() }
        val uri = found.uri ?: throw IOException("nothing to read")
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: throw IOException("can't open $uri")
    }

    /** Puts back everything [json] holds, replacing what's there now. Throws [BackupException] if it isn't a backup. */
    fun restore(json: String) {
        for ((name, values) in Backup.read(json)) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit(commit = true) {
                clear()
                for ((key, value) in values) {
                    when (value) {
                        is String -> putString(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
            }
        }
        generation++
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToDownloads(name: String, json: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Downloads refused it")
        resolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } ?: throw IOException("can't write $uri")
    }

    // Backups in Downloads/Zapparr that Zapparr may read: its own, and on Android 10 to 12 any, once
    // it may read storage (a backup made before Zapparr was installed again, say).
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun inDownloads(): List<Found> {
        val found = ArrayList<Found>()
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.DATE_MODIFIED)
        val where = MediaStore.Downloads.RELATIVE_PATH + " LIKE ?"
        try {
            context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, where, arrayOf("%$FOLDER%"), null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (!name.startsWith("zapparr-backup") || !name.endsWith(".json")) continue
                    val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
                    found += Found(name, c.getLong(2) * 1000, null, uri)
                }
            }
        } catch (e: RuntimeException) { // SecurityException, or no media store on this box
            return emptyList()
        }
        return found
    }

    private fun version() = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    companion object {
        const val FOLDER = "Zapparr"
        private const val KEEP = 10

        /** Counts restores, so a screen drawn before one knows to draw itself again. */
        @Volatile
        var generation = 0
            private set
    }
}

/** Whether backups include the server logins: Zapparr's own choice, never itself in a backup. */
class BackupSettings(context: Context) {
    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)

    var withLogins: Boolean
        get() = prefs.getBoolean(LOGINS, true)
        set(value) = prefs.edit { putBoolean(LOGINS, value) }

    private companion object {
        const val LOGINS = "logins"
    }
}
