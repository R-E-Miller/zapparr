package tv.zapparr.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.zapparr.library.Played
import tv.zapparr.library.Progress
import tv.zapparr.library.Saved

class ContinueWatchingTest {

    private val minute = 60_000L

    private fun movie(position: Long, at: Long, watched: Boolean = false) =
        Saved(Progress(position, 100 * minute, watched, at), Played("Movie", null, null, "mkv"))

    private fun episode(series: Int, number: Int, position: Long, at: Long, watched: Boolean = false) =
        Saved(Progress(position, 30 * minute, watched, at), Played("Show $series", null, null, "mkv", series, 1, number))

    private fun app(pkg: String, at: Long, type: Int = 0, browsable: Boolean = true, title: String = "Title") =
        WatchNextRow(pkg, pkg, title, null, null, null, null, type, browsable, at, 0, 0)

    private fun keys(cards: List<Unfinished>) = cards.map {
        when (it) {
            is Unfinished.InLibrary -> it.key
            is Unfinished.InApp -> it.row.packageName
        }
    }

    @Test
    fun onlyTitlesLeftPartWay() {
        val saved = mapOf(
            "movie:1" to movie(42 * minute, at = 5),
            "movie:2" to movie(0, at = 6, watched = true), // finished
            "movie:3" to movie(30_000, at = 7), // barely started: starts over, so not "continuing"
        )
        assertEquals(listOf("movie:1"), keys(ContinueWatching.cards(saved, emptyList(), "tv.zapparr")))
    }

    @Test
    fun oneCardPerSeriesTheEpisodeLeftLast() {
        val saved = mapOf(
            "episode:101" to episode(7, 1, 10 * minute, at = 3),
            "episode:102" to episode(7, 2, 12 * minute, at = 8),
            "episode:201" to episode(9, 1, 5 * minute, at = 4),
        )
        assertEquals(listOf("episode:102", "episode:201"), keys(ContinueWatching.cards(saved, emptyList(), "tv.zapparr")))
    }

    @Test
    fun otherAppsMixInByWhenTheyWereWatched() {
        val saved = mapOf("movie:1" to movie(42 * minute, at = 50), "movie:2" to movie(20 * minute, at = 10))
        val others = listOf(
            app("com.netflix", at = 30),
            app("com.watchlist", at = 99, type = 3), // a watchlist: saved for later, not being watched
            app("com.hidden", at = 98, browsable = false), // removed by the user
            app("tv.zapparr", at = 97), // Zapparr's own, never listed twice
            app("com.untitled", at = 96, title = " "),
        )
        assertEquals(listOf("movie:1", "com.netflix", "movie:2"), keys(ContinueWatching.cards(saved, others, "tv.zapparr")))
    }

    @Test
    fun theRowIsCapped() {
        val others = (1..30).map { app("app$it", at = it.toLong()) }
        val cards = ContinueWatching.cards(emptyMap(), others, "tv.zapparr")
        assertEquals(ContinueWatching.LIMIT, cards.size)
        assertEquals("app30", keys(cards).first())
    }

    @Test
    fun minutesLeftAndHowFarIn() {
        assertEquals(58, ContinueWatching.minutesLeft(42 * minute, 100 * minute))
        assertEquals(1, ContinueWatching.minutesLeft(99 * minute + 1, 100 * minute)) // rounded up
        assertNull(ContinueWatching.minutesLeft(42 * minute, 0)) // no length from the player
        assertEquals(0.42f, ContinueWatching.fraction(42 * minute, 100 * minute)!!, 0.0001f)
        assertNull(ContinueWatching.fraction(42 * minute, 0))
    }
}
