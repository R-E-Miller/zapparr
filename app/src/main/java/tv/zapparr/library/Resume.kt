package tv.zapparr.library

import tv.zapparr.live.Episode

/** Where a player stopped, from the result it hands back. Milliseconds; [duration] is 0 if unknown. */
data class Stopped(val position: Long, val duration: Long, val finished: Boolean)

/** What Zapparr remembers about a movie or an episode: where it was left, and when. [at] is epoch milliseconds. */
data class Progress(val position: Long, val duration: Long, val watched: Boolean, val at: Long)

/**
 * What was played, kept with its progress so Continue Watching can show it and play it again: a
 * movie named [name], or episode [episode] of season [season] of the series [series] named [name].
 * [art] is a wide picture (the episode's still or the movie's backdrop), [poster] the tall one.
 */
data class Played(
    val name: String,
    val poster: String?,
    val art: String?,
    val extension: String,
    val series: Int? = null,
    val season: Int = 0,
    val episode: Int = 0,
)

/** A movie or an episode as [ProgressStore] keeps it. */
data class Saved(val progress: Progress, val played: Played)

/** The Library's resume rules. Free of Android, so they are unit-tested. */
object Resume {

    /** Less than this in, a movie or an episode starts over next time. */
    const val MIN_POSITION = 60_000L

    /** Past this share of its length, it counts as watched. */
    const val WATCHED = 0.95

    /**
     * Reads a player's result extras: VLC's (`extra_position`, `extra_duration`) or MX Player's,
     * which Just Player uses too (`position`, `duration`, `end_by`). Null when the player said nothing.
     */
    fun stopped(extras: Map<String, Any?>): Stopped? {
        val position = millis(extras["extra_position"]) ?: millis(extras["position"]) ?: return null
        val duration = millis(extras["extra_duration"]) ?: millis(extras["duration"]) ?: 0
        return Stopped(position.coerceAtLeast(0), duration.coerceAtLeast(0), extras["end_by"] == "playback_completion")
    }

    /** What to remember after [stopped], at [now]. */
    fun progress(stopped: Stopped, now: Long): Progress {
        val watched = stopped.finished || stopped.duration > 0 && stopped.position >= stopped.duration * WATCHED
        val position = if (watched || stopped.position < MIN_POSITION) 0 else stopped.position
        return Progress(position, stopped.duration, watched, now)
    }

    /** Where to offer Resume from, or null to start from the beginning. */
    fun resumeAt(progress: Progress?): Long? = progress?.position?.takeIf { !progress.watched && it >= MIN_POSITION }

    /**
     * The episode a series' Play button offers: the one most recently left part-way, else the one
     * after the most recently watched, else the first. [episodes] are in season and episode order.
     */
    fun next(episodes: List<Episode>, progress: (Episode) -> Progress?): Episode? {
        val seen = episodes.mapNotNull { episode -> progress(episode)?.let { episode to it } }
        seen.filter { (_, p) -> resumeAt(p) != null }.maxByOrNull { (_, p) -> p.at }?.let { return it.first }
        val lastWatched = seen.filter { (_, p) -> p.watched }.maxByOrNull { (_, p) -> p.at }?.first
        if (lastWatched != null) episodes.getOrNull(episodes.indexOf(lastWatched) + 1)?.let { return it }
        return episodes.firstOrNull()
    }

    /** A position as a clock: "42:10", or "1:02:10" past an hour. */
    fun clock(millis: Long): String {
        val seconds = millis / 1000
        val (h, m, s) = Triple(seconds / 3600, seconds / 60 % 60, seconds % 60)
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun millis(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }
}
