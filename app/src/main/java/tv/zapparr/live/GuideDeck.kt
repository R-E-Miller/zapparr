package tv.zapparr.live

import android.app.AlertDialog
import android.content.Context
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.view.isVisible
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import tv.zapparr.R
import tv.zapparr.look.Look
import tv.zapparr.look.Option
import tv.zapparr.look.formatTime

/**
 * The Guide deck. Until a server's channels load, it shows a message and a button to the server
 * screen. Then it shows the focused program's details, the filters (Favorites, All channels and
 * the server's groups) and the time grid, with the channels arranged as the user arranged them
 * ([Arrangement]). OK on the grid watches a channel; holding OK offers, as TiviMate does: Favorites,
 * Move (Up and Down carry it, OK puts it down), Rename, and hiding the channel or its group.
 * Holding OK on a group's filter moves the group or hides it. [onChanged] runs when the channels,
 * the favorites or the channels watched lately change, for the Apps deck's On now row.
 */
class GuideDeck(deck: View, private val store: GuideStore, private val openServer: () -> Unit) : ServerDeck<Lineup> {

    var onChanged: () -> Unit = {}

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
    // The server's lineup as loaded; [lineup] is it arranged.
    private var loaded: Lineup? = null

    /** The channels and programs on show, arranged as the user wants them, or null while the Guide shows a message instead. */
    var lineup: Lineup? = null
        private set
    private var favorites = store.favorites()
    private var arrangement = store.arrangement()
    private var filter = store.filter
    private var options: List<Pair<String, String>> = emptyList()
    private var shown: List<Channel> = emptyList()
    private val sort = when (Look.current[Option.CHANNEL_ORDER]) {
        "number" -> ChannelOrder.NUMBER
        "name" -> ChannelOrder.NAME
        else -> ChannelOrder.YOURS
    }
    private val showHidden = Look.current.on(Option.SHOW_HIDDEN)

    override val showsContent get() = content.isVisible

    init {
        action.setOnClickListener { openServer() }
        deck.findViewById<View>(R.id.guide_settings).setOnClickListener { openServer() }
        grid.favorites = favorites
        grid.emptyText = context.getString(R.string.guide_no_favorites)
        grid.onSelected = ::showDetails
        grid.onWatch = ::watch
        grid.onHold = ::showMenu
        grid.onMove = ::move
        grid.onMoveDone = ::endMove
    }

    override fun showNotSetUp() = showMessage(context.getString(R.string.guide_not_set_up), R.string.guide_connect)

    override fun showLoading(address: String) = showMessage(context.getString(R.string.guide_loading, address), R.string.guide_server)

    override fun showProblem(text: String) = showMessage(text, R.string.guide_server)

    override fun show(server: Server, loaded: Lineup) {
        if (loaded.channels.isEmpty()) {
            showMessage(context.getString(R.string.guide_no_channels, server.address), R.string.guide_server)
            return
        }
        this.server = server
        this.loaded = loaded
        arrange(keepRow = true)
        swap(show = content, hide = message, focus = grid)
    }

    private fun showMessage(text: String, actionText: Int) {
        body.text = text
        action.setText(actionText)
        swap(show = message, hide = content, focus = action)
        loaded = null
        if (lineup != null) {
            lineup = null
            onChanged()
        }
    }

    // If the half being hidden has focus, focus moves to [focus] first, so it never leaves the deck.
    private fun swap(show: View, hide: View, focus: View) {
        show.isVisible = true
        if (hide.hasFocus()) focus.requestFocus()
        hide.isVisible = false
    }

    // The loaded lineup arranged again, after it loads or the user changes the arrangement.
    private fun arrange(keepRow: Boolean) {
        val loaded = loaded ?: return
        val arranged = loaded.copy(
            groups = Arrange.groups(loaded.groups, arrangement, showHidden),
            channels = Arrange.channels(loaded.channels, arrangement, sort, showHidden),
        )
        lineup = arranged
        grid.hidden = if (showHidden) loaded.channels.filter { Arrange.isHidden(it, arrangement) }.mapTo(HashSet()) { it.streamId } else emptySet()
        showFilters(arranged)
        applyFilter(keepRow)
        onChanged()
    }

    private fun showFilters(lineup: Lineup) {
        val used = lineup.channels.mapNotNullTo(HashSet()) { it.groupId }
        val options = listOf(
            Guide.FAVORITES to context.getString(R.string.guide_favorites),
            Guide.ALL to context.getString(R.string.guide_all),
        ) + lineup.groups.filter { it.id in used }.map { group ->
            val name = if (group.id in arrangement.hiddenGroups) context.getString(R.string.guide_hidden_label, group.name) else group.name
            Guide.groupFilter(group) to name
        }
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
            lineup.groups.firstOrNull { Guide.groupFilter(it) == key }?.let { group ->
                chip.setOnLongClickListener {
                    showGroupMenu(group)
                    true
                }
            }
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
        shown = Guide.filter(lineup.channels, filter ?: Guide.ALL, favorites)
        if (shown.isEmpty()) showDetails(null, null)
        grid.show(shown, lineup.programs, lineup.until, keepRow)
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
        val from = context.formatTime(start)
        val to = context.formatTime(end)
        if (DateUtils.isToday(start)) return context.getString(R.string.guide_time_range, from, to)
        val day = DateUtils.formatDateTime(context, start, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
        return context.getString(R.string.guide_time_range_day, day, from, to)
    }

    /** Hands [channel] to a video player, and remembers it for On now. */
    fun watch(channel: Channel) {
        val server = server ?: return
        context.watch(server, channel, store)
        onChanged()
    }

    /** Focuses the channel [streamId] at [time] in the grid (from Search), under All channels if the filter in use hides it. */
    fun showProgram(streamId: Int, time: Long) {
        val lineup = lineup ?: return
        if (Guide.filter(lineup.channels, filter ?: Guide.ALL, favorites).none { it.streamId == streamId }) choose(Guide.ALL)
        grid.focus(streamId, time)
    }

    // Holding OK on a channel: what TiviMate offers there.
    private fun showMenu(channel: Channel) {
        val choices = mutableListOf<Pair<String, () -> Unit>>()
        val favorite = channel.streamId in favorites
        choices += context.getString(if (favorite) R.string.guide_menu_favorite_remove else R.string.guide_menu_favorite_add) to { toggleFavorite(channel) }
        if (sort == ChannelOrder.YOURS) choices += context.getString(R.string.guide_menu_move) to { startMove(channel) }
        choices += context.getString(R.string.guide_menu_rename) to { rename(channel) }
        val hidden = channel.streamId in arrangement.hidden
        choices += context.getString(if (hidden) R.string.guide_menu_unhide else R.string.guide_menu_hide) to { setHidden(channel, !hidden) }
        loaded?.groups?.firstOrNull { it.id == channel.groupId }?.let { group ->
            val groupHidden = group.id in arrangement.hiddenGroups
            val label = if (groupHidden) R.string.guide_menu_unhide_group else R.string.guide_menu_hide_group
            choices += context.getString(label, group.name) to { setGroupHidden(group, !groupHidden) }
        }
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.guide_channel, channel.number, channel.name))
            .setItems(choices.map { it.first }.toTypedArray()) { _, which -> choices[which].second() }
            .show()
    }

    private fun toggleFavorite(channel: Channel) {
        val adding = channel.streamId !in favorites
        favorites = if (adding) favorites + channel.streamId else favorites - channel.streamId
        store.saveFavorites(favorites)
        grid.favorites = favorites
        val text = if (adding) R.string.guide_favorite_added else R.string.guide_favorite_removed
        Toast.makeText(context, context.getString(text, channel.name), Toast.LENGTH_SHORT).show()
        if (filter == Guide.FAVORITES) applyFilter(keepRow = true)
        onChanged()
    }

    private fun startMove(channel: Channel) {
        grid.moving = channel.streamId
        Toast.makeText(context, context.getString(R.string.guide_moving, channel.name), Toast.LENGTH_LONG).show()
    }

    // One step up or down among the channels on show; the hidden ones keep their places.
    private fun move(by: Int) {
        val loaded = loaded ?: return
        val id = grid.moving ?: return
        val all = Arrange.channels(loaded.channels, arrangement, ChannelOrder.YOURS, showHidden = true).map { it.streamId }
        val order = Arrange.move(all, shown.map { it.streamId }, id, by)
        if (order == all) return
        updateArrangement(arrangement.copy(order = order))
    }

    private fun endMove() {
        grid.moving = null
    }

    private fun rename(channel: Channel) {
        val serverName = loaded?.channels?.firstOrNull { it.streamId == channel.streamId }?.name ?: channel.name
        val field = EditText(context).apply {
            setText(channel.name)
            setSelection(text.length)
            setSingleLine()
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.guide_rename_title, channel.name))
            .setView(field)
            .setPositiveButton(R.string.guide_rename_save) { _, _ ->
                val name = field.text.toString().trim()
                val names = if (name.isEmpty() || name == serverName) arrangement.names - channel.streamId else arrangement.names + (channel.streamId to name)
                updateArrangement(arrangement.copy(names = names))
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (channel.streamId in arrangement.names) {
            dialog.setNeutralButton(R.string.guide_rename_reset) { _, _ -> updateArrangement(arrangement.copy(names = arrangement.names - channel.streamId)) }
        }
        dialog.show()
    }

    private fun setHidden(channel: Channel, hide: Boolean) {
        val hidden = if (hide) arrangement.hidden + channel.streamId else arrangement.hidden - channel.streamId
        updateArrangement(arrangement.copy(hidden = hidden))
        if (hide && !showHidden) Toast.makeText(context, context.getString(R.string.guide_hidden, channel.name), Toast.LENGTH_LONG).show()
    }

    private fun setGroupHidden(group: Group, hide: Boolean) {
        val hidden = if (hide) arrangement.hiddenGroups + group.id else arrangement.hiddenGroups - group.id
        updateArrangement(arrangement.copy(hiddenGroups = hidden))
        if (hide && !showHidden) Toast.makeText(context, context.getString(R.string.guide_hidden, group.name), Toast.LENGTH_LONG).show()
    }

    // Holding OK on a group's filter: move it along the filters, or hide it.
    private fun showGroupMenu(group: Group) {
        val loaded = loaded ?: return
        val all = Arrange.groups(loaded.groups, arrangement, showHidden = true).map { it.id }
        val choices = mutableListOf<Pair<String, () -> Unit>>()
        choices += context.getString(R.string.guide_menu_group_left) to { updateArrangement(arrangement.copy(groupOrder = Arrange.shift(all, group.id, -1))) }
        choices += context.getString(R.string.guide_menu_group_right) to { updateArrangement(arrangement.copy(groupOrder = Arrange.shift(all, group.id, 1))) }
        val hidden = group.id in arrangement.hiddenGroups
        val label = if (hidden) R.string.guide_menu_unhide_group else R.string.guide_menu_hide_group
        choices += context.getString(label, group.name) to { setGroupHidden(group, !hidden) }
        AlertDialog.Builder(context)
            .setTitle(group.name)
            .setItems(choices.map { it.first }.toTypedArray()) { _, which -> choices[which].second() }
            .show()
    }

    private fun updateArrangement(changed: Arrangement) {
        arrangement = changed
        store.saveArrangement(changed)
        arrange(keepRow = true)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val SEPARATOR = "  ·  "
    }
}

/**
 * What the Guide keeps on the box: the favorite channels and the channels watched lately (by
 * stream id), the last filter chosen, and how the user arranged the channels.
 */
class GuideStore(context: Context) {
    private val prefs = context.getSharedPreferences("guide", Context.MODE_PRIVATE)

    fun favorites(): Set<Int> = prefs.getStringSet(FAVORITES, null).orEmpty().mapNotNullTo(HashSet()) { it.toIntOrNull() }

    fun saveFavorites(ids: Set<Int>) = prefs.edit { putStringSet(FAVORITES, ids.mapTo(HashSet()) { it.toString() }) }

    /** Newest first. */
    fun recent(): List<Int> = ids(RECENT)

    fun saveRecent(ids: List<Int>) = prefs.edit { putString(RECENT, ids.joinToString(",")) }

    var filter: String?
        get() = prefs.getString(FILTER, null)
        set(value) = prefs.edit { putString(FILTER, value) }

    fun arrangement() = Arrangement(
        order = ids(ORDER),
        hidden = ids(HIDDEN).toSet(),
        hiddenGroups = prefs.getStringSet(HIDDEN_GROUPS, null).orEmpty(),
        names = names(),
        groupOrder = strings(GROUP_ORDER),
    )

    fun saveArrangement(arrangement: Arrangement) = prefs.edit {
        putString(ORDER, arrangement.order.joinToString(","))
        putString(HIDDEN, arrangement.hidden.joinToString(","))
        putStringSet(HIDDEN_GROUPS, arrangement.hiddenGroups)
        putString(NAMES, JSONObject(arrangement.names.mapKeys { it.key.toString() }).toString())
        putString(GROUP_ORDER, JSONArray(arrangement.groupOrder).toString())
    }

    private fun ids(key: String): List<Int> = prefs.getString(key, null)?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty()

    // Group ids are the server's text, so they are kept as a JSON list rather than joined with commas.
    private fun strings(key: String): List<String> = try {
        val array = JSONArray(prefs.getString(key, null) ?: "[]")
        (0 until array.length()).map { array.getString(it) }
    } catch (e: JSONException) {
        emptyList()
    }

    private fun names(): Map<Int, String> = try {
        val json = JSONObject(prefs.getString(NAMES, null) ?: "{}")
        json.keys().asSequence().mapNotNull { key -> key.toIntOrNull()?.let { it to json.getString(key) } }.toMap()
    } catch (e: JSONException) {
        emptyMap()
    }

    private companion object {
        const val FAVORITES = "favorites"
        const val FILTER = "filter"
        const val RECENT = "recent"
        const val ORDER = "order"
        const val HIDDEN = "hidden"
        const val HIDDEN_GROUPS = "hidden_groups"
        const val NAMES = "names"
        const val GROUP_ORDER = "group_order"
    }
}
