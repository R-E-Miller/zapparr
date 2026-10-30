package tv.zapparr.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.zapparr.live.Episode

class ResumeTest {

    private val minute = 60_000L

    @Test
    fun vlcAndMxPlayerResultsAreRead() {
        // VLC: longs named extra_*. MX Player and Just Player: ints, and end_by.
        assertEquals(Stopped(42 * minute, 100 * minute, false), Resume.stopped(mapOf("extra_position" to 42 * minute, "extra_duration" to 100 * minute)))
        assertEquals(
            Stopped(90 * minute, 100 * minute, true),
            Resume.stopped(mapOf("position" to (90 * minute).toInt(), "duration" to (100 * minute).toInt(), "end_by" to "playback_completion")),
        )
        assertEquals(Stopped(5 * minute, 0, false), Resume.stopped(mapOf("position" to (5 * minute).toInt(), "end_by" to "user")))
        assertNull(Resume.stopped(emptyMap()))
    }

    @Test
    fun whatIsRemembered() {
        assertEquals(Progress(42 * minute, 100 * minute, false, 7), Resume.progress(Stopped(42 * minute, 100 * minute, false), 7))
        // Barely started: start over next time, and not watched.
        assertEquals(Progress(0, 100 * minute, false, 7), Resume.progress(Stopped(30_000, 100 * minute, false), 7))
        // Into the credits, or played to the end: watched.
        assertEquals(Progress(0, 100 * minute, true, 7), Resume.progress(Stopped(96 * minute, 100 * minute, false), 7))
        assertEquals(Progress(0, 0, true, 7), Resume.progress(Stopped(80 * minute, 0, true), 7))
        // No length from the player: never watched by position alone.
        assertEquals(Progress(80 * minute, 0, false, 7), Resume.progress(Stopped(80 * minute, 0, false), 7))
    }

    @Test
    fun resumeIsOfferedOnlyPartWay() {
        assertEquals(42 * minute, Resume.resumeAt(Progress(42 * minute, 100 * minute, false, 1)))
        assertNull(Resume.resumeAt(Progress(0, 100 * minute, true, 1)))
        assertNull(Resume.resumeAt(Progress(0, 100 * minute, false, 1)))
        assertNull(Resume.resumeAt(null))
    }

    @Test
    fun theSeriesPlayButtonPicksTheRightEpisode() {
        fun ep(season: Int, number: Int) = Episode(season * 100 + number, season, number, "S$season E$number", "mkv", null, null, 0)
        val episodes = listOf(ep(1, 1), ep(1, 2), ep(1, 3), ep(2, 1))
        fun next(vararg seen: Pair<Int, Progress>) = Resume.next(episodes) { e -> seen.toMap()[e.id] }?.id

        assertEquals(101, next()) // nothing seen: the first
        assertEquals(103, next(102 to Progress(0, 0, true, 5))) // after the last watched
        assertEquals(201, next(103 to Progress(0, 0, true, 5))) // across seasons
        assertEquals(101, next(201 to Progress(0, 0, true, 5))) // after the very last: from the start
        // Part-way through one beats having watched a later one, and the most recent wins.
        assertEquals(102, next(102 to Progress(10 * minute, 0, false, 9), 103 to Progress(0, 0, true, 5), 101 to Progress(5 * minute, 0, false, 3)))
        assertNull(Resume.next(emptyList()) { null })
    }

    @Test
    fun clockTimes() {
        assertEquals("42:10", Resume.clock(42 * minute + 10_000))
        assertEquals("1:02:05", Resume.clock(62 * minute + 5_000))
        assertEquals("0:09", Resume.clock(9_000))
    }
}
