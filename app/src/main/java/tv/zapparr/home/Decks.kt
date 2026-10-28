package tv.zapparr.home

/** The order of the decks on the home screen, and how Left and Right move between them. */
internal object Decks {
    const val APPS = 0

    /** The deck Left or Right leads to from [current], or null past either end. */
    fun neighbor(current: Int, count: Int, pressedRight: Boolean, rtl: Boolean): Int? {
        val next = current + if (pressedRight != rtl) 1 else -1
        return next.takeIf { it in 0 until count }
    }
}
