package tv.zapparr.look

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.isNotEmpty
import tv.zapparr.R
import tv.zapparr.apps.openTvSettings
import tv.zapparr.backup.BackupActivity
import tv.zapparr.backup.Backups
import tv.zapparr.live.ServerActivity
import tv.zapparr.live.ServerKind

/**
 * Zapparr's settings, laid out like TiviMate's: the sections down the left (Look, Home, Guide,
 * Library, System), the focused one's options on the right. OK on an option moves it to its next
 * value; the color theme opens [PalettesActivity]. Home draws itself again with the new settings
 * when it comes back.
 */
class SettingsActivity : ComponentActivity() {

    private lateinit var applied: Look
    private var restored = Backups.generation
    private lateinit var store: LookStore
    private lateinit var sections: LinearLayout
    private lateinit var options: LinearLayout
    private var shown = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        applied = applyLook()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        store = LookStore(this)
        sections = findViewById(R.id.settings_sections)
        options = findViewById(R.id.settings_options)
        val inflater = LayoutInflater.from(this)
        SECTIONS.forEachIndexed { index, title ->
            val item = inflater.inflate(R.layout.item_section, sections, false) as TextView
            item.setText(title)
            // Focusing a section shows its options, as the tabs on Home show their decks.
            item.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) show(index) }
            item.setOnClickListener { options.getChildAt(0)?.requestFocus() }
            sections.addView(item)
        }
        show(savedInstanceState?.getInt(SHOWN) ?: 0)
        // Drawn again after a change (the background, the palette): focus goes back where it was.
        val focused = savedInstanceState?.getString(FOCUSED)?.let { options.findViewWithTag<View>(it) }
        (focused ?: sections.getChildAt(shown)).requestFocus()
    }

    override fun onResume() {
        super.onResume()
        if (store.load() != applied || Backups.generation != restored) recreate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(SHOWN, shown)
        (currentFocus?.tag as? String)?.let { outState.putString(FOCUSED, it) }
    }

    private fun show(index: Int) {
        if (index == shown && options.isNotEmpty()) return
        shown = index
        for (i in 0 until sections.childCount) sections.getChildAt(i).isActivated = i == index
        options.removeAllViews()
        val section = Section.entries.getOrNull(index)
        if (section != null) {
            Option.entries.filter { it.section == section }.forEach(::addOption)
            return
        }
        addAction("tv", R.string.setting_tv, R.string.setting_tv_hint) { openTvSettings() }
        addAction("live", R.string.setting_live_server, R.string.setting_live_server_hint) { ServerActivity.open(this, ServerKind.LIVE) }
        addAction("library", R.string.setting_library_server, R.string.setting_library_server_hint) {
            ServerActivity.open(this, ServerKind.LIBRARY)
        }
        addAction("backup", R.string.setting_backup, R.string.setting_backup_hint) { startActivity(Intent(this, BackupActivity::class.java)) }
        addAction("reset", R.string.setting_reset, R.string.setting_reset_hint) {
            store.reset()
            recreate()
        }
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        addAction("about", R.string.setting_about, R.string.setting_about_hint, value = version) {}
    }

    private fun addOption(option: Option) {
        val row = addRow(option.key, TITLES.getValue(option), HINTS.getValue(option))
        val value = row.findViewById<TextView>(R.id.option_value)
        value.text = labelOf(option, store.load()[option])
        row.setOnClickListener {
            if (option == Option.PALETTE) {
                startActivity(Intent(this, PalettesActivity::class.java))
                return@setOnClickListener
            }
            val next = option.next(store.load()[option])
            store.set(option, next)
            Look.current = store.load()
            // The window draws the background, so it shows a change only when drawn again.
            if (option == Option.BACKGROUND) recreate() else value.text = labelOf(option, next)
        }
    }

    private fun addAction(tag: String, title: Int, hint: Int, value: String? = null, action: () -> Unit) {
        val row = addRow(tag, title, hint)
        row.findViewById<TextView>(R.id.option_value).text = value
        row.setOnClickListener { action() }
    }

    private fun addRow(tag: String, title: Int, hint: Int): View {
        val row = LayoutInflater.from(this).inflate(R.layout.row_option, options, false)
        row.tag = tag
        row.findViewById<TextView>(R.id.option_title).setText(title)
        row.findViewById<TextView>(R.id.option_hint).setText(hint)
        options.addView(row)
        return row
    }

    private fun labelOf(option: Option, value: String): String = when {
        option == Option.PALETTE -> Palettes.of(value).name
        value == "on" -> getString(if (option in SHOW_HIDE) R.string.value_show else R.string.value_on)
        value == "off" -> getString(if (option in SHOW_HIDE) R.string.value_hide else R.string.value_off)
        else -> getString(VALUES.getValue(value))
    }

    private companion object {
        const val SHOWN = "shown"
        const val FOCUSED = "focused"

        val SECTIONS = listOf(R.string.section_look, R.string.section_home, R.string.section_guide, R.string.section_library, R.string.section_system)

        // On and off read as Show and Hide for these.
        val SHOW_HIDE = setOf(
            Option.DATE, Option.CONTINUE_WATCHING, Option.ON_NOW, Option.CHANNEL_NUMBERS, Option.CHANNEL_LOGOS,
            Option.POSTER_TITLES, Option.BACKDROP, Option.SHOW_HIDDEN,
        )

        val TITLES = mapOf(
            Option.PALETTE to R.string.setting_palette,
            Option.BACKGROUND to R.string.setting_background,
            Option.ZOOM to R.string.setting_zoom,
            Option.GLOW to R.string.setting_glow,
            Option.CLOCK to R.string.setting_clock,
            Option.DATE to R.string.setting_date,
            Option.CONTINUE_WATCHING to R.string.continue_watching,
            Option.ON_NOW to R.string.on_now,
            Option.APP_NAMES to R.string.setting_app_names,
            Option.HOME_SPACING to R.string.setting_home_spacing,
            Option.GUIDE_ROWS to R.string.setting_guide_rows,
            Option.CHANNEL_NUMBERS to R.string.setting_channel_numbers,
            Option.CHANNEL_LOGOS to R.string.setting_channel_logos,
            Option.NOW_LINE to R.string.setting_now_line,
            Option.PROGRAM_PROGRESS to R.string.setting_program_progress,
            Option.CHANNEL_ORDER to R.string.setting_channel_order,
            Option.SHOW_HIDDEN to R.string.setting_show_hidden,
            Option.POSTER_TITLES to R.string.setting_poster_titles,
            Option.BACKDROP to R.string.setting_backdrop,
        )

        val HINTS = mapOf(
            Option.PALETTE to R.string.setting_palette_hint,
            Option.BACKGROUND to R.string.setting_background_hint,
            Option.ZOOM to R.string.setting_zoom_hint,
            Option.GLOW to R.string.setting_glow_hint,
            Option.CLOCK to R.string.setting_clock_hint,
            Option.DATE to R.string.setting_date_hint,
            Option.CONTINUE_WATCHING to R.string.setting_continue_watching_hint,
            Option.ON_NOW to R.string.setting_on_now_hint,
            Option.APP_NAMES to R.string.setting_app_names_hint,
            Option.HOME_SPACING to R.string.setting_home_spacing_hint,
            Option.GUIDE_ROWS to R.string.setting_guide_rows_hint,
            Option.CHANNEL_NUMBERS to R.string.setting_channel_numbers_hint,
            Option.CHANNEL_LOGOS to R.string.setting_channel_logos_hint,
            Option.NOW_LINE to R.string.setting_now_line_hint,
            Option.PROGRAM_PROGRESS to R.string.setting_program_progress_hint,
            Option.CHANNEL_ORDER to R.string.setting_channel_order_hint,
            Option.SHOW_HIDDEN to R.string.setting_show_hidden_hint,
            Option.POSTER_TITLES to R.string.setting_poster_titles_hint,
            Option.BACKDROP to R.string.setting_backdrop_hint,
        )

        val VALUES = mapOf(
            "glow" to R.string.value_glow,
            "solid" to R.string.value_solid,
            "tv" to R.string.value_clock_tv,
            "12" to R.string.value_clock_12,
            "24" to R.string.value_clock_24,
            "focused" to R.string.value_focused,
            "always" to R.string.value_always,
            "roomy" to R.string.value_roomy,
            "compact" to R.string.value_compact,
            "normal" to R.string.value_normal,
            "full" to R.string.value_full,
            "top" to R.string.value_top,
            "yours" to R.string.value_yours,
            "number" to R.string.value_by_number,
            "name" to R.string.value_by_name,
        )
    }
}
