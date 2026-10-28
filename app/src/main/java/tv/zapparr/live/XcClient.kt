package tv.zapparr.live

import android.util.Xml
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Everything the Guide shows: groups and channels in number order, and programs by XMLTV channel id. */
data class Lineup(val groups: List<Group>, val channels: List<Channel>, val programs: Map<String, List<Program>>)

/** Fetches from an Xtream Codes server. Blocking: call it off the main thread. */
class XcClient(private val server: Server) {

    fun login() = Xc.checkLogin(text(Xc.apiUrl(server)))

    /** Channels and groups, plus the guide from [from] until [to] (epoch milliseconds). */
    fun lineup(from: Long, to: Long): Lineup {
        val groups = Xc.parseGroups(text(Xc.apiUrl(server, "get_live_categories")))
        val channels = Xc.parseChannels(text(Xc.apiUrl(server, "get_live_streams")), server.address)
        val ids = channels.mapTo(HashSet()) { it.epgId }
        // A server without a guide still has channels to show.
        val programs = try {
            open(Xc.xmltvUrl(server)).use { stream ->
                val parser = Xml.newPullParser().apply { setInput(stream, null) }
                Xmltv.read(parser, ids, from, to)
            }
        } catch (e: Exception) { // IOException, XmlPullParserException
            emptyMap()
        }
        return Lineup(groups, channels, programs)
    }

    private fun text(url: String) = open(url).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun open(url: String): InputStream {
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
            }
        } catch (e: Exception) { // MalformedURLException, IOException
            throw XcException(Problem.UNREACHABLE, e)
        }
        val code = try {
            connection.responseCode
        } catch (e: IOException) {
            connection.disconnect()
            throw XcException(Problem.UNREACHABLE, e)
        }
        if (code == HttpURLConnection.HTTP_OK) return connection.inputStream
        connection.disconnect()
        throw XcException(
            when (code) {
                HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> Problem.WRONG_LOGIN
                else -> Problem.NOT_XC
            },
        )
    }

    private companion object {
        const val CONNECT_TIMEOUT = 5_000
        // The XMLTV guide can take Dispatcharr a while to build.
        const val READ_TIMEOUT = 60_000
    }
}
