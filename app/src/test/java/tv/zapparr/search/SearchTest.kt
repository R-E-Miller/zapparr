package tv.zapparr.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.zapparr.live.Catalog
import tv.zapparr.live.Channel
import tv.zapparr.live.Library
import tv.zapparr.live.Lineup
import tv.zapparr.live.Movie
import tv.zapparr.live.Program
import tv.zapparr.live.Series

class SearchTest {

    private val minute = 60_000L
    private val now = 1_800_000_000_000L

    private fun channel(number: Int, name: String) = Channel(1000 + number, number, name, null, number.toString(), null, 0)

    private fun program(from: Long, to: Long, title: String) = Program(now + from * minute, now + to * minute, title, null)

    private fun movie(id: Int, name: String) = Movie(id, name, null, 0, null, null, "mkv", null)

    private fun index(channels: List<Channel> = emptyList(), programs: Map<String, List<Program>> = emptyMap(), movies: List<Movie> = emptyList(), series: List<Series> = emptyList()) =
        SearchIndex(
            Lineup(emptyList(), channels, programs, now + 12 * 60 * minute),
            Catalog(Library(emptyList(), emptyList(), movies), Library(emptyList(), emptyList(), series)),
        )

    @Test
    fun namesAreComparedWithoutCaseAccentsOrPunctuation() {
        assertEquals("amelie 2001", Search.normalize("Amélie (2001)"))
        assertEquals("spider man no way home", Search.normalize("  Spider-Man: No Way Home!  "))
        assertEquals("", Search.normalize(" – "))
    }

    @Test
    fun startsBeatLaterWordsBeatScatteredWords() {
        assertEquals(0, Search.rank("the last harbor 1980", "the last"))
        assertEquals(1, Search.rank("the last harbor 1980", "last harbor"))
        assertEquals(2, Search.rank("the last harbor 1980", "harbor last"))
        assertNull(Search.rank("the last harbor 1980", "arbor")) // inside a word
        assertNull(Search.rank("the last harbor 1980", "last ship"))
        assertNull(Search.rank("anything", ""))
    }

    @Test
    fun aNumberFindsThatChannelFirst() {
        val channels = listOf(channel(4, "WABC"), channel(14, "News 4"), channel(40, "Sports"), channel(2, "Kids 4 You"))
        val found = index(channels).find("4", now).channels.map { it.number }
        // Channel 4, then 40 (its number starts with 4), then names with a word "4", in number order.
        assertEquals(listOf(4, 40, 2, 14), found)
        assertEquals(listOf(40), index(channels).find("sp", now).channels.map { it.number })
    }

    @Test
    fun programsOnNowComeFirstEachTitleOncePerChannel() {
        val news = channel(1, "News 1")
        val other = channel(2, "Local 2")
        val programs = mapOf(
            "1" to listOf(program(-90, -30, "Evening News"), program(30, 60, "Evening News"), program(90, 120, "Evening News")),
            "2" to listOf(program(-10, 20, "Evening News Extra"), program(200, 230, "Weather")),
        )
        val found = index(listOf(news, other), programs).find("evening news", now).programs
        // Ended ones are gone; the one on now (an "Extra") leads; channel 1's news shows once, its next airing.
        assertEquals(listOf(2 to now - 10 * minute, 1 to now + 30 * minute), found.map { it.channel.number to it.program.start })
    }

    @Test
    fun moviesAndSeriesRankedThenAlphabetical() {
        val movies = listOf(movie(1, "Harbor Lights (1990)"), movie(2, "The Last Harbor (1980)"), movie(3, "Harbor (2001)"), movie(4, "Blue Ship"))
        val series = listOf(Series(9, "Harbor Patrol (US)", null, 0, null, null, null))
        val found = index(movies = movies, series = series).find("Harbor", now)
        assertEquals(listOf(3, 1, 2), found.movies.map { it.id }) // both starting with it, alphabetically, then the later word
        assertEquals(listOf(9), found.series.map { it.id })
        assertTrue(index(movies = movies).find("zzz", now).isEmpty)
        assertTrue(index(movies = movies).find("  ", now).isEmpty)
    }
}
