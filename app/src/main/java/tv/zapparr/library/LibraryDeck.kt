package tv.zapparr.library

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.graphics.scale
import androidx.core.view.isVisible
import androidx.leanback.widget.VerticalGridView
import tv.zapparr.R
import tv.zapparr.live.Catalog
import tv.zapparr.live.Images
import tv.zapparr.live.Library
import tv.zapparr.live.LibraryItem
import tv.zapparr.live.Movie
import tv.zapparr.live.Series
import tv.zapparr.live.Server
import tv.zapparr.live.ServerDeck
import tv.zapparr.look.Look
import tv.zapparr.look.Option

/**
 * The Library deck. Until the library server's catalog loads, it shows a message and a button to the
 * server screen. Then Movies or Series, as rows of posters: Recently added, then each category, with
 * the focused poster's name, year, rating and genre above them. OK opens its [DetailsActivity].
 */
class LibraryDeck(deck: View, private val store: LibraryStore, private val openServer: () -> Unit) : ServerDeck<Catalog> {

    private val context: Context = deck.context
    private val message: View = deck.findViewById(R.id.library_message)
    private val body: TextView = deck.findViewById(R.id.library_body)
    private val action: Button = deck.findViewById(R.id.library_action)
    private val content: View = deck.findViewById(R.id.library_content)
    private val movies: TextView = deck.findViewById(R.id.library_movies)
    private val series: TextView = deck.findViewById(R.id.library_series)
    private val rows: VerticalGridView = deck.findViewById(R.id.library_rows)
    private val focusTitle: TextView = deck.findViewById(R.id.library_focus_title)
    private val focusMeta: TextView = deck.findViewById(R.id.library_focus_meta)
    private val backdropBox: View = deck.findViewById(R.id.library_backdrop_box)
    private val backdrop: ImageView = deck.findViewById(R.id.library_backdrop)
    private val showBackdrop = Look.current.on(Option.BACKDROP)
    private val images = Images(context.resources.getDimensionPixelSize(R.dimen.poster_height), POSTER_CACHE, opaque = true)
    private val adapter = PosterRowsAdapter(images, ::open, ::showFocused)

    /** The movies and series on show, or null while the deck shows a message instead. */
    var catalog: Catalog? = null
        private set
    private var showingSeries = store.showingSeries
    // Category names by id, for the genre of a poster in Recently added.
    private var genres: Map<String, String> = emptyMap()

    override val showsContent get() = content.isVisible

    init {
        rows.adapter = adapter
        action.setOnClickListener { openServer() }
        deck.findViewById<View>(R.id.library_settings).setOnClickListener { openServer() }
        movies.setOnClickListener { choose(series = false) }
        series.setOnClickListener { choose(series = true) }
    }

    override fun showNotSetUp() = showMessage(context.getString(R.string.library_not_set_up), R.string.library_connect)

    override fun showLoading(address: String) = showMessage(context.getString(R.string.library_loading, address), R.string.library_server_settings)

    override fun showProblem(text: String) = showMessage(text, R.string.library_server_settings)

    override fun show(server: Server, loaded: Catalog) {
        if (loaded.movies.newest.isEmpty() && loaded.series.newest.isEmpty()) {
            showMessage(context.getString(R.string.library_empty, server.address), R.string.library_server_settings)
            return
        }
        catalog = loaded
        showRows()
        swap(show = content, hide = message, focus = rows)
        backdropBox.isVisible = showBackdrop
    }

    private fun showMessage(text: String, actionText: Int) {
        body.text = text
        action.setText(actionText)
        swap(show = message, hide = content, focus = action)
        backdropBox.isVisible = false
        catalog = null
    }

    // If the half being hidden has focus, focus moves to [focus] first, so it never leaves the deck.
    private fun swap(show: View, hide: View, focus: View) {
        show.isVisible = true
        if (hide.hasFocus()) focus.requestFocus()
        hide.isVisible = false
    }

    private fun choose(series: Boolean) {
        if (series == showingSeries) return
        showingSeries = series
        store.showingSeries = series
        showRows()
        rows.selectedPosition = 0
    }

    private fun showRows() {
        val catalog = catalog ?: return
        movies.isActivated = !showingSeries
        series.isActivated = showingSeries
        val library: Library<LibraryItem> = if (showingSeries) catalog.series else catalog.movies
        genres = library.shelves.associate { (group, _) -> group.id to group.name }
        focusTitle.text = null
        focusMeta.text = null
        adapter.submit(
            buildList {
                if (library.newest.isNotEmpty()) add(PosterRow(context.getString(R.string.library_newest), library.newest))
                for ((group, items) in library.shelves) add(PosterRow(context.getString(R.string.library_shelf, group.name, items.size), items))
            },
        )
    }

    private fun open(item: LibraryItem) = DetailsActivity.open(context, item, genreOf(item))

    private fun showFocused(item: LibraryItem) {
        // Its colors, blurred: the poster, once loaded, shrunk to a few pixels and spread out.
        if (showBackdrop) item.poster?.let(images::get)?.let { backdrop.setImageBitmap(it.scale(BLUR_WIDTH, BLUR_HEIGHT)) }
        focusTitle.text = item.name
        focusMeta.text = listOfNotNull(
            item.year,
            item.rating?.let { context.getString(R.string.library_rating, it) },
            genreOf(item),
        ).joinToString(SEPARATOR)
    }

    private fun genreOf(item: LibraryItem): String? = when (item) {
        is Movie -> item.group
        is Series -> item.group
    }?.let(genres::get)

    private companion object {
        // Posters are decoded at 16 bits a pixel, so this holds about a hundred and twenty.
        const val POSTER_CACHE = 16 * 1024 * 1024
        const val BLUR_WIDTH = 16
        const val BLUR_HEIGHT = 24
        const val SEPARATOR = "  ·  "
    }
}

/** Whether the Library deck last showed Series rather than Movies. */
class LibraryStore(context: Context) {
    private val prefs = context.getSharedPreferences("library_view", Context.MODE_PRIVATE)

    var showingSeries: Boolean
        get() = prefs.getBoolean(SERIES, false)
        set(value) = prefs.edit { putBoolean(SERIES, value) }

    private companion object {
        const val SERIES = "series"
    }
}
