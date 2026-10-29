package tv.zapparr.live

/** The Guide grid's rules: a channel's row of cells, scrolling through time, and the filters. Free of Android, so it is unit-tested. */
object Guide {
    const val HALF_HOUR = 30 * 60 * 1000L

    /** How much time the grid shows at once. */
    const val SPAN = 4 * HALF_HOUR

    const val FAVORITES = "favorites"
    const val ALL = "all"
    private const val GROUP = "group:"

    // Gaps shorter than this between programs are closed up rather than shown as a sliver.
    private const val MIN_GAP = 60 * 1000L

    /** One box in a channel's row: a program cut to the grid's time, or a gap with no guide data. */
    data class Cell(val start: Long, val end: Long, val program: Program?)

    /**
     * A channel's row from [from] until [to] (which must be later): [programs], in start order,
     * cut to that time, with the gaps between them filled, so every moment belongs to exactly one
     * cell. Where programs overlap, the earlier one wins.
     */
    fun cells(programs: List<Program>, from: Long, to: Long): List<Cell> {
        val cells = ArrayList<Cell>()
        var at = from
        for (program in programs) {
            if (program.start >= to) break
            val end = minOf(program.end, to)
            if (end <= at) continue
            val start = maxOf(program.start, at)
            if (start - at >= MIN_GAP) cells += Cell(at, start, null)
            cells += Cell(if (start - at < MIN_GAP) at else start, end, program)
            at = end
        }
        if (at < to) {
            if (to - at < MIN_GAP && cells.isNotEmpty()) cells[cells.lastIndex] = cells.last().copy(end = to)
            else cells += Cell(at, to, null)
        }
        return cells
    }

    /** The cell on screen at [time]: the first that hasn't ended by then, or the last. */
    fun indexAt(cells: List<Cell>, time: Long): Int = cells.indexOfFirst { it.end > time }.let { if (it < 0) cells.lastIndex else it }

    /** The half hour [time] falls in. Matches local half hours in every time zone on a whole or half-hour offset. */
    fun halfHour(time: Long): Long = time - Math.floorMod(time, HALF_HOUR)

    /**
     * Where the grid's left edge goes so the start of [cell] can be seen. It stays put while the
     * cell starts inside the window before its last half hour, and never goes back past [origin].
     */
    fun scroll(viewStart: Long, cell: Cell, origin: Long): Long = when {
        cell.start < viewStart -> maxOf(origin, halfHour(cell.start))
        cell.start >= viewStart + SPAN - HALF_HOUR -> maxOf(origin, halfHour(cell.start) - HALF_HOUR)
        else -> viewStart
    }

    fun groupFilter(group: Group) = GROUP + group.id

    /** The channels a filter shows: [FAVORITES], [ALL], or a group from [groupFilter]. */
    fun filter(channels: List<Channel>, filter: String, favorites: Set<Int>): List<Channel> = when {
        filter == FAVORITES -> channels.filter { it.streamId in favorites }
        filter.startsWith(GROUP) -> filter.removePrefix(GROUP).let { id -> channels.filter { it.groupId == id } }
        else -> channels
    }
}
