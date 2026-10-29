package tv.zapparr.live

import android.content.Context
import androidx.annotation.StringRes
import androidx.core.content.edit
import tv.zapparr.R
import java.net.URI

/** An Xtream Codes server, Dispatcharr or the user's library server, and the login for it. */
data class Server(val address: String, val username: String, val password: String) {

    companion object {
        /** Dispatcharr's port out of the box. */
        const val DEFAULT_PORT = 9191

        /**
         * Turns what was typed ("192.168.1.20", "nas:9191/", "https://tv.example.com") into a base
         * URL without a trailing slash, or null if it can't be one. Typed with neither a scheme nor a
         * port, the address gets http and [defaultPort].
         */
        fun normalizeAddress(typed: String, defaultPort: Int = DEFAULT_PORT): String? {
            val hasScheme = "://" in typed
            val text = typed.trim().trimEnd('/')
            if (text.isEmpty() || text.any { it.isWhitespace() }) return null
            val uri = try {
                URI(if (hasScheme) text else "http://$text")
            } catch (e: Exception) { // URISyntaxException
                return null
            }
            val scheme = uri.scheme?.lowercase()
            val host = uri.host
            if (scheme != "http" && scheme != "https" || host.isNullOrEmpty()) return null
            if (uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) return null
            val port = when {
                uri.port != -1 -> ":${uri.port}"
                hasScheme -> ""
                else -> ":$defaultPort"
            }
            return "$scheme://$host$port${uri.rawPath.orEmpty()}"
        }
    }
}

/**
 * The two servers Zapparr talks to: Dispatcharr for live TV, and the user's library server
 * (vod-organizer, on port 8000) for movies and series. Each has its own saved login and its own
 * wording on the server screen.
 */
enum class ServerKind(
    val prefs: String,
    val defaultPort: Int,
    @param:StringRes val title: Int,
    @param:StringRes val help: Int,
    @param:StringRes val addressLabel: Int,
    @param:StringRes val passwordLabel: Int,
) {
    LIVE("server", Server.DEFAULT_PORT, R.string.server_title, R.string.server_help, R.string.server_address, R.string.server_password),
    LIBRARY("library", 8000, R.string.library_server_title, R.string.library_server_help, R.string.library_server_address, R.string.library_server_password),
}

/** Keeps one server of each [ServerKind]. The box is the user's own, so the password sits in private app storage. */
class ServerStore(context: Context, kind: ServerKind) {
    private val prefs = context.getSharedPreferences(kind.prefs, Context.MODE_PRIVATE)

    fun load(): Server? {
        val address = prefs.getString(ADDRESS, null) ?: return null
        return Server(address, prefs.getString(USERNAME, "")!!, prefs.getString(PASSWORD, "")!!)
    }

    fun save(server: Server) = prefs.edit {
        putString(ADDRESS, server.address)
        putString(USERNAME, server.username)
        putString(PASSWORD, server.password)
    }

    fun clear() = prefs.edit { clear() }

    private companion object {
        const val ADDRESS = "address"
        const val USERNAME = "username"
        const val PASSWORD = "password"
    }
}
