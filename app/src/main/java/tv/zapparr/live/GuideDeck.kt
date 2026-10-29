package tv.zapparr.live

import android.content.Context
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.view.isVisible
import tv.zapparr.R

/**
 * The Guide deck. Until a server's channels load, it shows a message and a button to the server
 * screen. Then it shows the focused program's details, the filters (Favorites, All channels and
 * the server's groups) and the time grid. OK on the grid watches a channel; holding OK adds it to
 * Favorites or removes it.
 */
class GuideDeck(deck: View, private val store: GuideStore, private val openServer: () -> Unit) {

    private val context: Context = deck.context
    private val message: View = deck.findViewById(R.id.guide_message)
    private val body: TextView = deck.findViewById(R.id.guide_body)
    private val action: Button = deck.findViewById(R.id.guide_action)
    private val content: View = deck.findViewById(R.id.guide_content)
    private val title: TextView = deck.findViewById(R.id.guide_program_title)
    private val details: TextView = deck.findViewById(R.id.guide_program_details)
    private val description: TextView = deck.findViewById(R.id.guide_program_description)
    private val filters: ViewGroup = deck.findViewById(R.id.guide_filters)
    private val grid: GuideView = deck.findViewById(R.id.guide_grid)

    private var server: Server? = null
    private var lineup: Lineup? = null
    private var favorites = store.favorites()
    private var filter = store.filter
    private var options: List<Pair<String, String>> = emptyList()

    /** Whether the grid is up, rather than a message. */
    val showsGuide get() = content.isVisible

    init {
        action.setOnClickListener { openServer() }
        deck.findViewById<View>(R.id.guide_settings).setOnClickListener { openServer() }
        grid.favorites = favorites
        grid.emptyText = context.getString(R.string.guide_no_favorites)
        grid.onSelected = ::showDetails
        grid.onWatch = ::watch
        grid.onHold = ::toggleFavorite
    }

    fun showNotSetUp() = showMessage(context.getString(R.string.guide_not_set_up), R.string.guide_connect)

    fun showLoading(address: String) = showMessage(context.getString(R.string.guide_loading, address), R.string.guide_server)

    fun showProblem(text: String) = showMessage(text, R.string.guide_server)

    fun show(server: Server, lineup: Lineup) {
        if (lineup.channels.isEmpty()) {
            showMessage(context.getString(R.string.guide_no_channels, server.address), R.string.guide_server)
            return
        }
        this.server = server
        this.lineup = lineup
        showFilters(lineup)
        applyFilter(keepRow = true)
        swap(show = content, hide = message, focus = grid)
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

    private fun showFilters(lineup: Lineup) {
        val used = lineup.channels.mapNotNullTo(HashSet()) { it.groupId }
        val options = listOf(
            Guide.FAVORITES to context.getString(R.string.guide_favorites),
            Guide.ALL to context.getString(R.string.guide_all),
        ) + lineup.groups.filter { it.id in used }.map { Guide.groupFilter(it) to it.name }
        if (options.none { it.first == filter }) filter = if (favorites.isEmpty()) Guide.ALL else Guide.FAVORITES
        if (options == this.options) return
        this.options = options
        val focused = filters.focusedChild?.tag
        filters.removeAllViews()
        val inflater = LayoutInflater.from(context)
        for ((key, label) in options) {
            val chip = inflater.inflate(R.layout.guide_filter, filters, false) as TextView
            chip.text = label
            chip.tag = key
            chip.setOnClickListener { choose(key) }
            filters.addView(chip)
            if (key == focused) chip.requestFocus()
        }
    }

    private fun choose(key: String) {
        filter = key
        store.filter = key
        applyFilter(keepRow = false)
    }

    private fun applyFilter(keepRow: Boolean) {
        val lineup = lineup ?: return
        for (i in 0 until filters.childCount) filters.getChildAt(i).let { it.isActivated = it.tag == filter }
        val channels = Guide.filter(lineup.channels, filter ?: Guide.ALL, favorites)
        if (channels.isEmpty()) showDetails(null, null)
        grid.show(channels, lineup.programs, lineup.until, keepRow)
    }

    private fun showDetails(channel: Channel?, cell: Guide.Cell?) {
        if (channel == null || cell == null) {
            title.text = null
            details.text = null
            description.text = null
            return
        }
        val program = cell.program
        val parts = mutableListOf(context.getString(R.string.guide_channel, channel.number, channel.name))
        // A gap has no times worth showing. A program has its own, which the cell may cut to the grid's start.
        if (program != null) {
            parts += timeRange(program.start, program.end)
            val now = System.currentTimeMillis()
            if (now in program.start until program.end) {
                val minutes = ((program.end - now + MINUTE - 1) / MINUTE).toInt()
                parts += context.resources.getQuantityString(R.plurals.guide_minutes_left, minutes, minutes)
            }
        }
        title.text = program?.title ?: context.getString(R.string.guide_no_info)
        details.text = parts.joinToString(SEPARATOR)
        description.text = program?.description
    }

    // "11:30 PM – 12:30 AM", with the weekday for a program that isn't today. formatDateRange would
    // spell out both dates for a program that crosses midnight.
    private fun timeRange(start: Long, end: Long): String {
        val from = DateUtils.formatDateTime(context, start, DateUtils.FORMAT_SHOW_TIME)
        val to = DateUtils.formatDateTime(context, end, DateUtils.FORMAT_SHOW_TIME)
        if (DateUtils.isToday(start)) return context.getString(R.string.guide_time_range, from, to)
        val day = DateUtils.formatDateTime(context, start, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
        return context.getString(R.string.guide_time_range_day, day, from, to)
    }

    private fun watch(channel: Channel) {
        val server = server ?: return
        context.watch(Xc.liveUrl(server, channel), channel.name)
    }

    private fun toggleFavorite(channel: Channel) {
        val adding = channel.streamId !in favorites
        favorites = if (adding) favorites + channel.streamId else favorites - channel.streamId
        store.saveFavorites(favorites)
        grid.favorites = favorites
        val text = if (adding) R.string.guide_favorite_added else R.string.guide_favorite_removed
        Toast.makeText(context, context.getString(text, channel.name), Toast.LENGTH_SHORT).show()
        if (filter == Guide.FAVORITES) applyFilter(keepRow = true)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val SEPARATOR = "  ·  "
    }
}

/** The favorite channels (by stream id) and the last filter chosen. */
class GuideStore(context: Context) {
    private val prefs = context.getSharedPreferences("guide", Context.MODE_PRIVATE)

    fun favorites(): Set<Int> = prefs.getStringSet(FAVORITES, null).orEmpty().mapNotNullTo(HashSet()) { it.toIntOrNull() }

    fun saveFavorites(ids: Set<Int>) = prefs.edit { putStringSet(FAVORITES, ids.mapTo(HashSet()) { it.toString() }) }

    var filter: String?
        get() = prefs.getString(FILTER, null)
        set(value) = prefs.edit { putString(FILTER, value) }

    private companion object {
        const val FAVORITES = "favorites"
        const val FILTER = "filter"
    }
}
