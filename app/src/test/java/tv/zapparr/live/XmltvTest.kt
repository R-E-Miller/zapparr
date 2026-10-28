package tv.zapparr.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.StringReader

class XmltvTest {

    @Test
    fun timesHonorTheirOffset() {
        assertEquals(1704103200000, Xmltv.parseTime("20240101100000 +0000"))
        assertEquals(1704103200000, Xmltv.parseTime("20240101050000 -0500"))
        assertEquals(1704103200000, Xmltv.parseTime("202401011000"))
        assertNull(Xmltv.parseTime("2024-01-01 10:00"))
        assertNull(Xmltv.parseTime("20241301100000 +0000"))
    }

    @Test
    fun keepsWantedChannelsInsideTheWindowInStartOrder() {
        val guide = read(
            setOf("2", "5"),
            from = time("1000"), to = time("1300"),
            """
            <channel id="2"><display-name>News 24</display-name><icon src="x.png"/></channel>
            <programme channel="2" start="20240101110000 +0000" stop="20240101120000 +0000">
              <title lang="en">Noon Report</title><desc>Headlines.</desc>
            </programme>
            <programme channel="2" start="20240101093000 +0000" stop="20240101110000 +0000">
              <title>Morning</title><title lang="es">Mañana</title>
              <credits><presenter>Someone</presenter></credits>
              <desc>  </desc>
            </programme>
            <programme channel="2" start="20240101080000 +0000" stop="20240101100000 +0000"><title>Over before the window</title></programme>
            <programme channel="2" start="20240101130000 +0000" stop="20240101140000 +0000"><title>Starts after it</title></programme>
            <programme channel="9" start="20240101100000 +0000" stop="20240101110000 +0000"><title>Not in the lineup</title></programme>
            <programme channel="5" start="20240101100000 +0000" stop="20240101100000 +0000"><title>Zero length</title></programme>
            <programme channel="5" start="20240101100000 +0000" stop="20240101110000 +0000"><desc>No title</desc></programme>
            """,
        )

        assertEquals(
            mapOf(
                "2" to listOf(
                    Program(time("0930"), time("1100"), "Morning", null),
                    Program(time("1100"), time("1200"), "Noon Report", "Headlines."),
                ),
            ),
            guide,
        )
    }

    private fun read(channels: Set<String>, from: Long, to: Long, body: String): Map<String, List<Program>> {
        val parser = KXmlParser().apply { setInput(StringReader("<?xml version=\"1.0\"?><tv>$body</tv>")) }
        return Xmltv.read(parser, channels, from, to)
    }

    private fun time(hhmm: String) = Xmltv.parseTime("20240101${hhmm}00 +0000")!!
}
