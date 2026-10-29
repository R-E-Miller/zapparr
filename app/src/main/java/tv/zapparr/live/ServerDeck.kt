package tv.zapparr.live

/** A deck fed from a server, like the Guide or the Library: a message while there is nothing to show, then [T]. */
interface ServerDeck<T> {
    /** Whether [show]'s content is up, rather than a message. */
    val showsContent: Boolean

    fun showNotSetUp()

    fun showLoading(address: String)

    fun showProblem(text: String)

    fun show(server: Server, loaded: T)
}
