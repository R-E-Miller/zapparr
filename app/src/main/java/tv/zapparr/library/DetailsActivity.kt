package tv.zapparr.library

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.zapparr.R
import tv.zapparr.live.Details
import tv.zapparr.live.Episode
import tv.zapparr.live.Images
import tv.zapparr.live.LibraryItem
import tv.zapparr.live.Movie
import tv.zapparr.live.Problem
import tv.zapparr.live.Seasons
import tv.zapparr.live.Series
import tv.zapparr.live.Server
import tv.zapparr.live.ServerKind
import tv.zapparr.live.ServerStore
import tv.zapparr.live.Vod
import tv.zapparr.live.Xc
import tv.zapparr.live.XcClient
import tv.zapparr.live.XcException
import tv.zapparr.live.describe
import tv.zapparr.live.noPlayer
import tv.zapparr.live.playerIntent
import tv.zapparr.look.applyLook

/**
 * A movie or a series from the Library. A movie offers Play, or Resume and Start over. A series
 * lists its seasons and episodes, and its Play button offers the episode to watch next
 * ([Resume.next]). Players are asked to say where they stopped; [ProgressStore] keeps it.
 */
class DetailsActivity : ComponentActivity() {

    private lateinit var server: Server
    private lateinit var item: LibraryItem
    private lateinit var store: ProgressStore
    private var genre: String? = null
    private var details: Details? = null
    private var seasons: Seasons? = null
    private var season = 0
    // What the player was handed, so its result is kept under the right movie or episode. Saved
    // with the activity: on a 2 GB box Android may close Zapparr while the player runs.
    private var playing: Playing? = null

    private lateinit var title: TextView
    private lateinit var meta: TextView
    private lateinit var plot: TextView
    private lateinit var people: TextView
    private lateinit var play: Button
    private lateinit var restart: Button
    private lateinit var status: TextView
    private lateinit var seasonTabs: LinearLayout
    private lateinit var episodeList: VerticalGridView
    private lateinit var episodes: EpisodesAdapter
    // One loader for the backdrop, so a poster arriving late can't cover the backdrop shown after it.
    private lateinit var backdrops: Images
    private lateinit var backdrop: ImageView

    private val player = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val handed = playing ?: return@registerForActivityResult
        playing = null
        val extras = result.data?.extras ?: return@registerForActivityResult
        @Suppress("DEPRECATION") // Bundle.get: the players' extras come as Int or Long
        val stopped = Resume.stopped(extras.keySet().associateWith { extras.get(it) }) ?: return@registerForActivityResult
        store.save(handed.key, Saved(Resume.progress(stopped, System.currentTimeMillis()), handed.played))
        showButtons()
        episodes.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyLook()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)
        server = ServerStore(this, ServerKind.LIBRARY).load() ?: return finish()
        item = itemFrom(intent)
        genre = intent.getStringExtra(GENRE)
        store = ProgressStore(this)
        playing = savedInstanceState?.let(Playing::from)

        title = findViewById(R.id.details_title)
        meta = findViewById(R.id.details_meta)
        plot = findViewById(R.id.details_plot)
        people = findViewById(R.id.details_people)
        play = findViewById(R.id.details_play)
        restart = findViewById(R.id.details_restart)
        status = findViewById(R.id.details_status)
        seasonTabs = findViewById(R.id.details_seasons)
        episodeList = findViewById(R.id.details_episodes)
        val stillHeight = resources.getDimensionPixelSize(R.dimen.still_height)
        episodes = EpisodesAdapter(Images(stillHeight, STILL_CACHE, opaque = true), ::progressOf, ::playEpisode)
        episodeList.adapter = episodes

        title.text = item.name
        showMeta()
        play.isVisible = item is Movie
        if (item is Movie) showButtons()
        play.requestFocus()
        status.setText(R.string.details_loading)
        backdrop = findViewById(R.id.details_backdrop)
        backdrops = Images(resources.displayMetrics.heightPixels, BACKDROP_CACHE, opaque = true)
        backdrops.show(item.poster, backdrop)
        load()
        if (savedInstanceState == null) intent.getStringExtra(RESUME)?.let(::playSaved)
    }

    // Opened from Continue Watching: play at once from where it was left. Back from the player lands here.
    private fun playSaved(key: String) {
        val saved = store.get(key) ?: return
        val from = Resume.resumeAt(saved.progress) ?: 0
        when (val item = item) {
            is Movie -> playMovie(item, from)
            is Series -> {
                val id = ProgressStore.idOf(key) ?: return
                val played = saved.played
                playEpisode(Episode(id, played.season, played.episode, "", played.extension, null, played.art, 0), from)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        playing?.into(outState)
    }

    private fun load() = lifecycleScope.launch {
        val item = item
        val result: Any = withContext(Dispatchers.IO) {
            try {
                when (item) {
                    is Movie -> XcClient(server).movieDetails(item)
                    is Series -> XcClient(server).seasons(item)
                }
            } catch (e: XcException) {
                e.problem
            } catch (e: Exception) { // IOException while reading the reply
                Problem.UNREACHABLE
            }
        }
        status.text = null
        when (result) {
            is Problem -> status.text = describe(result, server.address)
            is Details -> show(result)
            is Seasons -> {
                show(result.details)
                showSeasons(result)
            }
        }
    }

    private fun show(loaded: Details) {
        details = loaded
        showMeta()
        plot.text = loaded.plot
        val credits = listOfNotNull(
            loaded.director?.let { getString(R.string.details_director, it) },
            loaded.cast?.let { getString(R.string.details_cast, it) },
        )
        people.text = credits.joinToString(SEPARATOR)
        people.isVisible = credits.isNotEmpty()
        // The backdrop, once known; the poster stands in until then, or for good without one.
        loaded.backdrop?.let { backdrops.show(it, backdrop) }
    }

    // Year, rating, genre, and the length of a movie or the number of seasons of a series.
    private fun showMeta() {
        val details = details
        val parts = listOfNotNull(
            details?.year ?: item.year,
            (details?.rating ?: item.rating)?.let { getString(R.string.library_rating, it) },
            details?.genre ?: genre,
            details?.seconds?.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.details_minutes, it / 60, it / 60) },
            seasons?.numbers?.size?.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.details_seasons, it, it) },
        )
        meta.text = parts.joinToString(SEPARATOR)
    }

    private fun showSeasons(loaded: Seasons) {
        seasons = loaded
        showMeta()
        if (loaded.episodes.isEmpty()) {
            status.setText(R.string.details_no_episodes)
            return
        }
        play.isVisible = true
        showButtons()
        play.requestFocus()
        val inflater = LayoutInflater.from(this)
        for (number in loaded.numbers) {
            val tab = inflater.inflate(R.layout.guide_filter, seasonTabs, false) as TextView
            tab.text = getString(R.string.details_season, number)
            // Focusing a season shows its episodes, so Down goes straight into them.
            tab.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) showSeason(number) }
            seasonTabs.addView(tab)
        }
        findViewById<View>(R.id.details_seasons_scroll).isVisible = true
        episodeList.isVisible = true
        showSeason(nextEpisode()?.season ?: loaded.numbers.first())
    }

    private fun showSeason(number: Int) {
        val loaded = seasons ?: return
        season = number
        loaded.numbers.forEachIndexed { i, n -> seasonTabs.getChildAt(i)?.isActivated = n == number }
        episodes.submit(loaded.episodes.filter { it.season == number })
    }

    // Play, or Resume and Start over, for the movie or for the series' next episode.
    private fun showButtons() {
        val item = item
        if (item is Movie) {
            val from = Resume.resumeAt(store.get(ProgressStore.movieKey(item.id))?.progress)
            play.text = if (from == null) getString(R.string.details_play) else getString(R.string.details_resume, Resume.clock(from))
            play.setOnClickListener { playMovie(item, from ?: 0) }
            restart.isVisible = from != null
            restart.setOnClickListener { playMovie(item, 0) }
            return
        }
        val next = nextEpisode() ?: return
        val from = Resume.resumeAt(progressOf(next))
        play.text = if (from == null) {
            getString(R.string.details_play_episode, next.season, next.number)
        } else {
            getString(R.string.details_resume_episode, next.season, next.number, Resume.clock(from))
        }
        play.setOnClickListener { playEpisode(next, from ?: 0) }
        restart.isVisible = from != null
        restart.setOnClickListener { playEpisode(next, 0) }
    }

    private fun nextEpisode(): Episode? = seasons?.let { Resume.next(it.episodes, ::progressOf) }

    private fun progressOf(episode: Episode) = store.get(ProgressStore.episodeKey(episode.id))?.progress

    private fun playMovie(movie: Movie, from: Long) {
        val key = ProgressStore.movieKey(movie.id)
        start(Xc.movieUrl(server, movie), movie.name, from, Playing(key, Played(movie.name, movie.poster, wideArt(key, null), movie.extension)))
    }

    // An episode from the list starts where it was left, if anywhere.
    private fun playEpisode(episode: Episode) = playEpisode(episode, Resume.resumeAt(progressOf(episode)) ?: 0)

    private fun playEpisode(episode: Episode, from: Long) {
        val key = ProgressStore.episodeKey(episode.id)
        val name = getString(R.string.details_episode_name, item.name, episode.season, episode.number)
        val played = Played(item.name, item.poster, wideArt(key, episode.image), episode.extension, item.id, episode.season, episode.number)
        start(Xc.episodeUrl(server, episode), name, from, Playing(key, played))
    }

    // Continue Watching's picture: the episode's still, else the backdrop at card size, else the one
    // kept last time (Play can be pressed before the details arrive).
    private fun wideArt(key: String, still: String?): String? =
        still ?: details?.backdrop?.let { Vod.tmdb(it, CARD_BACKDROP) } ?: store.get(key)?.played?.art

    private fun start(url: String, name: String, from: Long, handed: Playing) {
        try {
            player.launch(playerIntent(url, name, from))
            playing = handed
        } catch (e: ActivityNotFoundException) {
            noPlayer()
        }
    }

    /** What the player was handed: where to keep its result, and what Continue Watching shows for it. */
    private data class Playing(val key: String, val played: Played) {
        fun into(state: Bundle) {
            state.putString(P_KEY, key)
            state.putString(P_NAME, played.name)
            state.putString(P_POSTER, played.poster)
            state.putString(P_ART, played.art)
            state.putString(P_EXTENSION, played.extension)
            played.series?.let { state.putInt(P_SERIES, it) }
            state.putInt(P_SEASON, played.season)
            state.putInt(P_EPISODE, played.episode)
        }

        companion object {
            fun from(state: Bundle): Playing? {
                val key = state.getString(P_KEY) ?: return null
                val series = if (state.containsKey(P_SERIES)) state.getInt(P_SERIES) else null
                val played = Played(
                    state.getString(P_NAME).orEmpty(),
                    state.getString(P_POSTER),
                    state.getString(P_ART),
                    state.getString(P_EXTENSION) ?: "mp4",
                    series,
                    state.getInt(P_SEASON),
                    state.getInt(P_EPISODE),
                )
                return Playing(key, played)
            }
        }
    }

    companion object {
        private const val KIND = "kind"
        private const val ID = "id"
        private const val NAME = "name"
        private const val POSTER = "poster"
        private const val YEAR = "year"
        private const val RATING = "rating"
        private const val EXTENSION = "extension"
        private const val GENRE = "genre"
        private const val RESUME = "resume"
        private const val P_KEY = "playing_key"
        private const val P_NAME = "playing_title"
        private const val P_POSTER = "playing_poster"
        private const val P_ART = "playing_art"
        private const val P_EXTENSION = "playing_extension"
        private const val P_SERIES = "playing_series"
        private const val P_SEASON = "playing_season"
        private const val P_EPISODE = "playing_episode"
        private const val SEPARATOR = "  ·  "
        private const val STILL_CACHE = 4 * 1024 * 1024
        private const val BACKDROP_CACHE = 8 * 1024 * 1024
        // A Continue Watching card is about 400 pixels wide.
        private const val CARD_BACKDROP = "w780"

        /** Opens [item], with the name of the [genre] it was found under. */
        fun open(context: Context, item: LibraryItem, genre: String?) = context.startActivity(intent(context, item, genre))

        /**
         * Plays what [saved] (kept under [key]) left part-way, from there, over its details page:
         * Continue Watching's OK. Back from the player lands on the details.
         */
        fun resume(context: Context, key: String, saved: Saved) {
            val played = saved.played
            val item = if (played.series == null) {
                Movie(ProgressStore.idOf(key) ?: return, played.name, played.poster, 0, null, null, played.extension, null)
            } else {
                Series(played.series, played.name, played.poster, 0, null, null, null)
            }
            context.startActivity(intent(context, item, null).putExtra(RESUME, key))
        }

        private fun intent(context: Context, item: LibraryItem, genre: String?): Intent {
            val intent = Intent(context, DetailsActivity::class.java)
                .putExtra(KIND, if (item is Series) "series" else "movie")
                .putExtra(ID, item.id)
                .putExtra(NAME, item.name)
                .putExtra(POSTER, item.poster)
                .putExtra(YEAR, item.year)
                .putExtra(GENRE, genre)
            item.rating?.let { intent.putExtra(RATING, it) }
            if (item is Movie) intent.putExtra(EXTENSION, item.extension)
            return intent
        }

        private fun itemFrom(intent: Intent): LibraryItem {
            val id = intent.getIntExtra(ID, 0)
            val name = intent.getStringExtra(NAME).orEmpty()
            val poster = intent.getStringExtra(POSTER)
            val year = intent.getStringExtra(YEAR)
            val rating = if (intent.hasExtra(RATING)) intent.getDoubleExtra(RATING, 0.0) else null
            return if (intent.getStringExtra(KIND) == "series") {
                Series(id, name, poster, 0, year, rating, null)
            } else {
                Movie(id, name, poster, 0, year, rating, intent.getStringExtra(EXTENSION) ?: "mp4", null)
            }
        }
    }
}

/** A season's episodes: the still, how far each was watched, its number and title, and its description. */
private class EpisodesAdapter(
    private val images: Images,
    private val progressOf: (Episode) -> Progress?,
    private val onPlay: (Episode) -> Unit,
) : RecyclerView.Adapter<EpisodesAdapter.EpisodeHolder>() {

    private var episodes: List<Episode> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(newEpisodes: List<Episode>) {
        episodes = newEpisodes
        notifyDataSetChanged()
    }

    // After a player's result: the progress bars and Watched marks change.
    @SuppressLint("NotifyDataSetChanged")
    fun refresh() = notifyDataSetChanged()

    override fun getItemCount() = episodes.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        EpisodeHolder(LayoutInflater.from(parent.context).inflate(R.layout.row_episode, parent, false))

    override fun onBindViewHolder(holder: EpisodeHolder, position: Int) = holder.bind(episodes[position])

    inner class EpisodeHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val still: ImageView = view.findViewById(R.id.episode_still)
        private val bar: ProgressBar = view.findViewById(R.id.episode_progress)
        private val title: TextView = view.findViewById(R.id.episode_title)
        private val meta: TextView = view.findViewById(R.id.episode_meta)
        private val plot: TextView = view.findViewById(R.id.episode_plot)
        private var episode: Episode? = null

        init {
            view.setOnClickListener { episode?.let(onPlay) }
        }

        fun bind(entry: Episode) {
            episode = entry
            val context = itemView.context
            images.show(entry.image, still)
            title.text = context.getString(R.string.details_episode, entry.number, entry.title)
            val progress = progressOf(entry)
            val minutes = entry.seconds / 60
            meta.text = listOfNotNull(
                minutes.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.details_minutes, it, it) },
                context.getString(R.string.details_watched).takeIf { progress?.watched == true },
            ).joinToString("  ·  ")
            plot.text = entry.plot
            plot.isVisible = entry.plot != null
            // How far in, against the player's length for it, or the server's.
            val length = progress?.duration?.takeIf { it > 0 } ?: (entry.seconds * 1000L)
            val from = Resume.resumeAt(progress)
            bar.isVisible = from != null && length > 0
            if (from != null && length > 0) bar.progress = (from * 1000 / length).toInt().coerceIn(0, 1000)
            itemView.contentDescription = title.text
        }
    }
}
