package tv.zapparr.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnNowTest {

    private val minute = 60_000L

    private fun channel(id: Int, number: Int = id) = Channel(id, number, "Ch $number", null, number.toString(), null, 0)

    @Test
    fun theLastChannelWatchedComesFirst() {
        assertEquals(listOf(5), OnNow.watched(emptyList(), 5))
        assertEquals(listOf(3, 5, 7), OnNow.watched(listOf(5, 3, 7), 3)) // watched again: moves up, not in twice
        assertEquals(OnNow.LIMIT, OnNow.watched((1..20).toList(), 99).size)
    }

    @Test
    fun recentChannelsThenFavorites() {
        val lineup = listOf(channel(1), channel(2), channel(3), channel(4), channel(5))
        // 9 is gone from the server; 2 is both recent and a favorite, and shows once.
        assertEquals(listOf(4, 2, 1, 5), OnNow.channels(listOf(4, 9, 2), setOf(5, 2, 1), lineup).map { it.streamId })
        assertEquals(emptyList<Channel>(), OnNow.channels(emptyList(), emptySet(), lineup))
    }

    @Test
    fun whatIsAiring() {
        val programs = listOf(Program(0, 30 * minute, "early", null), Program(30 * minute, 90 * minute, "late", null))
        assertEquals("early", OnNow.airing(programs, 29 * minute)?.title)
        assertEquals("late", OnNow.airing(programs, 30 * minute)?.title)
        assertNull(OnNow.airing(programs, 90 * minute))
        assertNull(OnNow.airing(null, 0))
        assertEquals(0.25f, OnNow.progress(programs[1], 45 * minute), 0.0001f)
    }
}
