package tv.zapparr.backup

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A backup Zapparr can't read: not a backup, or from a newer Zapparr. */
class BackupException(message: String) : Exception(message)

/**
 * A backup: Zapparr's preference files as JSON, each value with its type, so restoring puts back
 * exactly what was saved. Free of Android (the JVM tests have org.json too), so it is unit-tested.
 */
object Backup {

    const val FORMAT = "zapparr-backup"
    const val VERSION = 1

    /**
     * What a backup holds: the settings, the Guide (favorite channels, your order, hidden channels
     * and groups, names, the channels watched lately), the favorite apps, the Library's view,
     * where each movie and episode was left, and the answer about other apps' shows.
     */
    val FILES = listOf("look", "guide", "favorites", "library_view", "progress", "watch_next")

    /** The server logins, held only when asked: a backup with them has the passwords in it. */
    val LOGINS = listOf("server", "library")

    fun write(files: Map<String, Map<String, Any?>>, created: Long, app: String): String {
        val prefs = JSONObject()
        for ((name, values) in files) {
            val file = JSONObject()
            for ((key, value) in values) typed(value)?.let { file.put(key, it) }
            prefs.put(name, file)
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("created", created)
            .put("app", app)
            .put("prefs", prefs)
            .toString(2)
    }

    /** The files [json] holds, those Zapparr keeps only ([FILES] and [LOGINS]). */
    fun read(json: String): Map<String, Map<String, Any?>> {
        val root = parse(json)
        val prefs = root.optJSONObject("prefs") ?: throw BackupException("no settings in it")
        return prefs.keys().asSequence().filter { it in FILES || it in LOGINS }.associateWith { name ->
            val file = prefs.optJSONObject(name) ?: JSONObject()
            file.keys().asSequence().mapNotNull { key -> file.optJSONObject(key)?.let(::untyped)?.let { key to it } }.toMap()
        }
    }

    /** When [json] was made (epoch milliseconds), or 0 if it doesn't say. */
    fun created(json: String): Long = parse(json).optLong("created")

    /** Whether [json] holds the server logins. */
    fun hasLogins(json: String): Boolean = read(json).keys.any { it in LOGINS }

    private fun parse(json: String): JSONObject {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw BackupException("not a Zapparr backup")
        }
        if (root.optString("format") != FORMAT) throw BackupException("not a Zapparr backup")
        if (root.optInt("version") > VERSION) throw BackupException("made by a newer Zapparr")
        return root
    }

    private fun typed(value: Any?): JSONObject? = when (value) {
        is String -> JSONObject().put("string", value)
        is Int -> JSONObject().put("int", value)
        is Long -> JSONObject().put("long", value)
        is Float -> JSONObject().put("float", value.toDouble())
        is Boolean -> JSONObject().put("boolean", value)
        is Set<*> -> JSONObject().put("strings", JSONArray(value.filterIsInstance<String>().sorted()))
        else -> null
    }

    private fun untyped(o: JSONObject): Any? = when {
        o.has("string") -> o.getString("string")
        o.has("int") -> o.getInt("int")
        o.has("long") -> o.getLong("long")
        o.has("float") -> o.getDouble("float").toFloat()
        o.has("boolean") -> o.getBoolean("boolean")
        o.has("strings") -> o.getJSONArray("strings").let { array -> (0 until array.length()).mapTo(HashSet()) { array.getString(it) } }
        else -> null
    }
}
