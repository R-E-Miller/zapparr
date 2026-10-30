package tv.zapparr.library

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.leanback.widget.HorizontalGridView
import androidx.recyclerview.widget.RecyclerView
import tv.zapparr.R
import tv.zapparr.apps.showLift
import tv.zapparr.live.Images
import tv.zapparr.live.LibraryItem
import tv.zapparr.look.Look
import tv.zapparr.look.Option

/** One row of the Library deck: Recently added, or a category. */
internal class PosterRow(val title: String, val items: List<LibraryItem>)

internal class PosterRowsAdapter(
    private val images: Images,
    private val onOpen: (LibraryItem) -> Unit,
    private val onFocus: (LibraryItem) -> Unit,
) : RecyclerView.Adapter<PosterRowsAdapter.RowHolder>() {

    private var rows: List<PosterRow> = emptyList()
    private val tilePool = RecyclerView.RecycledViewPool()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(newRows: List<PosterRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.row_posters, parent, false)
        return RowHolder(view)
    }

    override fun onBindViewHolder(holder: RowHolder, position: Int) = holder.bind(rows[position])

    inner class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.row_title)
        private val posters: HorizontalGridView = view.findViewById(R.id.row_posters)
        private val adapter = PostersAdapter(images, onOpen, onFocus)

        init {
            posters.setRecycledViewPool(tilePool)
            posters.adapter = adapter
        }

        fun bind(row: PosterRow) {
            title.text = row.title
            // A different list (Movies to Series, or a row reused further down) starts at its first poster.
            if (adapter.submit(row.items)) posters.selectedPosition = 0
        }
    }
}

internal class PostersAdapter(
    private val images: Images,
    private val onOpen: (LibraryItem) -> Unit,
    private val onFocus: (LibraryItem) -> Unit,
) : RecyclerView.Adapter<PostersAdapter.PosterHolder>() {

    private var items: List<LibraryItem> = emptyList()

    /** Shows [newItems]; returns whether they are a different list from before. */
    @SuppressLint("NotifyDataSetChanged")
    fun submit(newItems: List<LibraryItem>): Boolean {
        if (newItems === items) return false
        items = newItems
        notifyDataSetChanged()
        return true
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PosterHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.tile_poster, parent, false)
        return PosterHolder(view)
    }

    override fun onBindViewHolder(holder: PosterHolder, position: Int) = holder.bind(items[position])

    inner class PosterHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val card: View = view.findViewById(R.id.poster_card)
        private val image: ImageView = view.findViewById(R.id.poster_image)
        private val name: TextView = view.findViewById(R.id.poster_name)
        private val title: TextView = view.findViewById(R.id.poster_title)
        private val lift = view.resources.getDimension(R.dimen.tile_lift)
        private var item: LibraryItem? = null

        init {
            card.clipToOutline = true
            view.setOnClickListener { item?.let(onOpen) }
            view.setOnFocusChangeListener { _, hasFocus ->
                showFocus(hasFocus, animate = true)
                if (hasFocus) item?.let(onFocus)
            }
        }

        fun bind(entry: LibraryItem) {
            item = entry
            images.show(entry.poster, image)
            name.text = entry.name
            title.text = entry.name
            title.isVisible = Look.current.on(Option.POSTER_TITLES)
            itemView.contentDescription = entry.name
            showFocus(itemView.isFocused, animate = false)
        }

        // The focused poster lifts; its name shows in full above the rows.
        private fun showFocus(focused: Boolean, animate: Boolean) = showLift(card, focused, animate, FOCUS_SCALE, lift)
    }

    private companion object {
        const val FOCUS_SCALE = 1.08f
    }
}
