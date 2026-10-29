package tv.zapparr.home

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.zapparr.live.Problem
import tv.zapparr.live.Server
import tv.zapparr.live.ServerDeck
import tv.zapparr.live.ServerStore
import tv.zapparr.live.XcException

/**
 * Keeps a [deck] fed from the server saved in [store]. [refresh] loads when the server changes, when
 * the last try failed, or once what's shown is [maxAge] old. A reload keeps the old content on screen
 * until the new one is in; a failed reload keeps it too, and tries again next time.
 */
internal class ServerFeed<T : Any>(
    private val scope: CoroutineScope,
    private val store: ServerStore,
    private val deck: ServerDeck<T>,
    private val maxAge: Long,
    private val describe: (Problem, String) -> String,
    private val fetch: (Server) -> T,
) {
    private var job: Job? = null
    private var started = false
    private var server: Server? = null
    private var loadedAt = 0L
    private var failed = false

    fun refresh() {
        val saved = store.load()
        val now = System.currentTimeMillis()
        val sameServer = started && saved == server
        val current = saved == null || job?.isActive == true || now - loadedAt < maxAge
        if (sameServer && !failed && current) return
        job?.cancel()
        started = true
        server = saved
        failed = false
        if (saved == null) {
            deck.showNotSetUp()
            return
        }
        if (!sameServer || !deck.showsContent) deck.showLoading(saved.address)
        job = scope.launch {
            val result: Any = withContext(Dispatchers.IO) {
                try {
                    fetch(saved)
                } catch (e: XcException) {
                    e.problem
                } catch (e: Exception) { // IOException while reading a reply
                    Problem.UNREACHABLE
                }
            }
            if (result is Problem) {
                failed = true
                if (!deck.showsContent) deck.showProblem(describe(result, saved.address))
            } else {
                loadedAt = now
                @Suppress("UNCHECKED_CAST")
                deck.show(saved, result as T)
            }
        }
    }
}
