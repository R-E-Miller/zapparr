package tv.zapparr.live

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VodTest {

    private val server = Server("http://nas:9191", "living room", "p&ss")

    // Flat fields as XcClient streams them: strings and numbers both arrive as text.
    private fun fields(json: String): Map<String, Any?> {
        val o = JSONObject(json)
        return o.keys().asSequence().associateWith { key -> o.get(key).takeUnless { it == JSONObject.NULL }?.toString() }
    }

    @Test
    fun urlsForMoviesEpisodesAndCategories() {
        assertEquals(
            "http://nas:9191/player_api.php?username=living+room&password=p%26ss&action=get_vod_streams&category_id=12",
            Xc.apiUrl(server, "get_vod_streams", "category_id" to "12"),
        )
        val movie = Movie(31, "Heat", null, 0, null, null, "mkv")
        assertEquals("http://nas:9191/movie/living%20room/p%26ss/31.mkv", Xc.movieUrl(server, movie))
        val episode = Episode(77, 1, 2, "Pilot", "mp4", null, null, 0)
        assertEquals("http://nas:9191/series/living%20room/p%26ss/77.mp4", Xc.episodeUrl(server, episode))
    }

    @Test
    fun dispatcharrMoviesAreRead() {
        // As Dispatcharr's xc_get_vod_streams sends one: added as a string, rating raw, year a number.
        val json = """{"num":1,"name":"Heat (EN)","stream_type":"movie","stream_id":31,
            "stream_icon":"http://nas:9191/api/vod/movies/31/logo/","rating":"8.3","rating_5based":4.15,
            "added":"1761600000","is_adult":0,"tmdb_id":"949","imdb_id":"","trailer":"","plot":"A heist.",
            "genre":"Crime","year":1995,"director":"","cast":"","release_date":"","category_id":"12",
            "category_ids":[12],"container_extension":"mkv","custom_sid":null,"direct_source":""}"""

        assertEquals(
            Movie(31, "Heat (EN)", "http://nas:9191/api/vod/movies/31/logo/", 1_761_600_000, "1995", 8.3, "mkv"),
            Vod.movie(fields(json), server.address),
        )
    }

    @Test
    fun moviesFromOtherServersAndGaps() {
        val sparse = Vod.movie(fields("""{"stream_id":"9","stream_icon":"/images/9.jpg","rating":"0","release_date":"2019-05-01"}"""), server.address)
        assertEquals(Movie(9, "9", "http://nas:9191/images/9.jpg", 0, "2019", null, "mp4"), sparse)
        assertNull(Vod.movie(fields("""{"name":"no id"}"""), server.address))
    }

    @Test
    fun dispatcharrSeriesAreRead() {
        val json = """{"num":1,"name":"The Wire","series_id":5,"cover":null,"plot":"","cast":"","director":"",
            "genre":"Drama","release_date":"2002","releaseDate":"2002","last_modified":"1761500000","rating":"9.3",
            "rating_5based":"4.65","backdrop_path":[],"youtube_trailer":"","episode_run_time":"","category_id":"4",
            "category_ids":[4],"tmdb_id":"","imdb_id":""}"""

        assertEquals(Series(5, "The Wire", null, 1_761_500_000, "2002", 9.3), Vod.series(fields(json), server.address))
    }

    @Test
    fun movieDetailsFromGetVodInfo() {
        val json = """{"info":{"name":"Heat","plot":"A heist goes wrong.","genre":"Crime, Drama","director":"Michael Mann",
            "actors":"Al Pacino","cast":"Al Pacino, Robert De Niro","year":1995,"rating":8.3,
            "backdrop_path":["http://nas:9191/backdrops/31.jpg"],"bitrate":0,"video":{},"audio":{}},
            "movie_data":{"stream_id":31,"name":"Heat","added":"1761600000","category_id":"12","container_extension":"mkv"}}"""

        assertEquals(
            Details("A heist goes wrong.", "Crime, Drama", "Michael Mann", "Al Pacino, Robert De Niro", "http://nas:9191/backdrops/31.jpg", "1995", 8.3, 0),
            Vod.movieDetails(json, server.address),
        )
    }

    @Test
    fun seasonsFromGetSeriesInfo() {
        // Dispatcharr: episodes by season number, the description in "overview", the length in duration_secs.
        val json = """{"seasons":[{"season_number":1,"name":"Season 1"},{"season_number":2,"name":"Season 2"}],
            "info":{"name":"The Wire","plot":"Baltimore.","genre":"Drama","cast":"Dominic West","backdrop_path":"/b/5.jpg","rating":"9.3"},
            "episodes":{
              "2":[{"id":202,"season":2,"episode_num":1,"title":"Ebb Tide","container_extension":"mkv",
                    "info":{"overview":"The docks.","movie_image":null,"duration_secs":3480,"duration":"00:58:00"}}],
              "1":[{"id":102,"season":1,"episode_num":2,"title":"The Detail","container_extension":"mkv","info":{"duration":"00:59:30"}},
                   {"id":101,"season":1,"episode_num":1,"title":"The Target","container_extension":"mkv","info":{"overview":"A trial."}}]
            }}"""

        val seasons = Vod.seasons(json, server.address)
        assertEquals(listOf(1, 2), seasons.numbers)
        assertEquals(listOf(101, 102, 202), seasons.episodes.map { it.id })
        assertEquals(Episode(202, 2, 1, "Ebb Tide", "mkv", "The docks.", null, 3480), seasons.episodes[2])
        assertEquals(3570, seasons.episodes[1].seconds) // from "00:59:30"
        assertEquals("http://nas:9191/b/5.jpg", seasons.details.backdrop)
        assertEquals(9.3, seasons.details.rating!!, 0.0)
    }

    @Test
    fun seasonsAsAListOfListsWithStringIds() {
        val json = """{"info":{},"episodes":[[{"id":"7","episode_num":"3","title":"Three","season":"1"}]]}"""
        assertEquals(listOf(Episode(7, 1, 3, "Three", "mp4", null, null, 0)), Vod.seasons(json, server.address).episodes)
    }

    @Test
    fun newestKeepsOnlyTheMostRecent() {
        val newest = Vod.Newest<Pair<String, Long>>(3) { it.second }
        listOf("a" to 5L, "b" to 1L, "c" to 9L, "d" to 3L, "e" to 7L).forEach(newest::offer)
        assertEquals(listOf("c", "e", "a"), newest.newestFirst().map { it.first })
    }
}
