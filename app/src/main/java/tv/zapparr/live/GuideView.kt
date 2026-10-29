package tv.zapparr.live

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import androidx.core.content.ContextCompat
import tv.zapparr.R
import tv.zapparr.home.DeckPager
import java.util.Date
import kotlin.math.max
import kotlin.math.min

/**
 * The Guide's time grid, drawn as one view: channels down the side, half hours across the top and
 * a line at the current time. Up and Down change channel and keep to the same time, as a cable
 * box does; Left and Right step through programs. Before the program on now, or past the end of
 * the guide, Left and Right are left to the [DeckPager] to change deck.
 */
class GuideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), DeckPager.Sideways {

    /** The focused channel and cell, on every move and as time passes, for the details above the grid. */
    var onSelected: ((Channel, Guide.Cell) -> Unit)? = null
    var onWatch: ((Channel) -> Unit)? = null
    var onHold: ((Channel) -> Unit)? = null

    /** Stream ids of the favorite channels, marked beside their names. */
    var favorites: Set<Int> = emptySet()
        set(value) {
            field = value
            invalidate()
        }

    /** Shown in place of the grid while there are no channels. */
    var emptyText: String? = null

    private var channels: List<Channel> = emptyList()
    private var programs: Map<String, List<Program>> = emptyMap()
    private var until = 0L
    private val rows = HashMap<Int, List<Guide.Cell>>() // by stream id, built as rows come on screen

    private var origin = 0L // the half hour now is in: the grid never scrolls back past it
    private var viewStart = 0L // the time at the grid's left edge
    private var anchor = 0L // the time Up and Down keep to
    private var row = 0
    private var cell = 0
    private var topRow = 0
    private var held = false

    private val rowHeight = resources.getDimension(R.dimen.guide_row_height)
    private val headerHeight = resources.getDimension(R.dimen.guide_header_height)
    private val channelWidth = resources.getDimension(R.dimen.guide_channel_width)
    private val numberWidth = resources.getDimension(R.dimen.guide_number_width)
    private val logoWidth = resources.getDimension(R.dimen.guide_logo_width)
    private val logoHeight = resources.getDimension(R.dimen.guide_logo_height)
    private val gap = resources.getDimension(R.dimen.guide_gap)
    private val padding = resources.getDimension(R.dimen.guide_padding)
    private val corner = resources.getDimension(R.dimen.guide_corner)
    private val nowWidth = resources.getDimension(R.dimen.guide_now_width)
    private val markRadius = resources.getDimension(R.dimen.guide_mark_radius)

    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val onAccent = ContextCompat.getColor(context, R.color.on_accent)
    private val surface = ContextCompat.getColor(context, R.color.surface)
    private val airing = ContextCompat.getColor(context, R.color.guide_airing)
    private val textPrimary = ContextCompat.getColor(context, R.color.text_primary)
    private val textSecondary = ContextCompat.getColor(context, R.color.text_secondary)
    private val textTertiary = ContextCompat.getColor(context, R.color.text_tertiary)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.getDimension(R.dimen.guide_outline)
        color = accent
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = resources.getDimension(R.dimen.guide_title_text) }
    private val smallPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = resources.getDimension(R.dimen.guide_small_text) }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val box = RectF()
    private val date = Date()
    private val timeFormat = DateFormat.getTimeFormat(context)
    private val noInfo = context.getString(R.string.guide_no_info)
    private val logos = Logos(logoHeight.toInt()) { invalidate() }

    private val tick = object : Runnable {
        override fun run() {
            onTick()
            postDelayed(this, TICK_MS)
        }
    }

    private val viewEnd get() = viewStart + Guide.SPAN
    private val visibleRows get() = max(1, ((height - headerHeight) / rowHeight).toInt())

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
    }

    /**
     * Shows [channels], in order, with their [programs] by XMLTV channel id, known up to [until].
     * The focused channel stays focused if it is still in the list; otherwise [keepRow] keeps the
     * same row, or the first channel is focused.
     */
    fun show(channels: List<Channel>, programs: Map<String, List<Program>>, until: Long, keepRow: Boolean) {
        val focused = this.channels.getOrNull(row)?.streamId
        this.channels = channels
        this.programs = programs
        this.until = until
        rows.clear()
        if (origin == 0L) {
            val now = System.currentTimeMillis()
            origin = Guide.halfHour(now)
            viewStart = origin
            anchor = now
        }
        val index = channels.indexOfFirst { it.streamId == focused }
        select(if (index >= 0) index else if (keepRow) min(row, max(0, channels.lastIndex)) else 0)
    }

    override fun canMove(right: Boolean): Boolean {
        if (channels.isEmpty()) return false
        return if (right) cell < cellsOf(row).lastIndex else cell > 0
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (channels.isEmpty()) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> moveTo(row - 1)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveTo(row + 1)
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> moveTo(max(0, row - visibleRows))
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> moveTo(min(channels.lastIndex, row + visibleRows))
            KeyEvent.KEYCODE_DPAD_LEFT -> step(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> step(1)
            in OK_KEYS -> {
                // Tracked so that holding OK reaches onKeyLongPress.
                if (event.repeatCount == 0) {
                    held = false
                    event.startTracking()
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode !in OK_KEYS || channels.isEmpty()) return super.onKeyLongPress(keyCode, event)
        held = true
        onHold?.invoke(channels[row])
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode !in OK_KEYS || channels.isEmpty()) return super.onKeyUp(keyCode, event)
        if (event.isTracking && !event.isCanceled && !held) onWatch?.invoke(channels[row])
        held = false
        return true
    }

    // Moving focus out of the grid starts from the focused cell, so Up lands on the filter above it.
    override fun getFocusedRect(r: Rect) {
        if (channels.isEmpty() || width == 0) return super.getFocusedRect(r)
        val c = cellsOf(row)[cell]
        val top = headerHeight + (row - topRow) * rowHeight
        r.set(max(x(c.start), channelWidth).toInt(), top.toInt(), min(x(c.end), width.toFloat()).toInt(), (top + rowHeight).toInt())
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        topRow = topRow.coerceIn(max(0, row - visibleRows + 1), row)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(tick, TICK_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (channels.isEmpty()) {
            val text = emptyText ?: return
            titlePaint.color = textSecondary
            val shown = TextUtils.ellipsize(text, titlePaint, width.toFloat(), TextUtils.TruncateAt.END)
            canvas.drawText(shown, 0, shown.length, (width - titlePaint.measureText(shown, 0, shown.length)) / 2, height / 3f, titlePaint)
            return
        }
        val now = System.currentTimeMillis()
        smallPaint.color = textSecondary
        var t = viewStart
        while (t < viewEnd) {
            date.time = t
            canvas.drawText(timeFormat.format(date), x(t) + padding, baseline(smallPaint, 0f, headerHeight), smallPaint)
            t += Guide.HALF_HOUR
        }
        val last = min(channels.lastIndex, topRow + visibleRows - 1)
        for (index in topRow..last) {
            val top = headerHeight + (index - topRow) * rowHeight
            drawChannel(canvas, index, top)
            drawCells(canvas, index, top, now)
        }
        if (now in viewStart until viewEnd) {
            fill.color = accent
            val x = x(now)
            canvas.drawRect(x - nowWidth / 2, headerHeight - gap, x + nowWidth / 2, headerHeight + (last - topRow + 1) * rowHeight, fill)
        }
    }

    private fun drawChannel(canvas: Canvas, index: Int, top: Float) {
        val channel = channels[index]
        if (index == row) {
            fill.color = airing
            box.set(0f, top + gap / 2, channelWidth - gap, top + rowHeight - gap / 2)
            canvas.drawRoundRect(box, corner, corner, fill)
        }
        smallPaint.color = textSecondary
        val number = channel.number.toString()
        canvas.drawText(number, numberWidth - smallPaint.measureText(number), baseline(smallPaint, top, rowHeight), smallPaint)
        val logoLeft = numberWidth + padding
        channel.logo?.let(logos::get)?.let { bitmap ->
            val scale = min(logoWidth / bitmap.width, logoHeight / bitmap.height)
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            box.set(logoLeft + (logoWidth - w) / 2, top + (rowHeight - h) / 2, logoLeft + (logoWidth + w) / 2, top + (rowHeight + h) / 2)
            canvas.drawBitmap(bitmap, null, box, bitmapPaint)
        }
        var left = logoLeft + logoWidth + padding
        if (channel.streamId in favorites) {
            fill.color = accent
            canvas.drawCircle(left + markRadius, top + rowHeight / 2, markRadius, fill)
            left += markRadius * 2 + padding / 2
        }
        titlePaint.color = textPrimary
        drawText(canvas, channel.name, left, channelWidth - gap - padding, top)
    }

    private fun drawCells(canvas: Canvas, index: Int, top: Float, now: Long) {
        cellsOf(index).forEachIndexed { i, c ->
            if (c.end <= viewStart || c.start >= viewEnd) return@forEachIndexed
            val left = max(x(c.start), channelWidth) + gap / 2
            val right = min(x(c.end), width.toFloat()) - gap / 2
            if (right - left < 1f) return@forEachIndexed
            val selected = index == row && i == cell
            box.set(left, top + gap / 2, right, top + rowHeight - gap / 2)
            fill.color = when {
                selected && isFocused -> accent
                c.start <= now && now < c.end -> airing
                else -> surface
            }
            canvas.drawRoundRect(box, corner, corner, fill)
            if (selected && !isFocused) canvas.drawRoundRect(box, corner, corner, outline)
            titlePaint.color = when {
                selected && isFocused -> onAccent
                c.program == null -> textTertiary
                else -> textPrimary
            }
            val title = c.program?.title ?: noInfo
            // A program that began before the left edge is marked, as on a cable box.
            val text = if ((c.program?.start ?: c.start) < viewStart) "‹ $title" else title
            drawText(canvas, text, left + padding, right - padding, top)
        }
    }

    private fun drawText(canvas: Canvas, text: String, left: Float, right: Float, top: Float) {
        if (right - left < 1f) return
        val shown = TextUtils.ellipsize(text, titlePaint, right - left, TextUtils.TruncateAt.END)
        canvas.drawText(shown, 0, shown.length, left, baseline(titlePaint, top, rowHeight), titlePaint)
    }

    private fun baseline(paint: Paint, top: Float, height: Float) = top + height / 2 - (paint.descent() + paint.ascent()) / 2

    private fun x(time: Long) = channelWidth + (time - viewStart) * (width - channelWidth) / Guide.SPAN

    private fun cellsOf(index: Int): List<Guide.Cell> {
        val channel = channels[index]
        return rows.getOrPut(channel.streamId) {
            Guide.cells(programs[channel.epgId].orEmpty(), origin, max(until, origin + Guide.SPAN))
        }
    }

    private fun moveTo(index: Int): Boolean {
        if (index !in channels.indices) return false // Up from the first channel leaves for the filters
        if (index != row) select(index)
        return true
    }

    private fun step(by: Int): Boolean {
        val cells = cellsOf(row)
        val next = cell + by
        if (next !in cells.indices) return false
        cell = next
        viewStart = Guide.scroll(viewStart, cells[next], origin)
        // Never before now: Down from the program on now lands on what's on now, not on one that just ended.
        anchor = maxOf(cells[next].start, viewStart, System.currentTimeMillis())
        changed()
        return true
    }

    private fun select(index: Int) {
        row = index
        if (channels.isEmpty()) {
            cell = 0
            topRow = 0
        } else {
            cell = Guide.indexAt(cellsOf(row), max(anchor, viewStart))
            topRow = topRow.coerceIn(max(0, row - visibleRows + 1), row)
        }
        changed()
    }

    // Each half hour the grid's start moves on; the rows are cut again from there.
    private fun onTick() {
        val half = Guide.halfHour(System.currentTimeMillis())
        if (origin != 0L && half != origin) {
            origin = half
            viewStart = max(viewStart, origin)
            anchor = max(anchor, origin)
            rows.clear()
            if (channels.isNotEmpty()) cell = Guide.indexAt(cellsOf(row), max(anchor, viewStart))
        }
        changed()
    }

    private fun changed() {
        invalidate()
        val channel = channels.getOrNull(row) ?: return
        val selected = cellsOf(row)[cell]
        contentDescription = channel.name + ", " + (selected.program?.title ?: noInfo)
        onSelected?.invoke(channel, selected)
    }

    private companion object {
        const val TICK_MS = 30_000L
        val OK_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
    }
}
