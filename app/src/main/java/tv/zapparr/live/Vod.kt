package tv.zapparr.live

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A movie or a series: what a poster tile shows. [rating] is out of 10. */
sealed interface LibraryItem {
    val id: Int
    val name: String
    val poster: String?
    val year: String?
    val rating: Double?
}

/** A movie as the lists give it: enough for a poster tile and to play it. [added] is epoch seconds, 0 if unknown. */
data class Movie(
    override val id: Int,
    override val name: String,
    override val poster: String?,
    val added: Long,
    override val year: String?,
    override val rating: Double?,
    val extension: String,
    val group: String?,
) : LibraryItem

/** A series as the lists give it. [updated] (epoch seconds, 0 if unknown) stands in for when it was added. */
data class Series(
    override val id: Int,
    override val name: String,
    override val poster: String?,
    val updated: Long,
    override val year: String?,
    override val rating: Double?,
    val group: String?,
) : LibraryItem

/** The Library deck's rows for movies or series: the newest, then each category that has any, in the server's order. */
data class Library<out T>(val newest: List<T>, val shelves: List<Pair<Group, List<T>>>)

/** Everything the Library deck shows. */
data class Catalog(val movies: Library<Movie>, val series: Library<Series>)

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
 * Movies and series from an Xtream Codes server: the user's vod-organizer, or Dispatcharr.
 * Network-free, so it is unit-tested; [XcClient] does the fetching.
 *
 * List entries are read from flat fields (nested values left out), so a library of tens of
 * thousands can be streamed one entry at a time. XC servers disagree about types, numbers as
 * strings and the other way round, so every field is read leniently.
 */
object Vod {

    const val NEWEST = 30

    /** A movie from one get_vod_streams entry, or null without an id. Relative posters are resolved against [address]. */
    fun movie(fields: Map<String, Any?>, address: String): Movie? {
        val id = int(fields["stream_id"]) ?: return null
        return Movie(
            id = id,
            name = text(fields["name"]) ?: id.toString(),
            poster = tmdb(image(fields["stream_icon"], address) ?: image(fields["cover"], address), POSTER),
            added = long(fields["added"]) ?: 0,
            year = year(fields["year"]) ?: year(fields["release_date"]),
            rating = rating(fields["rating"]),
            extension = text(fields["container_extension"]) ?: DEFAULT_EXTENSION,
            group = text(fields["category_id"]),
        )
    }

    /** A series from one get_series entry, or null without an id. */
    fun series(fields: Map<String, Any?>, address: String): Series? {
        val id = int(fields["series_id"]) ?: return null
        return Series(
            id = id,
            name = text(fields["name"]) ?: id.toString(),
            poster = tmdb(image(fields["cover"], address) ?: image(fields["stream_icon"], address), POSTER),
            updated = long(fields["last_modified"]) ?: 0,
            year = year(fields["release_date"]) ?: year(fields["releaseDate"]),
            rating = rating(fields["rating"]),
            group = text(fields["category_id"]),
        )
    }

    /**
     * Sorts [items] onto [groups]' shelves, leaving out empty ones and items in no listed group.
     * Newest goes by [added], then by [id]: vod-organizer sends no dates, and providers number new
     * items upward.
     */
    fun <T> library(groups: List<Group>, items: List<T>, group: (T) -> String?, added: (T) -> Long, id: (T) -> Int): Library<T> {
        val byGroup = items.groupBy(group)
        val shelves = groups.mapNotNull { g -> byGroup[g.id]?.let { g to it } }
        val newest = items.sortedWith(compareByDescending(added).thenByDescending(id)).take(NEWEST)
        return Library(newest, shelves)
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

    /**
     * [url] at TMDb's [size] when it is a TMDb image. vod-organizer sends w500 posters and
     * original-size backdrops (often several megabytes); smaller sizes suit a 2 GB box.
     */
    fun tmdb(url: String?, size: String): String? = url?.replace(TMDB_SIZE, "$1$size/")

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
            image = tmdb(image(info.opt("movie_image"), address), STILL),
            seconds = seconds(info),
        )
    }

    private fun details(info: JSONObject, address: String) = Details(
        plot = text(info.opt("plot")) ?: text(info.opt("description")),
        genre = text(info.opt("genre")),
        director = text(info.opt("director")),
        cast = text(info.opt("cast")) ?: text(info.opt("actors")),
        backdrop = tmdb(
            when (val backdrop = info.opt("backdrop_path")) {
                is JSONArray -> image(backdrop.opt(0), address)
                else -> image(backdrop, address)
            },
            BACKDROP,
        ),
        year = year(info.opt("year")) ?: year(info.opt("release_date")) ?: year(info.opt("releasedate")) ?: year(info.opt("releaseDate")),
        rating = rating(info.opt("rating")),
        seconds = seconds(info),
    )

    // duration_secs, or a duration like "01:42:10". Movies from Dispatcharr and vod-organizer have neither.
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
    private const val POSTER = "w342"
    private const val BACKDROP = "w1280"
    private const val STILL = "w300"
    private val TMDB_SIZE = Regex("""^(https?://image\.tmdb\.org/t/p/)[^/]+/""")
}
