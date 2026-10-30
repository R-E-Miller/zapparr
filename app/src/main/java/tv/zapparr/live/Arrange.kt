package tv.zapparr.live

/**
 * How the user arranged the server's channels, as TiviMate allows: their own [order] (stream ids;
 * channels it doesn't name follow in the server's order), [hidden] channels, [hiddenGroups],
 * new [names] for channels, and the [groupOrder] of the filter chips.
 */
data class Arrangement(
    val order: List<Int> = emptyList(),
    val hidden: Set<Int> = emptySet(),
    val hiddenGroups: Set<String> = emptySet(),
    val names: Map<Int, String> = emptyMap(),
    val groupOrder: List<String> = emptyList(),
)

/** How channels are sorted, from Settings > Guide > Channel order. */
enum class ChannelOrder { YOURS, NUMBER, NAME }

/** Arranging the server's channels and groups as the user wants them. Free of Android, so it is unit-tested. */
object Arrange {

    fun isHidden(channel: Channel, arrangement: Arrangement) =
        channel.streamId in arrangement.hidden || channel.groupId in arrangement.hiddenGroups

    /**
     * The channels as Zapparr shows them: renamed, in [sort] order (the user's own, or by number
     * or name), without the hidden ones unless [showHidden].
     */
    fun channels(server: List<Channel>, arrangement: Arrangement, sort: ChannelOrder, showHidden: Boolean): List<Channel> {
        val named = server.map { channel -> arrangement.names[channel.streamId]?.let { channel.copy(name = it) } ?: channel }
        val sorted = when (sort) {
            ChannelOrder.NUMBER -> named.sortedWith(compareBy({ it.number }, { it.name }))
            ChannelOrder.NAME -> named.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            ChannelOrder.YOURS -> inOrder(named, arrangement.order) { it.streamId }
        }
        return if (showHidden) sorted else sorted.filterNot { isHidden(it, arrangement) }
    }

    /** The groups as their chips show them: the user's order, then the rest in the server's; hidden ones left out unless [showHidden]. */
    fun groups(server: List<Group>, arrangement: Arrangement, showHidden: Boolean): List<Group> {
        val sorted = inOrder(server, arrangement.groupOrder) { it.id }
        return if (showHidden) sorted else sorted.filterNot { it.id in arrangement.hiddenGroups }
    }

    /**
     * [all] (every channel's stream id, in the order shown) once [id] has moved [by] places (−1 up,
     * +1 down) among the channels in [view] (those the user can see where they are, in the same
     * order): it trades places with its neighbor there. Unchanged at either end.
     */
    fun move(all: List<Int>, view: List<Int>, id: Int, by: Int): List<Int> {
        val at = view.indexOf(id)
        val neighbor = view.getOrNull(at + by) ?: return all
        if (at < 0) return all
        val rest = all.filter { it != id }.toMutableList()
        val target = rest.indexOf(neighbor).takeIf { it >= 0 } ?: return all
        rest.add(if (by > 0) target + 1 else target, id)
        return rest
    }

    /** [list] with [id] moved [by] places, round neither end. */
    fun <T> shift(list: List<T>, id: T, by: Int): List<T> {
        val at = list.indexOf(id)
        val to = at + by
        if (at < 0 || to !in list.indices) return list
        return list.toMutableList().apply { add(to, removeAt(at)) }
    }

    // [items] in [order] (by key), then the rest in their own order.
    private fun <T, K> inOrder(items: List<T>, order: List<K>, key: (T) -> K): List<T> {
        if (order.isEmpty()) return items
        val rank = order.withIndex().associate { (i, k) -> k to i }
        return items.withIndex()
            .sortedWith(compareBy({ rank[key(it.value)] ?: Int.MAX_VALUE }, { it.index }))
            .map { it.value }
    }
}
