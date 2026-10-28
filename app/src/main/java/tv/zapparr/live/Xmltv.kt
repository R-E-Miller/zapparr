package tv.zapparr.live

import org.xmlpull.v1.XmlPullParser
import java.time.LocalDateTime
import java.time.ZoneOffset

/** One airing in the guide. Times are epoch milliseconds. */
data class Program(val start: Long, val end: Long, val title: String, val description: String?)

/** Reads an XMLTV guide. Free of Android apart from the pull parser interface, so it is unit-tested. */
object Xmltv {

    /**
     * The programs on [channels] (XMLTV channel ids) that overlap [from] until [to], by channel id
     * and in start order. Streams through [parser], keeping nothing else, so a guide much larger
     * than the box's memory can be read.
     */
    fun read(parser: XmlPullParser, channels: Set<String>, from: Long, to: Long): Map<String, List<Program>> {
        val programs = HashMap<String, MutableList<Program>>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG || parser.name != "programme") continue
            val channel = parser.getAttributeValue(null, "channel")
            val start = parser.getAttributeValue(null, "start")?.let(::parseTime)
            val end = parser.getAttributeValue(null, "stop")?.let(::parseTime)
            if (channel !in channels || start == null || end == null || end <= start || end <= from || start >= to) {
                skip(parser)
                continue
            }
            val program = readProgram(parser, start, end) ?: continue
            programs.getOrPut(channel!!) { ArrayList() }.add(program)
        }
        return programs.mapValues { (_, list) -> list.sortedBy { it.start } }
    }

    /** Parses an XMLTV time such as "20240101103000 +0000"; no offset means UTC. */
    fun parseTime(text: String): Long? {
        val match = TIME.matchEntire(text.trim()) ?: return null
        val (digits, offset) = match.destructured
        return try {
            val local = LocalDateTime.of(
                digits.substring(0, 4).toInt(), digits.substring(4, 6).toInt(), digits.substring(6, 8).toInt(),
                digits.substring(8, 10).toInt(), digits.substring(10, 12).toInt(),
                if (digits.length == 14) digits.substring(12, 14).toInt() else 0,
            )
            val zone = if (offset.isEmpty()) ZoneOffset.UTC else ZoneOffset.of(offset.substring(0, 3) + ":" + offset.substring(3))
            local.toInstant(zone).toEpochMilli()
        } catch (e: RuntimeException) { // DateTimeException: a day or offset out of range
            null
        }
    }

    // On a <programme> start tag; returns on its end tag. The first title and description win.
    private fun readProgram(parser: XmlPullParser, start: Long, end: Long): Program? {
        var title: String? = null
        var description: String? = null
        val depth = parser.depth
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == depth) break
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "title" -> parser.nextText().trim().let { if (title == null && it.isNotEmpty()) title = it }
                "desc" -> parser.nextText().trim().let { if (description == null && it.isNotEmpty()) description = it }
                else -> skip(parser)
            }
        }
        return title?.let { Program(start, end, it, description) }
    }

    // On a start tag; returns on its matching end tag.
    private fun skip(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private val TIME = Regex("""(\d{14}|\d{12})\s*([+-]\d{4})?""")
}
