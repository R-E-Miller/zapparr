package tv.zapparr.live

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.zapparr.live.Guide.Cell
import tv.zapparr.live.Guide.HALF_HOUR

class GuideTest {

    private val minute = 60 * 1000L
    private val t0 = 1_800_000_000_000L // a half hour

    private fun program(from: Long, to: Long, title: String = "p") = Program(t0 + from * minute, t0 + to * minute, title, null)

    private fun spans(cells: List<Cell>) = cells.map { listOf((it.start - t0) / minute, (it.end - t0) / minute, it.program?.title) }

    @Test
    fun cellsCutProgramsToTheWindowAndFillGaps() {
        val programs = listOf(
            program(-90, -30, "gone"), // ended before the grid
            program(-15, 30, "airing"), // started before the grid
            program(45, 90, "later"), // after a 15-minute gap
            program(200, 260, "beyond"), // after the grid
        )
        assertEquals(
            listOf(listOf(0L, 30L, "airing"), listOf(30L, 45L, null), listOf(45L, 90L, "later"), listOf(90L, 120L, null)),
            spans(Guide.cells(programs, t0, t0 + 120 * minute)),
        )
    }

    @Test
    fun cellsTrimOverlapsAndCloseTinyGaps() {
        val programs = listOf(
            Program(t0, t0 + 30 * minute, "first", null),
            Program(t0 + 20 * minute, t0 + 60 * minute, "overlaps", null),
            Program(t0 + 60 * minute + 20_000, t0 + 119 * minute + 30_000, "late by 20 s", null),
        )
        val cells = Guide.cells(programs, t0, t0 + 120 * minute)
        assertEquals(listOf("first", "overlaps", "late by 20 s"), cells.map { it.program?.title })
        assertEquals(listOf(t0, t0 + 30 * minute, t0 + 60 * minute), cells.map { it.start })
        assertEquals(t0 + 120 * minute, cells.last().end) // the 30 s left at the end joins the last program
    }

    @Test
    fun aChannelWithoutGuideDataIsOneGap() {
        assertEquals(listOf(Cell(t0, t0 + HALF_HOUR, null)), Guide.cells(emptyList(), t0, t0 + HALF_HOUR))
    }

    @Test
    fun indexAtFindsTheCellOnScreenAtATime() {
        val cells = Guide.cells(listOf(program(0, 30), program(30, 90)), t0, t0 + 120 * minute)
        assertEquals(0, Guide.indexAt(cells, t0))
        assertEquals(1, Guide.indexAt(cells, t0 + 30 * minute))
        assertEquals(2, Guide.indexAt(cells, t0 + 100 * minute))
        assertEquals(2, Guide.indexAt(cells, t0 + 500 * minute))
    }

    @Test
    fun halfHourRoundsDown() {
        assertEquals(t0, Guide.halfHour(t0))
        assertEquals(t0, Guide.halfHour(t0 + 29 * minute))
        assertEquals(t0 + HALF_HOUR, Guide.halfHour(t0 + 31 * minute))
    }

    @Test
    fun scrollKeepsTheFocusedProgramInView() {
        val origin = t0
        fun cell(from: Long, to: Long) = Cell(t0 + from * minute, t0 + to * minute, null)
        // Inside the two hours on screen, before the last half hour: no scrolling.
        assertEquals(origin, Guide.scroll(origin, cell(60, 90), origin))
        // Starting in the last half hour: the grid moves on so that half hour is second.
        assertEquals(t0 + 60 * minute, Guide.scroll(origin, cell(90, 120), origin))
        assertEquals(t0 + 150 * minute, Guide.scroll(origin, cell(185, 200), origin))
        // Back to a program that starts before the left edge: its half hour comes back, never before the origin.
        assertEquals(t0 + 30 * minute, Guide.scroll(t0 + 60 * minute, cell(45, 70), origin))
        assertEquals(origin, Guide.scroll(t0 + 60 * minute, cell(-20, 70), origin))
    }

    @Test
    fun filters() {
        fun channel(id: Int, group: String?) = Channel(id, id, "c$id", null, "$id", group, 0)
        val channels = listOf(channel(1, "news"), channel(2, "sports"), channel(3, null), channel(4, "news"))
        assertEquals(channels, Guide.filter(channels, Guide.ALL, emptySet()))
        assertEquals(listOf(1, 4), Guide.filter(channels, Guide.groupFilter(Group("news", "News")), emptySet()).map { it.streamId })
        assertEquals(listOf(2, 3), Guide.filter(channels, Guide.FAVORITES, setOf(3, 2, 99)).map { it.streamId })
        assertEquals(emptyList<Channel>(), Guide.filter(channels, Guide.FAVORITES, emptySet()))
    }
}
