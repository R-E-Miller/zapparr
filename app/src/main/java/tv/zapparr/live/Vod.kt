package tv.zapparr.live

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.PriorityQueue

/** A movie as the lists give it: enough for a poster tile and to play it. [added] is epoch seconds, 0 if unknown. */
data class Movie(
    val id: Int,
    val name: String,
    val poster: String?,
    val added: Long,
    val year: String?,
    val rating: Double?,
    val extension: String,
)

/** A series as the lists give it. [updated] (epoch seconds) stands in for when it was added. */
data class Series(
    val id: Int,
    val name: String,
    val poster: String?,
    val updated: Long,
    val year: String?,
    val rating: Double?,
)

/** What the details screen adds to a movie or a series. [rating] is out of 10. */
data class Details(
    val plot: String?,
    val genre: String?,
    val director: String?,
    val cast: String?,
    val backdrop: String?,
    val year: String?,
    val rating: Double?,
    val seconds: Int,
)

data class Episode(
    val id: Int,
    val season: Int,
    val number: Int,
    val title: String,
    val extension: String,
    val plot: String?,
    val image: String?,
    val seconds: Int,
)

/** A series' details and its episodes, in season and episode order. */
data class Seasons(val details: Details, val episodes: List<Episode>) {
    val numbers: List<Int> get() = episodes.map { it.season }.distinct()
}

/**
 * Movies and series from the Xtream Codes API as Dispatcharr serves them. Network-free, so it is
 * unit-tested; [XcClient] does the fetching.
 *
 * List entries are read from flat fields (a list's nested values left out), so a large library can
 * be streamed one entry at a time rather than held whole. Other XC servers send numbers as strings
 * and the other way round, so every field is read leniently.
 */
object Vod {

    /** A movie from one get_vod_streams entry, or null without an id. Relative posters are resolved against [address]. */
    fun movie(fields: Map<String, Any?>, address: String): Movie? {
        val id = int(fields["stream_id"]) ?: return null
        return Movie(
            id = id,
            name = text(fields["name"]) ?: id.toString(),
            poster = image(fields["stream_icon"], address),
            added = long(fields["added"]) ?: 0,
            year = year(fields["year"]) ?: year(fields["release_date"]),
            rating = rating(fields["rating"]),
            extension = text(fields["container_extension"]) ?: DEFAULT_EXTENSION,
        )
    }

    /** A series from one get_series entry, or null without an id. */
    fun series(fields: Map<String, Any?>, address: String): Series? {
        val id = int(fields["series_id"]) ?: return null
        return Series(
            id = id,
            name = text(fields["name"]) ?: id.toString(),
            poster = image(fields["cover"], address),
            updated = long(fields["last_modified"]) ?: 0,
            year = year(fields["release_date"]) ?: year(fields["releaseDate"]),
            rating = rating(fields["rating"]),
        )
    }

    /** The details in a get_vod_info reply. */
    fun movieDetails(json: String, address: String): Details = details(parse(json).optJSONObject("info") ?: JSONObject(), address)

    /** The details and episodes in a get_series_info reply. */
    fun seasons(json: String, address: String): Seasons {
        val reply = parse(json)
        val episodes = ArrayList<Episode>()
        // A map from season number to that season's episodes; some servers send a list of lists instead.
        when (val bySeason = reply.opt("episodes")) {
            is JSONObject -> bySeason.keys().forEach { key -> bySeason.optJSONArray(key)?.let { episodes += episodes(it, int(key), address) } }
            is JSONArray -> for (i in 0 until bySeason.length()) bySeason.optJSONArray(i)?.let { episodes += episodes(it, null, address) }
        }
        val details = details(reply.optJSONObject("info") ?: JSONObject(), address)
        return Seasons(details, episodes.sortedWith(compareBy({ it.season }, { it.number })))
    }

    /** Keeps the [count] newest of the items offered to it, by [time], without holding the rest. */
    class Newest<T>(private val count: Int, private val time: (T) -> Long) {
        private val kept = PriorityQueue<T>(count + 1, compareBy(time))

        fun offer(item: T) {
            kept.add(item)
            if (kept.size > count) kept.poll()
        }

        fun newestFirst(): List<T> = kept.sortedByDescending(time)
    }

    private fun episodes(list: JSONArray, season: Int?, address: String): List<Episode> = (0 until list.length()).mapNotNull { i ->
        val episode = list.optJSONObject(i) ?: return@mapNotNull null
        val id = int(episode.opt("id")) ?: return@mapNotNull null
        val info = episode.optJSONObject("info") ?: JSONObject()
        Episode(
            id = id,
            season = int(episode.opt("season")) ?: season ?: 1,
            number = int(episode.opt("episode_num")) ?: 0,
            title = text(episode.opt("title")) ?: text(info.opt("name")) ?: "",
            extension = text(episode.opt("container_extension")) ?: DEFAULT_EXTENSION,
            plot = text(info.opt("overview")) ?: text(info.opt("plot")),
            image = image(info.opt("movie_image"), address),
            seconds = seconds(info),
        )
    }

    private fun details(info: JSONObject, address: String) = Details(
        plot = text(info.opt("plot")) ?: text(info.opt("description")),
        genre = text(info.opt("genre")),
        director = text(info.opt("director")),
        cast = text(info.opt("cast")) ?: text(info.opt("actors")),
        backdrop = when (val backdrop = info.opt("backdrop_path")) {
            is JSONArray -> image(backdrop.opt(0), address)
            else -> image(backdrop, address)
        },
        year = year(info.opt("year")) ?: year(info.opt("release_date")) ?: year(info.opt("releasedate")) ?: year(info.opt("releaseDate")),
        rating = rating(info.opt("rating")),
        seconds = seconds(info),
    )

    // duration_secs, or a duration like "01:42:10". Dispatcharr's movies have neither.
    private fun seconds(info: JSONObject): Int {
        int(info.opt("duration_secs"))?.takeIf { it > 0 }?.let { return it }
        val parts = text(info.opt("duration"))?.split(':')?.map { it.toIntOrNull() ?: return 0 } ?: return 0
        return parts.fold(0) { total, part -> total * 60 + part }
    }

    private fun parse(json: String) = try {
        JSONObject(json)
    } catch (e: JSONException) {
        throw XcException(Problem.NOT_XC, e)
    }

    private fun text(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is String -> value.trim().takeIf { it.isNotEmpty() }
        is Number, is Boolean -> value.toString()
        else -> null
    }

    private fun number(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        else -> text(value)?.toDoubleOrNull()
    }

    private fun int(value: Any?) = number(value)?.toInt()

    private fun long(value: Any?) = number(value)?.toLong()

    // 0 is how XC servers say "no rating".
    private fun rating(value: Any?) = number(value)?.takeIf { it > 0 }

    // A year, or the year of a date like "2019-05-01".
    private fun year(value: Any?) = text(value)?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }

    private fun image(value: Any?, address: String) = text(value)?.let { Xc.resolve(address, it) }

    private const val DEFAULT_EXTENSION = "mp4"
}
