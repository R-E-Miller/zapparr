package tv.zapparr.search

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.format.DateUtils
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.zapparr.R
import tv.zapparr.apps.CARD_SEPARATOR
import tv.zapparr.apps.Card
import tv.zapparr.apps.channelCard
import tv.zapparr.library.DetailsActivity
import tv.zapparr.live.Catalog
import tv.zapparr.live.Channel
import tv.zapparr.live.GuideStore
import tv.zapparr.live.Images
import tv.zapparr.live.LibraryItem
import tv.zapparr.live.Lineup
import tv.zapparr.live.Movie
import tv.zapparr.live.OnNow
import tv.zapparr.live.Program
import tv.zapparr.live.Series
import tv.zapparr.live.ServerKind
import tv.zapparr.live.ServerStore
import tv.zapparr.live.watch
import tv.zapparr.look.applyLook
import tv.zapparr.look.formatTime

/**
 * Search, through live TV and the Library at once: channels by name or number, programs on now or
 * later, movies and series. Results follow each letter typed, or what's said into the mic. OK on a
 * channel, or on a program on now, watches it; on a later program, goes back to Home's Guide at
 * that time ([programOf]); on a movie or a series, opens its details.
 */
class SearchActivity : ComponentActivity() {

    private val lineup = handedLineup
    private val catalog = handedCatalog
    private var index: SearchIndex? = null
    private var job: Job? = null
    private var libraryFirst = false
    // Category names by id, for the genre of a movie or a series.
    private var movieGenres: Map<String, String> = emptyMap()
    private var seriesGenres: Map<String, String> = emptyMap()

    private lateinit var query: EditText
    private lateinit var message: TextView
    private lateinit var focusTitle: TextView
    private lateinit var focusMeta: TextView
    private lateinit var results: VerticalGridView
    private lateinit var adapter: SearchRowsAdapter

    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val said = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return@registerForActivityResult
        query.setText(said)
        query.setSelection(said.length)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyLook()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        libraryFirst = intent.getBooleanExtra(LIBRARY_FIRST, false)
        query = findViewById(R.id.search_query)
        message = findViewById(R.id.search_message)
        focusTitle = findViewById(R.id.search_focus_title)
        focusMeta = findViewById(R.id.search_focus_meta)
        results = findViewById(R.id.search_results)
        val logos = Images(resources.getDimensionPixelSize(R.dimen.card_height), LOGO_CACHE)
        val posters = Images(resources.getDimensionPixelSize(R.dimen.poster_height), POSTER_CACHE, opaque = true)
        adapter = SearchRowsAdapter(logos, posters, ::open, ::showItem, ::showCard)
        results.adapter = adapter
        catalog?.let { c ->
            movieGenres = c.movies.shelves.associate { (group, _) -> group.id to group.name }
            seriesGenres = c.series.shelves.associate { (group, _) -> group.id to group.name }
        }

        query.doAfterTextChanged { search() }
        // The keyboard's Search key puts it away and goes to the results.
        query.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(query.windowToken, 0)
            results.requestFocus()
            true
        }
        val speech = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.search_hint))
        findViewById<ImageButton>(R.id.search_voice).apply {
            isVisible = speech.resolveActivity(packageManager) != null
            setOnClickListener {
                try {
                    voice.launch(speech)
                } catch (e: ActivityNotFoundException) {
                    isVisible = false
                }
            }
        }
        show("", null)
        query.requestFocus()
        lifecycleScope.launch {
            index = withContext(Dispatchers.Default) { SearchIndex(lineup, catalog) }
            search()
        }
    }

    // A moment after the last letter, so typing a word searches once, not for each letter of it.
    private fun search() {
        val index = index ?: return
        val text = query.text.toString()
        job?.cancel()
        job = lifecycleScope.launch {
            delay(DEBOUNCE_MS)
            val found = withContext(Dispatchers.Default) { index.find(text, System.currentTimeMillis()) }
            show(text, found)
        }
    }

    private fun show(text: String, found: Found?) {
        val now = System.currentTimeMillis()
        val live = listOfNotNull(
            found?.channels?.takeIf { it.isNotEmpty() }?.let { channels ->
                CardResults(getString(R.string.search_channels), channels.map { channelCard(it, lineup?.programs?.get(it.epgId), now) { tune(it) } })
            },
            found?.programs?.takeIf { it.isNotEmpty() }?.let { programs ->
                CardResults(getString(R.string.search_programs), programs.map { programCard(it, now) })
            },
        )
        val library = listOfNotNull(
            found?.movies?.takeIf { it.isNotEmpty() }?.let { PosterResults(getString(R.string.library_movies), it) },
            found?.series?.takeIf { it.isNotEmpty() }?.let { PosterResults(getString(R.string.library_series), it) },
        )
        adapter.submit(if (libraryFirst) library + live else live + library)
        showFocus(null, null)
        message.text = when {
            lineup == null && catalog == null -> getString(R.string.search_nothing_loaded)
            text.isBlank() -> getString(R.string.search_start)
            found?.isEmpty == true -> getString(R.string.search_none, text.trim())
            else -> null
        }
        message.isVisible = !message.text.isNullOrEmpty()
    }

    // A program on now shows how far in it is; a later one, when it starts.
    private fun programCard(airing: Airing, now: Long): Card {
        val (channel, program) = airing
        val name = getString(R.string.guide_channel, channel.number, channel.name)
        val onNow = program.start <= now
        val subtitle = if (onNow) {
            val minutes = ((program.end - now + MINUTE - 1) / MINUTE).toInt()
            listOf(name, resources.getQuantityString(R.plurals.guide_minutes_left, minutes, minutes))
        } else {
            listOf(startTime(program.start), name)
        }
        return Card(
            key = "program:${channel.streamId}:${program.start}",
            title = program.title,
            subtitle = subtitle.joinToString(CARD_SEPARATOR),
            image = channel.logo,
            progress = if (onNow) OnNow.progress(program, now) else null,
            open = { if (program.start <= System.currentTimeMillis()) tune(channel) else showInGuide(channel, program) },
            logo = true,
            placeholder = channel.name,
            live = onNow,
        )
    }

    // "8:00 PM", with the weekday for a program that isn't today.
    private fun startTime(start: Long): String {
        val time = formatTime(start)
        if (DateUtils.isToday(start)) return time
        val day = DateUtils.formatDateTime(this, start, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
        return getString(R.string.search_day_time, day, time)
    }

    private fun tune(channel: Channel) {
        val server = ServerStore(this, ServerKind.LIVE).load() ?: return
        watch(server, channel, GuideStore(this))
    }

    private fun showInGuide(channel: Channel, program: Program) {
        setResult(RESULT_OK, Intent().putExtra(CHANNEL, channel.streamId).putExtra(TIME, program.start))
        finish()
    }

    private fun open(item: LibraryItem) = DetailsActivity.open(this, item, genreOf(item))

    private fun showItem(item: LibraryItem) = showFocus(
        item.name,
        listOfNotNull(item.year, item.rating?.let { getString(R.string.library_rating, it) }, genreOf(item)).joinToString(CARD_SEPARATOR),
    )

    private fun showCard(card: Card) = showFocus(card.title, card.subtitle)

    private fun showFocus(title: String?, meta: String?) {
        focusTitle.text = title
        focusMeta.text = meta
        focusTitle.isVisible = title != null
        focusMeta.isVisible = title != null
    }

    private fun genreOf(item: LibraryItem): String? = when (item) {
        is Movie -> item.group?.let(movieGenres::get)
        is Series -> item.group?.let(seriesGenres::get)
    }

    companion object {
        private const val LIBRARY_FIRST = "library_first"
        private const val CHANNEL = "channel"
        private const val TIME = "time"
        private const val DEBOUNCE_MS = 200L
        private const val MINUTE = 60_000L
        private const val LOGO_CACHE = 2 * 1024 * 1024
        private const val POSTER_CACHE = 8 * 1024 * 1024

        // Too big for an Intent: Home hands them over in memory, where it keeps them anyway.
        private var handedLineup: Lineup? = null
        private var handedCatalog: Catalog? = null

        /** Search through [lineup] and [catalog], with movies and series first when [libraryFirst]. */
        fun intent(context: Context, lineup: Lineup?, catalog: Catalog?, libraryFirst: Boolean): Intent {
            handedLineup = lineup
            handedCatalog = catalog
            return Intent(context, SearchActivity::class.java).putExtra(LIBRARY_FIRST, libraryFirst)
        }

        /** The channel and start time of a later program chosen in Search, for Home to show in the Guide. */
        fun programOf(result: ActivityResult): Pair<Int, Long>? {
            val data = result.data ?: return null
            if (result.resultCode != Activity.RESULT_OK || !data.hasExtra(CHANNEL)) return null
            return data.getIntExtra(CHANNEL, 0) to data.getLongExtra(TIME, 0)
        }
    }
}
