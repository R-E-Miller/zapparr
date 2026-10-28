package tv.zapparr.apps

import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.leanback.widget.HorizontalGridView
import androidx.recyclerview.widget.RecyclerView
import tv.zapparr.R

/** One row of the Apps deck. [hint] shows in place of tiles while the row is empty. */
internal class TileRow(val title: String?, val apps: List<AppEntry>, val hint: String? = null)

internal class RowsAdapter(
    private val onOpen: (AppEntry) -> Unit,
    private val onMenu: (AppEntry, View) -> Unit,
) : RecyclerView.Adapter<RowsAdapter.RowHolder>() {

    private var rows: List<TileRow> = emptyList()
    private val pendingTile = HashMap<Int, Int>()
    private val tilePool = RecyclerView.RecycledViewPool()

    // Rows keep their index (Favorites is always row 0), so a full rebind keeps focus in place.
    @SuppressLint("NotifyDataSetChanged")
    fun submit(newRows: List<TileRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    /** Selects [tile] in [row] now if the row is on screen, or as soon as it is bound. */
    fun selectTile(grid: RecyclerView, row: Int, tile: Int) {
        val holder = grid.findViewHolderForAdapterPosition(row) as? RowHolder
        if (holder != null) holder.tiles.selectedPosition = tile else pendingTile[row] = tile
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.row_apps, parent, false)
        return RowHolder(view)
    }

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        val row = rows[position]
        holder.title.text = row.title
        holder.title.isVisible = row.title != null
        holder.hint.text = row.hint
        holder.hint.isVisible = row.apps.isEmpty() && row.hint != null
        holder.adapter.submit(row.apps)
        pendingTile.remove(position)?.let { holder.tiles.selectedPosition = it }
    }

    inner class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.row_title)
        val hint: TextView = view.findViewById(R.id.row_hint)
        val tiles: HorizontalGridView = view.findViewById(R.id.row_tiles)
        val adapter = TileAdapter(onOpen, onMenu)

        init {
            tiles.setRecycledViewPool(tilePool)
            tiles.adapter = adapter
        }
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

        // The focused tile lifts and shows its name underneath.
        private fun showFocus(focused: Boolean, animate: Boolean) {
            val scale = if (focused) FOCUS_SCALE else 1f
            val z = if (focused) lift else 0f
            val alpha = if (focused) 1f else 0f
            if (animate) {
                card.animate().scaleX(scale).scaleY(scale).translationZ(z).setDuration(FOCUS_MS).start()
                caption.animate().alpha(alpha).setDuration(FOCUS_MS).start()
            } else {
                card.animate().cancel()
                caption.animate().cancel()
                card.scaleX = scale
                card.scaleY = scale
                card.translationZ = z
                caption.alpha = alpha
            }
        }
    }

    private companion object {
        const val FOCUS_SCALE = 1.12f
        const val FOCUS_MS = 150L
    }
}

private fun Drawable.copy(): Drawable = constantState?.newDrawable() ?: this
