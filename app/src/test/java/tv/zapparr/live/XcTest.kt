package tv.zapparr.live

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class XcTest {

    private val server = Server("http://nas:9191", "living room", "p&ss")

    @Test
    fun urlsCarryTheLoginEncoded() {
        assertEquals("http://nas:9191/player_api.php?username=living+room&password=p%26ss", Xc.apiUrl(server))
        assertEquals(
            "http://nas:9191/player_api.php?username=living+room&password=p%26ss&action=get_live_streams",
            Xc.apiUrl(server, "get_live_streams"),
        )
        assertEquals("http://nas:9191/xmltv.php?username=living+room&password=p%26ss", Xc.xmltvUrl(server))
        assertEquals("http://nas:9191/live/living%20room/p%26ss/42", Xc.liveUrl(server, channel(42)))
    }

    @Test
    fun anActiveAccountPasses() {
        Xc.checkLogin("""{"user_info":{"auth":1,"status":"Active"},"server_info":{"port":"9191"}}""")
    }

    @Test
    fun loginProblemsAreTold() {
        assertProblem(Problem.WRONG_LOGIN) { Xc.checkLogin("""{"user_info":{"auth":0}}""") }
        assertProblem(Problem.ACCOUNT_INACTIVE) { Xc.checkLogin("""{"user_info":{"auth":"1","status":"Expired"}}""") }
        assertProblem(Problem.NOT_XC) { Xc.checkLogin("<html>Dispatcharr</html>") }
        assertProblem(Problem.NOT_XC) { Xc.checkLogin("""{"detail":"Not found"}""") }
    }

    @Test
    fun groupsKeepTheServersOrder() {
        val json = """[{"category_id":"7","category_name":"News","parent_id":0},{"category_id":"3","category_name":"Sports"},{"category_name":"no id"}]"""

        assertEquals(listOf(Group("7", "News"), Group("3", "Sports")), Xc.parseGroups(json))
    }

    @Test
    fun dispatcharrChannelsAreReadInNumberOrder() {
        // As Dispatcharr sends them: numbers as numbers, the number as the guide id, logos may be null.
        val json = """[
            {"num":5,"name":"WXYZ","stream_type":"live","stream_id":12,"stream_icon":"http://nas:9191/api/channels/logos/3/cache/","epg_channel_id":"5","category_id":"7","tv_archive":0,"tv_archive_duration":0},
            {"num":2,"name":"News 24","stream_type":"live","stream_id":9,"stream_icon":null,"epg_channel_id":"2","category_id":"7","tv_archive":1,"tv_archive_duration":3}
        ]"""

        assertEquals(
            listOf(
                Channel(9, 2, "News 24", null, "2", "7", 3),
                Channel(12, 5, "WXYZ", "http://nas:9191/api/channels/logos/3/cache/", "5", "7", 0),
            ),
            Xc.parseChannels(json, server.address),
        )
    }

    @Test
    fun otherServersStringsAndGapsAreTolerated() {
        // Other XC servers send numbers as strings, relative logos, and leave fields empty.
        val json = """[
            {"num":"3","name":"Sports 1","stream_id":"30","stream_icon":"/logos/s1.png","epg_channel_id":"","category_id":null},
            {"name":"No number","stream_id":31},
            {"num":1,"name":"No stream id"}
        ]"""

        assertEquals(
            listOf(
                Channel(30, 3, "Sports 1", "http://nas:9191/logos/s1.png", "3", null, 0),
                Channel(31, 31, "No number", null, "31", null, 0),
            ),
            Xc.parseChannels(json, server.address),
        )
    }

    @Test
    fun aReplyThatIsNotAListIsNotXc() {
        assertProblem(Problem.NOT_XC) { Xc.parseChannels("""{"user_info":{"auth":0}}""", server.address) }
    }

    private fun channel(id: Int) = Channel(id, id, "Channel $id", null, id.toString(), null, 0)

    private fun assertProblem(expected: Problem, block: () -> Unit) {
        try {
            block()
            fail("expected $expected")
        } catch (e: XcException) {
            assertEquals(expected, e.problem)
        }
    }
}
