package tv.zapparr.look

import android.app.Activity
import android.content.Context
import android.text.format.DateFormat
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.StyleRes
import androidx.core.content.edit
import tv.zapparr.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A color theme: its [key] in the settings, the [name] Settings shows, and its colors' [style] in palettes.xml. */
class Palette(val key: String, val name: String, @param:StyleRes val style: Int)

/**
 * The color themes, in the order Settings shows them: Zapparr's own, cable-box looks, then
 * well-known editor themes, each from its published palette (sources in palettes.xml).
 */
object Palettes {
    val ALL = listOf(
        Palette(DEFAULT_PALETTE, "Zapparr", R.style.Palette_Zapparr),
        Palette("cable_blue", "Cable Blue", R.style.Palette_CableBlue),
        Palette("retro_guide", "Retro Guide", R.style.Palette_RetroGuide),
        Palette("phosphor", "Phosphor", R.style.Palette_Phosphor),
        Palette("amber", "Amber", R.style.Palette_Amber),
        Palette("crimson", "Crimson", R.style.Palette_Crimson),
        Palette("graphite", "Graphite", R.style.Palette_Graphite),
        Palette("dracula", "Dracula", R.style.Palette_Dracula),
        Palette("nord", "Nord", R.style.Palette_Nord),
        Palette("gruvbox", "Gruvbox", R.style.Palette_Gruvbox),
        Palette("solarized", "Solarized", R.style.Palette_Solarized),
        Palette("catppuccin", "Catppuccin", R.style.Palette_Catppuccin),
        Palette("tokyo_night", "Tokyo Night", R.style.Palette_TokyoNight),
        Palette("one_dark", "One Dark", R.style.Palette_OneDark),
        Palette("monokai", "Monokai", R.style.Palette_Monokai),
        Palette("rose_pine", "Rosé Pine", R.style.Palette_RosePine),
        Palette("everforest", "Everforest", R.style.Palette_Everforest),
        Palette("github_dark", "GitHub Dark", R.style.Palette_GitHubDark),
        Palette("night_owl", "Night Owl", R.style.Palette_NightOwl),
        Palette("synthwave", "SynthWave '84", R.style.Palette_Synthwave),
        Palette("cobalt2", "Cobalt2", R.style.Palette_Cobalt2),
        Palette("kanagawa", "Kanagawa", R.style.Palette_Kanagawa),
    )

    /** The palette saved as [key], or Zapparr's own for one that no longer exists. */
    fun of(key: String): Palette = ALL.firstOrNull { it.key == key } ?: ALL.first()
}

/** Zapparr's settings, kept on the box. */
class LookStore(context: Context) {
    private val prefs = context.getSharedPreferences("look", Context.MODE_PRIVATE)

    fun load() = Look(Option.entries.mapNotNull { option -> prefs.getString(option.key, null)?.let { option to it } }.toMap())

    fun set(option: Option, value: String) = prefs.edit { putString(option.key, value) }

    /** Every setting back to its default. */
    fun reset() = prefs.edit { clear() }
}

/**
 * Lays the chosen palette, and the options that change drawing, over this screen's theme, and
 * makes them [Look.current]. Call first in onCreate, before super.onCreate, so the window's
 * background is drawn with them too. Returns what was applied, so a screen can tell later
 * whether Settings changed it.
 */
fun Activity.applyLook(): Look {
    val look = LookStore(this).load()
    theme.applyStyle(Palettes.of(look[Option.PALETTE]).style, true)
    if (look[Option.BACKGROUND] == "solid") theme.applyStyle(R.style.Look_SolidBackground, true)
    Look.current = look
    return look
}

/** How a time of day reads, as Settings > Clock says: 12-hour, 24-hour, or the way the TV is set. */
fun Context.timeFormat(): java.text.DateFormat {
    val locale = Locale.getDefault()
    return when (Look.current[Option.CLOCK]) {
        "12" -> SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "hma"), locale)
        "24" -> SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "Hm"), locale)
        else -> DateFormat.getTimeFormat(this)
    }
}

/** [time] as a time of day, by [timeFormat]. */
fun Context.formatTime(time: Long): String = timeFormat().format(Date(time))

/** A palette color ([R.attr.zAccent] and the like) from this context's theme. */
@ColorInt
fun Context.themeColor(@AttrRes attr: Int): Int {
    val values = obtainStyledAttributes(intArrayOf(attr))
    try {
        return values.getColor(0, 0)
    } finally {
        values.recycle()
    }
}
