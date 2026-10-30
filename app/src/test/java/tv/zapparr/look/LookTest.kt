package tv.zapparr.look

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookTest {

    @Test
    fun okStepsThroughTheValuesAndRoundAgain() {
        assertEquals("12", Option.CLOCK.next("tv"))
        assertEquals("24", Option.CLOCK.next("12"))
        assertEquals("tv", Option.CLOCK.next("24"))
        assertEquals("off", Option.ZOOM.next("on"))
        // A value no longer offered starts the list again.
        assertEquals("tv", Option.CLOCK.next("13"))
    }

    @Test
    fun missingOrStaleValuesReadAsTheDefault() {
        val look = Look(mapOf(Option.GUIDE_ROWS to "huge", Option.CLOCK to "24", Option.PALETTE to "nord"))
        assertEquals("normal", look[Option.GUIDE_ROWS])
        assertEquals("24", look[Option.CLOCK])
        assertEquals("glow", look[Option.BACKGROUND])
        assertTrue(look.on(Option.CONTINUE_WATCHING))
        assertFalse(look.on(Option.POSTER_TITLES)) // titles under posters start hidden
        // Any saved palette is kept: the list of them decides.
        assertEquals("nord", look[Option.PALETTE])
    }

    @Test
    fun aChangeMakesADifferentLook() {
        val look = Look()
        assertEquals(look, Look())
        assertTrue(look != look.with(Option.ZOOM, "off"))
        assertEquals("off", look.with(Option.ZOOM, "off")[Option.ZOOM])
    }

    @Test
    fun everyOptionHasDistinctKeysAndValues() {
        assertEquals(Option.entries.size, Option.entries.map { it.key }.toSet().size)
        for (option in Option.entries) assertEquals(option.values.size, option.values.toSet().size)
    }
}
