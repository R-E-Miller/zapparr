package tv.zapparr.library

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
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
import tv.zapparr.live.Xc
import tv.zapparr.live.watch

/**
 * The Library deck. Until the library server's catalog loads, it shows a message and a button to the
 * server screen. Then Movies or Series, as rows of posters: Recently added, then each category.
 * OK on a movie plays it; series open their seasons in the details screen, which comes next.
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
    private val images = Images(context.resources.getDimensionPixelSize(R.dimen.poster_height), POSTER_CACHE, opaque = true)
    private val adapter = PosterRowsAdapter(images, ::open)

    private var server: Server? = null
    private var catalog: Catalog? = null
    private var showingSeries = store.showingSeries

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
        this.server = server
        catalog = loaded
        showRows()
        swap(show = content, hide = message, focus = rows)
    }

    private fun showMessage(text: String, actionText: Int) {
        body.text = text
        action.setText(actionText)
        swap(show = message, hide = content, focus = action)
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
        adapter.submit(
            buildList {
                if (library.newest.isNotEmpty()) add(PosterRow(context.getString(R.string.library_newest), library.newest))
                for ((group, items) in library.shelves) add(PosterRow(context.getString(R.string.library_shelf, group.name, items.size), items))
            },
        )
    }

    private fun open(item: LibraryItem) {
        val server = server ?: return
        when (item) {
            is Movie -> context.watch(Xc.movieUrl(server, item), item.name)
            is Series -> Toast.makeText(context, R.string.library_series_soon, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        // Posters are decoded at 16 bits a pixel, so this holds about a hundred and twenty.
        const val POSTER_CACHE = 16 * 1024 * 1024
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
