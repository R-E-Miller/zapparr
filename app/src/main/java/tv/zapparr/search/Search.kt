package tv.zapparr.search

import tv.zapparr.live.Catalog
import tv.zapparr.live.Channel
import tv.zapparr.live.Lineup
import tv.zapparr.live.Movie
import tv.zapparr.live.Program
import tv.zapparr.live.Series
import java.text.Normalizer

/** How typed text matches names. Free of Android, so it is unit-tested. */
object Search {

    /** Lower case, without accents, punctuation turned into single spaces: how names and queries are compared. */
    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        for (c in Normalizer.normalize(text, Normalizer.Form.NFD)) {
            when {
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> Unit
                c.isLetterOrDigit() -> out.append(c.lowercaseChar())
                out.isNotEmpty() && out.last() != ' ' -> out.append(' ')
            }
        }
        return out.trimEnd().toString()
    }

    /**
     * How well [name] matches [query] (both normalized), lower being better: 0 when the name starts
     * with it, 1 when it starts a later word ("last harbor" in "the last harbor 1980"), 2 when each
     * of its words starts a word somewhere ("harbor last"). Null when it doesn't match.
     */
    fun rank(name: String, query: String): Int? {
        if (query.isEmpty()) return null
        if (name.startsWith(query)) return 0
        if (startsWord(name, query)) return 1
        return if (query.split(' ').all { startsWord(name, it) }) 2 else null
    }

    private fun startsWord(name: String, term: String): Boolean {
        var at = name.indexOf(term)
        while (at >= 0) {
            if (at == 0 || name[at - 1] == ' ') return true
            at = name.indexOf(term, at + 1)
        }
        return false
    }
}

/** A program on a channel, as Search finds it. */
data class Airing(val channel: Channel, val program: Program)

/** What a search found, best matches first. */
data class Found(val channels: List<Channel>, val programs: List<Airing>, val movies: List<Movie>, val series: List<Series>) {
    val isEmpty get() = channels.isEmpty() && programs.isEmpty() && movies.isEmpty() && series.isEmpty()
}

/**
 * Everything Search looks through: the guide's channels and programs and the library's movies and
 * series, with their names normalized once. Built off the main thread; a search over some 27,000
 * names then takes a few milliseconds.
 */
class SearchIndex(lineup: Lineup?, catalog: Catalog?) {

    private class Named<T>(val name: String, val item: T)

    private val channels = lineup?.channels.orEmpty().map { Named(Search.normalize(it.name), it) }
    private val programs: List<Named<Airing>> = lineup?.let { l ->
        // A title repeats all day; normalize each once.
        val titles = HashMap<String, String>()
        l.channels.flatMap { channel ->
            l.programs[channel.epgId].orEmpty().map { Named(titles.getOrPut(it.title) { Search.normalize(it.title) }, Airing(channel, it)) }
        }
    }.orEmpty()
    private val movies = catalog?.movies?.all.orEmpty().map { Named(Search.normalize(it.name), it) }
    private val series = catalog?.series?.all.orEmpty().map { Named(Search.normalize(it.name), it) }

    /** What matches [query] at [now]: programs that haven't ended, each title once per channel, on now first. */
    fun find(query: String, now: Long): Found {
        val q = Search.normalize(query)
        if (q.isEmpty()) return Found(emptyList(), emptyList(), emptyList(), emptyList())
        val number = q.toIntOrNull()
        val channels = channels.mapNotNull { named ->
            val digits = named.item.number.toString()
            // A number finds that channel first, then the ones it starts (4, then 40 to 49).
            val rank = when {
                number != null && named.item.number == number -> -2
                number != null && digits.startsWith(q) -> -1
                else -> Search.rank(named.name, q)
            }
            rank?.let { it to named.item }
        }.sortedWith(compareBy({ it.first }, { it.second.number })).map { it.second }.take(CHANNELS)
        val programs = programs.asSequence()
            .filter { it.item.program.end > now }
            .mapNotNull { named -> Search.rank(named.name, q)?.let { Triple(it, named.name, named.item) } }
            .sortedWith(compareBy({ it.first }, { it.third.program.start }, { it.third.channel.number }))
            .distinctBy { (_, name, airing) -> name to airing.channel.streamId }
            .map { it.third }
            .take(PROGRAMS)
            .toList()
        return Found(channels, programs, ranked(movies, q, MOVIES), ranked(series, q, SERIES))
    }

    private fun <T> ranked(named: List<Named<T>>, query: String, limit: Int): List<T> =
        named.mapNotNull { n -> Search.rank(n.name, query)?.let { Triple(it, n.name, n.item) } }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .take(limit)
            .map { it.third }

    private companion object {
        const val CHANNELS = 30
        const val PROGRAMS = 40
        const val MOVIES = 60
        const val SERIES = 60
    }
}
