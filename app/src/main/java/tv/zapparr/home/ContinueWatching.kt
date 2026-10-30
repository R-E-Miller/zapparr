package tv.zapparr.home

import tv.zapparr.library.Resume
import tv.zapparr.library.Saved

/**
 * Another app's Watch Next entry, as TvProvider lists it. [season] and [episode] are the app's own
 * labels. Times are milliseconds; [at] is when it was last watched, 0 if unknown.
 */
data class WatchNextRow(
    val packageName: String,
    val appName: String,
    val title: String,
    val season: String?,
    val episode: String?,
    val image: String?,
    val intentUri: String?,
    val type: Int,
    val browsable: Boolean,
    val at: Long,
    val position: Long,
    val duration: Long,
)

/** A card in Continue Watching. */
sealed interface Unfinished {
    val at: Long

    /** A movie or an episode left part-way in the Library, under its [tv.zapparr.library.ProgressStore] key. */
    data class InLibrary(val key: String, val saved: Saved) : Unfinished {
        override val at get() = saved.progress.at
    }

    /** Another app's Watch Next entry. */
    data class InApp(val row: WatchNextRow) : Unfinished {
        override val at get() = row.at
    }
}

/** What the Apps deck's Continue Watching row shows. Free of Android, so it is unit-tested. */
object ContinueWatching {

    const val LIMIT = 20

    // TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST: saved for later, not being watched.
    private const val WATCHLIST = 3

    /**
     * Library titles left part-way (one per series: the episode left last) and other apps' Watch
     * Next entries, the most recently watched first. [own] is Zapparr's package, never listed twice.
     */
    fun cards(saved: Map<String, Saved>, others: List<WatchNextRow>, own: String): List<Unfinished> {
        val library = saved.entries
            .filter { Resume.resumeAt(it.value.progress) != null }
            .groupBy { (key, value) -> value.played.series?.let { "series:$it" } ?: key }
            .map { (_, entries) -> entries.maxBy { it.value.progress.at } }
            .map { Unfinished.InLibrary(it.key, it.value) }
        val apps = others
            .filter { it.browsable && it.type != WATCHLIST && it.packageName != own && it.title.isNotBlank() }
            .map(Unfinished::InApp)
        return (library + apps).sortedByDescending { it.at }.take(LIMIT)
    }

    /** Whole minutes left (rounded up), or null when the length isn't known. */
    fun minutesLeft(position: Long, duration: Long): Int? =
        if (duration <= 0 || position <= 0 || position >= duration) null else ((duration - position + MINUTE - 1) / MINUTE).toInt()

    /** How far in, 0 to 1, or null when the length isn't known. */
    fun fraction(position: Long, duration: Long): Float? =
        if (duration <= 0 || position <= 0) null else (position.toFloat() / duration).coerceIn(0f, 1f)

    private const val MINUTE = 60_000L
}
