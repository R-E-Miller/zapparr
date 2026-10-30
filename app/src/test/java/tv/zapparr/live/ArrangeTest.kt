package tv.zapparr.live

import org.junit.Assert.assertEquals
import org.junit.Test

class ArrangeTest {

    private fun channel(id: Int, name: String = "Ch $id", group: String? = null) = Channel(id, id, name, null, id.toString(), group, 0)

    private val server = listOf(channel(1, "Zulu", "news"), channel(2, "alpha", "sports"), channel(3, "Mike", "news"), channel(4, "Bravo"))

    private fun ids(channels: List<Channel>) = channels.map { it.streamId }

    @Test
    fun yourOrderFirstThenTheRestInTheServersOrder() {
        val arrangement = Arrangement(order = listOf(3, 1))
        assertEquals(listOf(3, 1, 2, 4), ids(Arrange.channels(server, arrangement, ChannelOrder.YOURS, showHidden = false)))
        assertEquals(listOf(1, 2, 3, 4), ids(Arrange.channels(server, arrangement, ChannelOrder.NUMBER, showHidden = false)))
        assertEquals(listOf(2, 4, 3, 1), ids(Arrange.channels(server, arrangement, ChannelOrder.NAME, showHidden = false)))
    }

    @Test
    fun hiddenChannelsAndGroupsStayOutUnlessAskedFor() {
        val arrangement = Arrangement(hidden = setOf(2), hiddenGroups = setOf("news"))
        assertEquals(listOf(4), ids(Arrange.channels(server, arrangement, ChannelOrder.YOURS, showHidden = false)))
        assertEquals(listOf(1, 2, 3, 4), ids(Arrange.channels(server, arrangement, ChannelOrder.YOURS, showHidden = true)))
    }

    @Test
    fun renamedChannelsSortByTheirNewName() {
        val arrangement = Arrangement(names = mapOf(1 to "Aardvark TV"))
        val shown = Arrange.channels(server, arrangement, ChannelOrder.NAME, showHidden = false)
        assertEquals("Aardvark TV", shown.first().name)
        assertEquals(listOf(1, 2, 4, 3), ids(shown))
    }

    @Test
    fun aMovedChannelTradesPlacesWithItsNeighborInView() {
        val all = listOf(1, 2, 3, 4, 5)
        assertEquals(listOf(1, 3, 2, 4, 5), Arrange.move(all, all, 3, -1))
        assertEquals(listOf(1, 2, 4, 3, 5), Arrange.move(all, all, 3, 1))
        // Within a filter showing only 1, 3 and 5: down from 3 goes past 4, after 5.
        assertEquals(listOf(1, 2, 4, 5, 3), Arrange.move(all, listOf(1, 3, 5), 3, 1))
        // Up from the top, or a channel not in view: nothing moves.
        assertEquals(all, Arrange.move(all, all, 1, -1))
        assertEquals(all, Arrange.move(all, listOf(1, 3), 2, 1))
    }

    @Test
    fun groupsInYourOrderAndShiftedOneStep() {
        val groups = listOf(Group("a", "A"), Group("b", "B"), Group("c", "C"))
        assertEquals(listOf("c", "a", "b"), Arrange.groups(groups, Arrangement(groupOrder = listOf("c")), showHidden = false).map { it.id })
        assertEquals(listOf("a", "c"), Arrange.groups(groups, Arrangement(hiddenGroups = setOf("b")), showHidden = false).map { it.id })
        assertEquals(listOf("b", "a", "c"), Arrange.shift(listOf("a", "b", "c"), "b", -1))
        assertEquals(listOf("a", "b", "c"), Arrange.shift(listOf("a", "b", "c"), "c", 1))
    }
}
