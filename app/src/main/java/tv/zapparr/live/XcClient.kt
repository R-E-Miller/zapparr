package tv.zapparr.live

import android.util.JsonReader
import android.util.JsonToken
import android.util.Xml
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Everything the Guide shows: groups and channels in number order, and programs by XMLTV channel
 * id, known [until] (epoch milliseconds).
 */
data class Lineup(val groups: List<Group>, val channels: List<Channel>, val programs: Map<String, List<Program>>, val until: Long)

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
        return Lineup(groups, channels, programs, to)
    }

    fun movieGroups(): List<Group> = Xc.parseGroups(text(Xc.apiUrl(server, "get_vod_categories")))

    fun seriesGroups(): List<Group> = Xc.parseGroups(text(Xc.apiUrl(server, "get_series_categories")))

    /** The movies in [group], in the server's order. */
    fun movies(group: Group): List<Movie> = buildList {
        entries(Xc.apiUrl(server, "get_vod_streams", "category_id" to group.id)) { Vod.movie(it, server.address)?.let(this::add) }
    }

    fun series(group: Group): List<Series> = buildList {
        entries(Xc.apiUrl(server, "get_series", "category_id" to group.id)) { Vod.series(it, server.address)?.let(this::add) }
    }

    /** The [count] most recently added movies, read from the whole library one at a time. */
    fun newestMovies(count: Int): List<Movie> {
        val newest = Vod.Newest(count, Movie::added)
        entries(Xc.apiUrl(server, "get_vod_streams")) { Vod.movie(it, server.address)?.let(newest::offer) }
        return newest.newestFirst()
    }

    fun newestSeries(count: Int): List<Series> {
        val newest = Vod.Newest(count, Series::updated)
        entries(Xc.apiUrl(server, "get_series")) { Vod.series(it, server.address)?.let(newest::offer) }
        return newest.newestFirst()
    }

    fun movieDetails(movie: Movie): Details =
        Vod.movieDetails(text(Xc.apiUrl(server, "get_vod_info", "vod_id" to movie.id.toString())), server.address)

    fun seasons(series: Series): Seasons =
        Vod.seasons(text(Xc.apiUrl(server, "get_series_info", "series_id" to series.id.toString())), server.address)

    // Streams a JSON list, handing each object's flat fields (nested values skipped) to [each].
    // Anything but a list (some servers send {} for an empty category) is no entries.
    private fun entries(url: String, each: (Map<String, Any?>) -> Unit) = open(url).use { stream ->
        val reader = JsonReader(stream.reader(Charsets.UTF_8))
        try {
            if (reader.peek() != JsonToken.BEGIN_ARRAY) return@use
            reader.beginArray()
            while (reader.hasNext()) {
                if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                    reader.skipValue()
                    continue
                }
                val fields = HashMap<String, Any?>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    when (reader.peek()) {
                        JsonToken.STRING, JsonToken.NUMBER -> fields[name] = reader.nextString()
                        JsonToken.BOOLEAN -> fields[name] = reader.nextBoolean()
                        JsonToken.NULL -> reader.nextNull()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                each(fields)
            }
        } catch (e: IllegalStateException) { // not JSON, or not the JSON expected
            throw XcException(Problem.NOT_XC, e)
        }
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
