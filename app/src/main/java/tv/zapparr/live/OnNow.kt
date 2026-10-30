package tv.zapparr.live

/**
 * The Apps deck's On now row: the channels watched lately, then the favorites, each with what's
 * airing. Free of Android, so it is unit-tested.
 */
object OnNow {

    const val LIMIT = 12

    /** The channels watched lately (stream ids, newest first) once [id] is watched. */
    fun watched(recent: List<Int>, id: Int): List<Int> = (listOf(id) + recent.filter { it != id }).take(LIMIT)

    /** The row's channels: those watched lately, newest first, then the favorites in number order. */
    fun channels(recent: List<Int>, favorites: Set<Int>, channels: List<Channel>): List<Channel> {
        val byId = channels.associateBy { it.streamId }
        return (recent.mapNotNull(byId::get) + channels.filter { it.streamId in favorites }).distinct().take(LIMIT)
    }

    /** What [programs] (in time order) have on at [now], if anything. */
    fun airing(programs: List<Program>?, now: Long): Program? = programs?.firstOrNull { now >= it.start && now < it.end }

    /** How far [program] is at [now], 0 to 1. */
    fun progress(program: Program, now: Long): Float =
        if (program.end <= program.start) 0f else ((now - program.start).toFloat() / (program.end - program.start)).coerceIn(0f, 1f)
}
