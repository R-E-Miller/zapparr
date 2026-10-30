package tv.zapparr.search

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.leanback.widget.HorizontalGridView
import androidx.recyclerview.widget.RecyclerView
import tv.zapparr.R
import tv.zapparr.apps.Card
import tv.zapparr.apps.CardAdapter
import tv.zapparr.library.PostersAdapter
import tv.zapparr.live.Images
import tv.zapparr.live.LibraryItem

/** A row of results: cards (channels, programs) or posters (movies, series). */
internal sealed interface ResultRow {
    val title: String
}

internal class CardResults(override val title: String, val cards: List<Card>) : ResultRow

internal class PosterResults(override val title: String, val items: List<LibraryItem>) : ResultRow

internal class SearchRowsAdapter(
    private val logos: Images,
    private val posters: Images,
    private val onOpen: (LibraryItem) -> Unit,
    private val onFocusItem: (LibraryItem) -> Unit,
    private val onFocusCard: (Card) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<ResultRow> = emptyList()

    // New results come with each letter typed, while focus is still in the query.
    @SuppressLint("NotifyDataSetChanged")
    fun submit(newRows: List<ResultRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = if (rows[position] is CardResults) CARDS else POSTERS

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == CARDS) {
            CardsHolder(inflater.inflate(R.layout.row_apps, parent, false))
        } else {
            PostersHolder(inflater.inflate(R.layout.row_posters, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is CardResults -> (holder as CardsHolder).bind(row)
            is PosterResults -> (holder as PostersHolder).bind(row)
        }
    }

    inner class CardsHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.row_title)
        private val cards: HorizontalGridView = view.findViewById(R.id.row_tiles)
        // Search shows logos only, so they serve as the pictures too.
        private val adapter = CardAdapter(logos, logos, onFocusCard)

        init {
            view.findViewById<View>(R.id.row_hint).isVisible = false
            cards.adapter = adapter
        }

        fun bind(row: CardResults) {
            title.text = row.title
            adapter.replace(row.cards)
            cards.selectedPosition = 0
        }
    }

    inner class PostersHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.row_title)
        private val grid: HorizontalGridView = view.findViewById(R.id.row_posters)
        private val adapter = PostersAdapter(posters, onOpen, onFocusItem)

        init {
            grid.adapter = adapter
        }

        fun bind(row: PosterResults) {
            title.text = row.title
            if (adapter.submit(row.items)) grid.selectedPosition = 0
        }
    }

    private companion object {
        const val CARDS = 0
        const val POSTERS = 1
    }
}
