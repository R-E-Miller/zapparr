package tv.zapparr.live

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VodTest {

    private val server = Server("http://nas:8000", "living room", "p&ss")

    // Flat fields as XcClient streams them: strings and numbers both arrive as text, nested values not at all.
    private fun fields(json: String): Map<String, Any?> {
        val o = JSONObject(json)
        return o.keys().asSequence().filter { o.get(it) !is org.json.JSONArray && o.get(it) !is JSONObject }
            .associateWith { key -> o.get(key).takeUnless { it == JSONObject.NULL }?.toString() }
    }

    @Test
    fun urlsForMoviesEpisodesAndCategories() {
        assertEquals(
            "http://nas:8000/player_api.php?username=living+room&password=p%26ss&action=get_vod_info&vod_id=12",
            Xc.apiUrl(server, "get_vod_info", "vod_id" to "12"),
        )
        val movie = Movie(31, "Heat", null, 0, null, null, "mkv", null)
        assertEquals("http://nas:8000/movie/living%20room/p%26ss/31.mkv", Xc.movieUrl(server, movie))
        val episode = Episode(77, 1, 2, "Pilot", "mp4", null, null, 0)
        assertEquals("http://nas:8000/series/living%20room/p%26ss/77.mp4", Xc.episodeUrl(server, episode))
    }

    @Test
    fun vodOrganizerMoviesAreRead() {
        // As vod-organizer's build_movie makes one: a TMDb w500 poster, no date, the TMDb genre's id as category.
        val json = """{"num":4512,"name":"Heat (US) (1995)","stream_type":"movie","stream_id":88213,
            "stream_icon":"https://image.tmdb.org/t/p/w500/abc.jpg","cover":"https://image.tmdb.org/t/p/w500/abc.jpg",
            "backdrop_path":["https://image.tmdb.org/t/p/original/def.jpg"],"plot":"A heist.","cast":"","director":"",
            "rating":"7.9","year":"1995","youtube_trailer":"","category_id":"7","container_extension":"mkv"}"""

        assertEquals(
            Movie(88213, "Heat (US) (1995)", "https://image.tmdb.org/t/p/w342/abc.jpg", 0, "1995", 7.9, "mkv", "7"),
            Vod.movie(fields(json), server.address),
        )
    }

    @Test
    fun vodOrganizerSeriesAreRead() {
        val json = """{"num":1,"name":"The Office (UK) (2001)","series_id":5521,"cover":"","stream_icon":"",
            "backdrop_path":[],"plot":"","cast":"","director":"","genre":"Comedy","releaseDate":"2001","rating":"0",
            "youtube_trailer":"","category_id":"5"}"""

        assertEquals(Series(5521, "The Office (UK) (2001)", null, 0, "2001", null, "5"), Vod.series(fields(json), server.address))
    }

    @Test
    fun dispatcharrMoviesAndSeriesAreReadToo() {
        val movie = """{"num":1,"name":"Heat (EN)","stream_id":31,"stream_icon":null,"rating":"8.3","added":"1761600000",
            "year":1995,"category_id":"12","container_extension":"mp4"}"""
        assertEquals(Movie(31, "Heat (EN)", null, 1_761_600_000, "1995", 8.3, "mp4", "12"), Vod.movie(fields(movie), server.address))

        val series = """{"name":"The Wire","series_id":5,"cover":"/api/vod/series/5/logo/","release_date":"2002",
            "last_modified":"1761500000","rating":"9.3","category_id":"4"}"""
        assertEquals(
            Series(5, "The Wire", "http://nas:8000/api/vod/series/5/logo/", 1_761_500_000, "2002", 9.3, "4"),
            Vod.series(fields(series), server.address),
        )
        assertNull(Vod.movie(fields("""{"name":"no id"}"""), server.address))
    }

    @Test
    fun theLibraryHidesEmptyCategoriesAndPutsTheHighestIdsFirstWithoutDates() {
        fun movie(id: Int, group: String?) = Movie(id, "m$id", null, 0, null, null, "mp4", group)
        val groups = listOf(Group("1", "Action"), Group("2", "Talk"), Group("3", "Comedy"))
        val movies = listOf(movie(10, "3"), movie(30, "1"), movie(20, "3"), movie(40, "99"))

        val library = Vod.library(groups, movies, Movie::group, Movie::added, Movie::id)

        assertEquals(listOf("Action" to listOf(30), "Comedy" to listOf(10, 20)), library.shelves.map { (g, items) -> g.name to items.map { it.id } })
        assertEquals(listOf(40, 30, 20, 10), library.newest.map { it.id })
    }

    @Test
    fun datesWinOverIdsWhenTheServerSendsThem() {
        val old = Movie(99, "old", null, 1_000, null, null, "mp4", "1")
        val new = Movie(1, "new", null, 2_000, null, null, "mp4", "1")
        assertEquals(listOf(1, 99), Vod.library(emptyList(), listOf(old, new), Movie::group, Movie::added, Movie::id).newest.map { it.id })
    }

    @Test
    fun tmdbImagesComeAtTvSizes() {
        assertEquals("https://image.tmdb.org/t/p/w1280/def.jpg", Vod.tmdb("https://image.tmdb.org/t/p/original/def.jpg", "w1280"))
        assertEquals("http://nas:8000/posters/1.png", Vod.tmdb("http://nas:8000/posters/1.png", "w342"))
        assertNull(Vod.tmdb(null, "w342"))
    }

    @Test
    fun movieDetailsFromVodOrganizer() {
        // vod-organizer's get_vod_info: the year in "releasedate", no genre, cast and director empty.
        val json = """{"info":{"name":"Heat (US) (1995)","cover_big":"https://image.tmdb.org/t/p/w500/abc.jpg",
            "plot":"A heist goes wrong.","cast":"","director":"","rating":"7.9","releasedate":"1995","youtube_trailer":"",
            "backdrop_path":["https://image.tmdb.org/t/p/original/def.jpg"]},"movie_data":{"stream_id":88213}}"""

        assertEquals(
            Details("A heist goes wrong.", null, null, null, "https://image.tmdb.org/t/p/w1280/def.jpg", "1995", 7.9, 0),
            Vod.movieDetails(json, server.address),
        )
    }

    @Test
    fun seasonsAsProvidersSendThem() {
        // get_series_info passes the provider's reply through: string ids, the description in "plot".
        val json = """{"seasons":[],"info":{"name":"The Office","plot":"Slough.","cover":"https://image.tmdb.org/t/p/w500/o.jpg"},
            "episodes":{
              "2":[{"id":"9021","episode_num":1,"title":"Merger","container_extension":"mkv","season":2,
                    "info":{"plot":"Two offices.","movie_image":"https://image.tmdb.org/t/p/w500/s.jpg","duration_secs":1740}}],
              "1":[{"id":"9002","episode_num":"2","title":"Work Experience","container_extension":"mkv","info":{"duration":"00:29:30"}},
                   {"id":"9001","episode_num":"1","title":"Downsize","container_extension":"mkv","info":[]}]
            }}"""

        val seasons = Vod.seasons(json, server.address)
        assertEquals(listOf(1, 2), seasons.numbers)
        assertEquals(listOf(9001, 9002, 9021), seasons.episodes.map { it.id })
        assertEquals(Episode(9021, 2, 1, "Merger", "mkv", "Two offices.", "https://image.tmdb.org/t/p/w300/s.jpg", 1740), seasons.episodes[2])
        assertEquals(1770, seasons.episodes[1].seconds) // from "00:29:30"
        assertEquals("Slough.", seasons.details.plot)
    }

    @Test
    fun dispatcharrSeasonsAndAListOfLists() {
        val dispatcharr = """{"info":{"backdrop_path":"/b/5.jpg"},"episodes":{"1":[{"id":101,"season":1,"episode_num":1,"title":"The Target",
            "container_extension":"mkv","info":{"overview":"A trial.","duration_secs":3480}}]}}"""
        val seasons = Vod.seasons(dispatcharr, server.address)
        assertEquals(Episode(101, 1, 1, "The Target", "mkv", "A trial.", null, 3480), seasons.episodes.single())
        assertEquals("http://nas:8000/b/5.jpg", seasons.details.backdrop)

        val lists = """{"info":{},"episodes":[[{"id":"7","episode_num":"3","title":"Three","season":"1"}]]}"""
        assertEquals(listOf(Episode(7, 1, 3, "Three", "mp4", null, null, 0)), Vod.seasons(lists, server.address).episodes)
    }
}
