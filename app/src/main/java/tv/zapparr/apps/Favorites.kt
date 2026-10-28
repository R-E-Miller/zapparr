package tv.zapparr.apps

import android.content.Context
import androidx.core.content.edit

/** The rules for the Favorites row, kept free of Android so they can be unit-tested. */
object Favorites {
    /** Shown until the row is first edited; apps that aren't installed are skipped. */
    val DEFAULTS = listOf(
        "com.amazon.amazonvideo.livingroom", // Prime Video
        "com.cbs.ott", // Paramount+
        "ar.tvplayer.tv", // TiviMate
        "com.google.android.youtube.tv", // YouTube
        "com.google.android.youtube.tvmusic", // YouTube Music
    )

    /** The packages to show, in order: the saved row if there is one, otherwise the defaults. */
    fun visible(saved: List<String>?, installed: Collection<String>): List<String> {
        val present = installed.toHashSet()
        return (saved ?: DEFAULTS).filter { it in present }.distinct()
    }

    fun add(row: List<String>, packageName: String) = if (packageName in row) row else row + packageName

    fun remove(row: List<String>, packageName: String) = row - packageName

    /** Moves [packageName] [by] places, stopping at either end. */
    fun move(row: List<String>, packageName: String, by: Int): List<String> {
        val from = row.indexOf(packageName)
        if (from < 0) return row
        val to = (from + by).coerceIn(0, row.lastIndex)
        return row.toMutableList().apply { add(to, removeAt(from)) }
    }
}

/** Saves the Favorites row once it has been edited; until then [Favorites.DEFAULTS] apply. */
class FavoritesStore(context: Context) {
    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    fun saved(): List<String>? = prefs.getString(KEY, null)?.split('\n')?.filter { it.isNotEmpty() }

    fun save(row: List<String>) = prefs.edit { putString(KEY, row.joinToString("\n")) }

    private companion object {
        const val KEY = "row"
    }
}
