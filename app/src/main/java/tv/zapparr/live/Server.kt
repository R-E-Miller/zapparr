package tv.zapparr.live

import android.content.Context
import androidx.core.content.edit
import java.net.URI

/** A Dispatcharr server (or any Xtream Codes server) and the XC login it gives the user. */
data class Server(val address: String, val username: String, val password: String) {

    companion object {
        /** Dispatcharr's port out of the box. */
        const val DEFAULT_PORT = 9191

        /**
         * Turns what was typed ("192.168.1.20", "nas:9191/", "https://tv.example.com") into a base
         * URL without a trailing slash, or null if it can't be one. Typed with neither a scheme nor a
         * port, the address gets http and Dispatcharr's port.
         */
        fun normalizeAddress(typed: String): String? {
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
                else -> ":$DEFAULT_PORT"
            }
            return "$scheme://$host$port${uri.rawPath.orEmpty()}"
        }
    }
}

/** Keeps the one server Zapparr talks to. The box is the user's own, so the password sits in private app storage. */
class ServerStore(context: Context) {
    private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)

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
