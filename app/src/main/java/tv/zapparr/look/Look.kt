package tv.zapparr.look

/** The sections of Zapparr's settings, in the order the settings screen lists them. */
enum class Section { LOOK, HOME, GUIDE, LIBRARY }

private val ON_OFF = listOf("on", "off")
private val OFF_ON = listOf("off", "on")

/**
 * One of Zapparr's settings: stored under [key], one of [values], the first being the default.
 * The palette is chosen from a list of its own (`Palettes`), so its values aren't listed here.
 * Free of Android, so it is unit-tested.
 */
enum class Option(val key: String, val section: Section, val values: List<String>) {
    PALETTE("palette", Section.LOOK, listOf(DEFAULT_PALETTE)),
    BACKGROUND("background", Section.LOOK, listOf("glow", "solid")),
    ZOOM("zoom", Section.LOOK, ON_OFF),
    GLOW("glow", Section.LOOK, ON_OFF),
    CLOCK("clock", Section.LOOK, listOf("tv", "12", "24")),
    DATE("date", Section.LOOK, ON_OFF),
    CONTINUE_WATCHING("continue_watching", Section.HOME, ON_OFF),
    ON_NOW("on_now", Section.HOME, ON_OFF),
    APP_NAMES("app_names", Section.HOME, listOf("focused", "always")),
    HOME_SPACING("home_spacing", Section.HOME, listOf("roomy", "compact")),
    GUIDE_ROWS("guide_rows", Section.GUIDE, listOf("normal", "compact", "roomy")),
    CHANNEL_NUMBERS("channel_numbers", Section.GUIDE, ON_OFF),
    CHANNEL_LOGOS("channel_logos", Section.GUIDE, ON_OFF),
    NOW_LINE("now_line", Section.GUIDE, listOf("full", "top")),
    PROGRAM_PROGRESS("program_progress", Section.GUIDE, ON_OFF),
    CHANNEL_ORDER("channel_order", Section.GUIDE, listOf("yours", "number", "name")),
    SHOW_HIDDEN("show_hidden", Section.GUIDE, OFF_ON),
    POSTER_TITLES("poster_titles", Section.LIBRARY, OFF_ON),
    BACKDROP("backdrop", Section.LIBRARY, ON_OFF);

    val default: String get() = values.first()

    /** The value after [value], round to the first again; the first for a value no longer offered. */
    fun next(value: String): String = values[(values.indexOf(value) + 1) % values.size]
}

/**
 * The settings as screens read them: [values] as stored, with the default for any missing or no
 * longer offered. [current] is what the screen on top applied (see `applyLook`).
 */
data class Look(private val values: Map<Option, String> = emptyMap()) {

    operator fun get(option: Option): String {
        val value = values[option] ?: return option.default
        // Any stored palette counts; Palettes knows which exist and falls back itself.
        return if (option == Option.PALETTE || value in option.values) value else option.default
    }

    fun on(option: Option) = get(option) == "on"

    /** This look with [option] set to [value]. */
    fun with(option: Option, value: String) = Look(values + (option to value))

    companion object {
        @Volatile
        var current = Look()
    }
}

/** Zapparr's own palette, the first in the list. */
const val DEFAULT_PALETTE = "zapparr"
