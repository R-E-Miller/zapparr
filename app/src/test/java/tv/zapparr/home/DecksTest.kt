package tv.zapparr.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecksTest {

    @Test
    fun rightGoesToTheNextDeckAndStopsAtTheLast() {
        assertEquals(1, Decks.neighbour(current = 0, count = 3, pressedRight = true, rtl = false))
        assertEquals(2, Decks.neighbour(current = 1, count = 3, pressedRight = true, rtl = false))
        assertNull(Decks.neighbour(current = 2, count = 3, pressedRight = true, rtl = false))
    }

    @Test
    fun leftGoesBackAndStopsAtTheFirst() {
        assertEquals(1, Decks.neighbour(current = 2, count = 3, pressedRight = false, rtl = false))
        assertNull(Decks.neighbour(current = 0, count = 3, pressedRight = false, rtl = false))
    }

    @Test
    fun rightToLeftLayoutsMirrorTheKeys() {
        assertEquals(1, Decks.neighbour(current = 0, count = 3, pressedRight = false, rtl = true))
        assertNull(Decks.neighbour(current = 0, count = 3, pressedRight = true, rtl = true))
    }
}
