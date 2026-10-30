package tv.zapparr.apps

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import tv.zapparr.R
import tv.zapparr.live.Channel
import tv.zapparr.live.Images
import tv.zapparr.live.OnNow
import tv.zapparr.live.Program

/**
 * A card in Continue Watching or On now: a wide picture with a progress bar ([progress], 0 to 1,
 * none if null), and two lines under it. A [logo] (a channel's) shows whole on the tile color;
 * other pictures fill the card. [placeholder] shows until the picture loads, or for good without
 * one. [key] stays the same while the card shows the same thing. [menu], if any, is for holding OK.
 * A [live] card (a channel, a program on now) wears a LIVE badge.
 */
internal class Card(
    val key: String,
    val title: String,
    val subtitle: String,
    val image: String?,
    val progress: Float?,
    val open: () -> Unit,
    val logo: Boolean = false,
    val placeholder: String = title,
    val menu: ((View) -> Unit)? = null,
    val live: Boolean = false,
) {
    fun showsSameAs(other: Card) =
        title == other.title && subtitle == other.subtitle && image == other.image && progress == other.progress &&
            placeholder == other.placeholder && live == other.live
}

/**
 * A channel's card, as On now and Search show it: its logo, what [programs] have on at [now] with
 * the minutes left and how far in, and its number and name.
 */
internal fun Context.channelCard(channel: Channel, programs: List<Program>?, now: Long, open: () -> Unit): Card {
    val program = OnNow.airing(programs, now)
    val left = program?.let { ((it.end - now + MINUTE - 1) / MINUTE).toInt() }
    return Card(
        key = "channel:${channel.streamId}",
        title = program?.title ?: getString(R.string.guide_no_info),
        subtitle = listOfNotNull(
            getString(R.string.guide_channel, channel.number, channel.name),
            left?.let { resources.getQuantityString(R.plurals.guide_minutes_left, it, it) },
        ).joinToString(CARD_SEPARATOR),
        image = channel.logo,
        progress = program?.let { OnNow.progress(it, now) },
        open = open,
        logo = true,
        placeholder = channel.name,
        live = true,
    )
}

/** Between the parts of a card's second line. */
internal const val CARD_SEPARATOR = "  ·  "

private const val MINUTE = 60_000L

/** [onFocus] runs as a card gains focus. */
internal class CardAdapter(
    private val pictures: Images,
    private val logos: Images,
    private val onFocus: (Card) -> Unit = {},
) : RecyclerView.Adapter<CardAdapter.CardHolder>() {

    private var cards: List<Card> = emptyList()

    /** New cards for a row without focus: positions start over. */
    @SuppressLint("NotifyDataSetChanged")
    fun replace(newCards: List<Card>) {
        cards = newCards
        notifyDataSetChanged()
    }

    // Cards matched by key: a card that moves to the front, or a minute ticking by, keeps focus where it is.
    fun submit(newCards: List<Card>) {
        val old = cards
        cards = newCards
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newCards.size
            override fun areItemsTheSame(oldPosition: Int, newPosition: Int) = old[oldPosition].key == newCards[newPosition].key
            override fun areContentsTheSame(oldPosition: Int, newPosition: Int) = old[oldPosition].showsSameAs(newCards[newPosition])
        }).dispatchUpdatesTo(this)
    }

    override fun getItemCount() = cards.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        CardHolder(LayoutInflater.from(parent.context).inflate(R.layout.tile_card, parent, false))

    override fun onBindViewHolder(holder: CardHolder, position: Int) = holder.bind(cards[position])

    inner class CardHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val frame: View = view.findViewById(R.id.card_frame)
        private val image: ImageView = view.findViewById(R.id.card_image)
        private val placeholder: TextView = view.findViewById(R.id.card_placeholder)
        private val bar: ProgressBar = view.findViewById(R.id.card_progress)
        private val title: TextView = view.findViewById(R.id.card_title)
        private val subtitle: TextView = view.findViewById(R.id.card_subtitle)
        private val badge: View = view.findViewById(R.id.card_badge)
        private val lift = view.resources.getDimension(R.dimen.tile_lift)
        private val logoPadding = view.resources.getDimensionPixelSize(R.dimen.card_logo_padding)

        // The card at click time: one left unchanged by a refresh keeps its old binding, not its old actions.
        private val card get() = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(cards::get)

        init {
            frame.clipToOutline = true
            view.setOnClickListener { card?.open?.invoke() }
            view.setOnLongClickListener { v ->
                val menu = card?.menu ?: return@setOnLongClickListener false
                menu(v)
                true
            }
            view.setOnFocusChangeListener { _, hasFocus ->
                showLift(frame, hasFocus, animate = true, FOCUS_SCALE, lift)
                if (hasFocus) card?.let(onFocus)
            }
        }

        fun bind(card: Card) {
            // A logo's background is see-through, so its placeholder would show around it.
            placeholder.text = if (card.logo && card.image != null) null else card.placeholder
            image.scaleType = if (card.logo) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            val padding = if (card.logo) logoPadding else 0
            image.setPadding(padding, padding, padding, padding)
            (if (card.logo) logos else pictures).show(card.image, image)
            badge.isVisible = card.live
            bar.isVisible = card.progress != null
            card.progress?.let { bar.progress = (it * bar.max).toInt() }
            title.text = card.title
            subtitle.text = card.subtitle
            subtitle.isVisible = card.subtitle.isNotEmpty()
            itemView.contentDescription = listOf(card.title, card.subtitle).filter { it.isNotEmpty() }.joinToString(", ")
            showLift(frame, itemView.isFocused, animate = false, FOCUS_SCALE, lift)
        }
    }

    private companion object {
        const val FOCUS_SCALE = 1.08f
    }
}
