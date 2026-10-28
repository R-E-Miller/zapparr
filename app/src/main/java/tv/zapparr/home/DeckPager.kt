package tv.zapparr.home

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView

/**
 * Holds the decks side by side and shows one at a time.
 *
 * Left and Right move focus inside the current deck while there is something on the same line
 * to move to. At the edge of a deck they slide to the neighboring deck instead, so focus never
 * jumps diagonally into another row.
 */
class DeckPager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var current = Decks.APPS
        private set

    var onDeckChanged: ((Int) -> Unit)? = null

    private val slide = DecelerateInterpolator(1.5f)
    private val isRtl get() = layoutDirection == LAYOUT_DIRECTION_RTL

    override fun onFinishInflate() {
        super.onFinishInflate()
        for (i in 0 until childCount) {
            val deck = getChildAt(i)
            deck.visibility = if (i == current) VISIBLE else INVISIBLE
            setFocusable(deck, i == current)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) for (i in 0 until childCount) getChildAt(i).translationX = offsetOf(i)
    }

    // When focus has to be rebuilt (leaving touch mode, a row reloading under the focused tile), it
    // goes to the deck on screen rather than the window's first focusable, the Settings button.
    override fun restoreDefaultFocus(): Boolean = getChildAt(current)?.requestFocus() ?: false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> FOCUS_RIGHT
            else -> return super.dispatchKeyEvent(event)
        }
        val focused = findFocus()
        if (event.action != KeyEvent.ACTION_DOWN || focused == null || canMoveWithinDeck(focused, direction)) {
            return super.dispatchKeyEvent(event)
        }
        // At the edge: a fresh press changes deck, while a held key stops here rather than overshooting.
        if (event.repeatCount == 0) {
            Decks.neighbor(current, childCount, direction == FOCUS_RIGHT, isRtl)?.let { show(it, takeFocus = true) }
        }
        return true
    }

    /** Slides [index] into view; with [takeFocus], focus moves into it (grids restore their selection). */
    fun show(index: Int, takeFocus: Boolean) {
        if (index !in 0 until childCount) return
        val changed = index != current
        current = index
        val incoming = getChildAt(index)
        incoming.visibility = VISIBLE
        setFocusable(incoming, true)
        if (takeFocus) incoming.requestFocus()
        for (i in 0 until childCount) {
            val deck = getChildAt(i)
            if (i != current) setFocusable(deck, false)
            deck.animate()
                .translationX(offsetOf(i))
                .setDuration(if (changed) SLIDE_MS else 0)
                .setInterpolator(slide)
                .withEndAction { if (i != current) deck.visibility = INVISIBLE }
                .start()
        }
        if (changed) onDeckChanged?.invoke(index)
    }

    private fun offsetOf(index: Int): Float = (index - current) * width * if (isRtl) -1f else 1f

    private fun setFocusable(deck: View, focusable: Boolean) {
        (deck as? ViewGroup)?.descendantFocusability =
            if (focusable) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    private fun canMoveWithinDeck(focused: View, direction: Int): Boolean {
        // A scrolling row knows about tiles that aren't laid out yet, so ask it by position.
        val row = focused.parent as? RecyclerView
        if (row != null && row.layoutManager?.canScrollHorizontally() == true) {
            val position = row.getChildAdapterPosition(focused)
            val forward = (direction == FOCUS_RIGHT) != (row.layoutDirection == LAYOUT_DIRECTION_RTL)
            return if (forward) position < (row.adapter?.itemCount ?: 0) - 1 else position > 0
        }
        val deck = getChildAt(current) as? ViewGroup ?: return false
        val next = FocusFinder.getInstance().findNextFocus(deck, focused, direction) ?: return false
        return onSameLine(focused, next)
    }

    private fun onSameLine(a: View, b: View): Boolean {
        val ra = boundsOf(a)
        val rb = boundsOf(b)
        return ra.top < rb.bottom && rb.top < ra.bottom
    }

    private fun boundsOf(view: View) = Rect().also {
        view.getDrawingRect(it)
        offsetDescendantRectToMyCoords(view, it)
    }

    private companion object {
        const val SLIDE_MS = 220L
    }
}
