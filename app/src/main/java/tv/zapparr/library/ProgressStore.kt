package tv.zapparr.library

import android.content.Context
import androidx.core.content.edit
import org.json.JSONException
import org.json.JSONObject

/**
 * Where each movie and episode was left, kept on the box: the newest [LIMIT]. Each entry also keeps
 * what Continue Watching needs to show and play it again ([Played]).
 */
class ProgressStore(context: Context) {

    private val prefs = context.getSharedPreferences("progress", Context.MODE_PRIVATE)

    fun get(key: String): Saved? = prefs.getString(key, null)?.let(::parse)

    /** Everything remembered, by key. */
    fun all(): Map<String, Saved> = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let(::parse)?.let { k to it } }.toMap()

    fun remove(key: String) = prefs.edit { remove(key) }

    fun save(key: String, saved: Saved) = prefs.edit {
        val played = saved.played
        putString(key, JSONObject().apply {
            put("position", saved.progress.position)
            put("duration", saved.progress.duration)
            put("watched", saved.progress.watched)
            put("at", saved.progress.at)
            put("title", played.name)
            put("poster", played.poster)
            put("art", played.art)
            put("extension", played.extension)
            put("series", played.series)
            put("season", played.season)
            put("episode", played.episode)
        }.toString())
        // Forget the oldest beyond LIMIT, never the one being saved (a removal in the same edit would win).
        val all = all().filterKeys { it != key }.map { (k, v) -> k to v.progress.at }
        if (all.size >= LIMIT) all.sortedBy { it.second }.take(all.size - LIMIT + 1).forEach { remove(it.first) }
    }

    private fun parse(json: String): Saved? = try {
        val o = JSONObject(json)
        Saved(
            Progress(o.optLong("position"), o.optLong("duration"), o.optBoolean("watched"), o.optLong("at")),
            Played(
                name = o.optString("title"),
                poster = o.text("poster"),
                art = o.text("art"),
                extension = o.optString("extension", "mp4"),
                series = if (o.isNull("series")) null else o.optInt("series"),
                season = o.optInt("season"),
                episode = o.optInt("episode"),
            ),
        )
    } catch (e: JSONException) {
        null
    }

    // optString would turn a JSON null into "null".
    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    companion object {
        const val LIMIT = 200

        fun movieKey(id: Int) = "movie:$id"

        fun episodeKey(id: Int) = "episode:$id"

        /** The movie or episode id in a key. */
        fun idOf(key: String): Int? = key.substringAfter(':').toIntOrNull()
    }
}
