package tv.zapparr.look

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.View
import tv.zapparr.R

/**
 * A small picture of a screen in a [Palette]'s colors: its background, a line of text, a tab in
 * its accent, and a row of tiles, the first with the focus ring.
 */
class PaletteSwatch @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2
    }
    private val box = RectF()
    private val corner = resources.getDimension(R.dimen.corner)
    private var colors = IntArray(6)

    fun show(palette: Palette) {
        val themed = ContextThemeWrapper(context, palette.style)
        colors = intArrayOf(R.attr.zBackground, R.attr.zSurface, R.attr.zSurfaceHigh, R.attr.zAccent, R.attr.zText, R.attr.zTextDim)
            .map { themed.themeColor(it) }.toIntArray()
        contentDescription = palette.name
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val background = colors[0]
        val surface = colors[1]
        val surfaceHigh = colors[2]
        val accent = colors[3]
        val text = colors[4]
        val dim = colors[5]
        val w = width.toFloat()
        val h = height.toFloat()
        fill.color = background
        box.set(0f, 0f, w, h)
        canvas.drawRoundRect(box, corner, corner, fill)
        // A title and a line under it, then a tab in the accent at the top right.
        fill.color = text
        box.set(w * 0.08f, h * 0.12f, w * 0.48f, h * 0.2f)
        canvas.drawRoundRect(box, h, h, fill)
        fill.color = dim
        box.set(w * 0.08f, h * 0.26f, w * 0.34f, h * 0.32f)
        canvas.drawRoundRect(box, h, h, fill)
        fill.color = accent
        box.set(w * 0.66f, h * 0.1f, w * 0.92f, h * 0.24f)
        canvas.drawRoundRect(box, h, h, fill)
        // Three tiles: the first lifted and ringed, as focus looks.
        val top = h * 0.48f
        val bottom = h * 0.86f
        val tile = (w * 0.84f - w * 0.06f) / 3
        for (i in 0 until 3) {
            val left = w * 0.08f + i * (tile + w * 0.03f)
            box.set(left, top, left + tile, bottom)
            fill.color = if (i == 0) surfaceHigh else surface
            canvas.drawRoundRect(box, corner / 2, corner / 2, fill)
            if (i == 0) {
                ring.color = accent
                canvas.drawRoundRect(box, corner / 2, corner / 2, ring)
            }
        }
    }
}
