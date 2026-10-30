package tv.zapparr.look

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.RecyclerView
import tv.zapparr.R
import tv.zapparr.apps.showLift

/**
 * Settings > Color theme: every palette as a small screen in its colors, four to a row. OK
 * chooses one; Settings and Home draw themselves again in it.
 */
class PalettesActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val look = applyLook()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_palettes)
        val store = LookStore(this)
        val current = Palettes.of(look[Option.PALETTE])
        val grid = findViewById<VerticalGridView>(R.id.palettes)
        grid.adapter = PalettesAdapter(current) { palette ->
            store.set(Option.PALETTE, palette.key)
            finish()
        }
        grid.selectedPosition = Palettes.ALL.indexOf(current)
    }

    private class PalettesAdapter(private val current: Palette, private val onChoose: (Palette) -> Unit) :
        RecyclerView.Adapter<PalettesAdapter.Holder>() {

        override fun getItemCount() = Palettes.ALL.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.tile_palette, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(Palettes.ALL[position])

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val frame: View = view.findViewById(R.id.palette_frame)
            private val swatch: PaletteSwatch = view.findViewById(R.id.palette_swatch)
            private val name: TextView = view.findViewById(R.id.palette_name)
            private val lift = view.resources.getDimension(R.dimen.tile_lift)

            init {
                frame.clipToOutline = true
                view.setOnFocusChangeListener { _, hasFocus -> showLift(frame, hasFocus, animate = true, FOCUS_SCALE, lift) }
            }

            fun bind(palette: Palette) {
                swatch.show(palette)
                // The one in use is ticked.
                name.text = if (palette == current) itemView.context.getString(R.string.palette_current, palette.name) else palette.name
                itemView.setOnClickListener { onChoose(palette) }
                showLift(frame, itemView.isFocused, animate = false, FOCUS_SCALE, lift)
            }
        }
    }

    private companion object {
        const val FOCUS_SCALE = 1.08f
    }
}
