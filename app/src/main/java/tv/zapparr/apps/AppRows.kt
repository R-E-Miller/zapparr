package tv.zapparr.apps

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.leanback.widget.HorizontalGridView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import tv.zapparr.R
import tv.zapparr.live.Images
import tv.zapparr.look.Look
import tv.zapparr.look.Option
import tv.zapparr.look.themeColor

/** A row of the Apps deck. [id] names it, so rows can come and go without moving focus off another. */
internal sealed interface DeckRow {
    val id: String
}

/** A row of app tiles. [hint] shows in place of tiles while the row is empty. */
internal class TileRow(override val id: String, val title: String?, val apps: List<AppEntry>, val hint: String? = null) : DeckRow

/** A row of [Card]s: Continue Watching, or On now. */
internal class CardRow(override val id: String, val title: String, val cards: List<Card>) : DeckRow

internal class RowsAdapter(
    private val onOpen: (AppEntry) -> Unit,
    private val onMenu: (AppEntry, View) -> Unit,
    private val pictures: Images,
    private val logos: Images,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<DeckRow> = emptyList()
    private val pendingTile = HashMap<Int, Int>()
    private val tilePool = RecyclerView.RecycledViewPool()

    // Rows matched by id: one that appears above the focused row (On now, once the guide loads)
    // shifts focus down with its row instead of handing it to the newcomer. Every row rebinds.
    fun submit(newRows: List<DeckRow>) {
        val old = rows
        rows = newRows
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newRows.size
            override fun areItemsTheSame(oldPosition: Int, newPosition: Int) = old[oldPosition].id == newRows[newPosition].id
            override fun areContentsTheSame(oldPosition: Int, newPosition: Int) = false
        }).dispatchUpdatesTo(this)
    }

    /** Selects [tile] in [row] now if the row is on screen, or as soon as it is bound. */
    fun selectTile(grid: RecyclerView, row: Int, tile: Int) {
        val holder = grid.findViewHolderForAdapterPosition(row) as? TileRowHolder
        if (holder != null) holder.tiles.selectedPosition = tile else pendingTile[row] = tile
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = if (rows[position] is CardRow) CARDS else TILES

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.row_apps, parent, false)
        return if (viewType == CARDS) CardRowHolder(view) else TileRowHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is TileRow -> (holder as TileRowHolder).bind(row, position)
            is CardRow -> (holder as CardRowHolder).bind(row)
        }
    }

    inner class TileRowHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.row_title)
        private val hint: TextView = view.findViewById(R.id.row_hint)
        val tiles: HorizontalGridView = view.findViewById(R.id.row_tiles)
        private val adapter = TileAdapter(onOpen, onMenu)

        init {
            tiles.setRecycledViewPool(tilePool)
            tiles.adapter = adapter
        }

        fun bind(row: TileRow, position: Int) {
            title.text = row.title
            title.isVisible = row.title != null
            hint.text = row.hint
            hint.isVisible = row.apps.isEmpty() && row.hint != null
            adapter.submit(row.apps)
            pendingTile.remove(position)?.let { tiles.selectedPosition = it }
        }
    }

    inner class CardRowHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.row_title)
        private val cards: HorizontalGridView = view.findViewById(R.id.row_tiles)
        private val adapter = CardAdapter(pictures, logos)

        init {
            view.findViewById<View>(R.id.row_hint).isVisible = false
            cards.adapter = adapter
        }

        fun bind(row: CardRow) {
            title.text = row.title
            // In the row, focus stays on its card as the cards move. From outside, Down lands on the
            // front card: the channel just watched, or the title just left.
            if (cards.hasFocus()) {
                adapter.submit(row.cards)
            } else {
                adapter.replace(row.cards)
                cards.selectedPosition = 0
            }
        }
    }

    private companion object {
        const val TILES = 0
        const val CARDS = 1
    }
}

internal class TileAdapter(
    private val onOpen: (AppEntry) -> Unit,
    private val onMenu: (AppEntry, View) -> Unit,
) : RecyclerView.Adapter<TileAdapter.TileHolder>() {

    private var apps: List<AppEntry> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(newApps: List<AppEntry>) {
        if (newApps == apps) return
        apps = newApps
        notifyDataSetChanged()
    }

    override fun getItemCount() = apps.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.tile_app, parent, false)
        return TileHolder(view)
    }

    override fun onBindViewHolder(holder: TileHolder, position: Int) = holder.bind(apps[position])

    inner class TileHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val card: View = view.findViewById(R.id.tile_card)
        private val banner: ImageView = view.findViewById(R.id.tile_banner)
        private val icon: ImageView = view.findViewById(R.id.tile_icon)
        private val name: TextView = view.findViewById(R.id.tile_name)
        private val caption: TextView = view.findViewById(R.id.tile_caption)
        private val lift = view.resources.getDimension(R.dimen.tile_lift)
        private var app: AppEntry? = null

        init {
            card.clipToOutline = true
            view.setOnClickListener { app?.let(onOpen) }
            view.setOnLongClickListener { v ->
                app?.let { onMenu(it, v) }
                true
            }
            view.setOnFocusChangeListener { _, hasFocus -> showFocus(hasFocus, animate = true) }
        }

        fun bind(entry: AppEntry) {
            app = entry
            // An app can sit in Favorites and All apps at once, and a drawable belongs to one view.
            val art = entry.banner?.copy()
            banner.setImageDrawable(art)
            banner.isVisible = art != null
            icon.setImageDrawable(if (art == null) entry.icon.copy() else null)
            icon.isVisible = art == null
            name.text = entry.label
            name.isVisible = art == null
            caption.text = entry.label
            itemView.contentDescription = entry.label
            showFocus(itemView.isFocused, animate = false)
        }

        // The focused tile lifts and shows its name underneath (every tile shows it, if Settings says so).
        private fun showFocus(focused: Boolean, animate: Boolean) {
            showLift(card, focused, animate, FOCUS_SCALE, lift)
            val alpha = if (focused || Look.current[Option.APP_NAMES] == "always") 1f else 0f
            caption.animate().cancel()
            if (animate) caption.animate().alpha(alpha).setDuration(FOCUS_MS).start() else caption.alpha = alpha
        }
    }

    private companion object {
        const val FOCUS_SCALE = 1.12f
    }
}

/**
 * The focused tile, card or poster grows by [scale] (unless Settings turns zoom off) and lifts by
 * [lift], with a glow in the accent under it (Settings > Focus glow; Android 9 and later).
 */
internal fun showLift(card: View, focused: Boolean, animate: Boolean, scale: Float, lift: Float) {
    val look = Look.current
    val size = if (focused && look.on(Option.ZOOM)) scale else 1f
    val z = if (focused) lift else 0f
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val glow = if (look.on(Option.GLOW)) card.context.themeColor(R.attr.zAccent) else Color.BLACK
        card.outlineSpotShadowColor = glow
        card.outlineAmbientShadowColor = glow
    }
    card.animate().cancel()
    if (animate) {
        card.animate().scaleX(size).scaleY(size).translationZ(z).setDuration(FOCUS_MS).start()
    } else {
        card.scaleX = size
        card.scaleY = size
        card.translationZ = z
    }
}

private const val FOCUS_MS = 150L

private fun Drawable.copy(): Drawable = constantState?.newDrawable() ?: this
