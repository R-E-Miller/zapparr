package tv.zapparr.live

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

/** A channel group, as managed in Dispatcharr ("category" in Xtream Codes). */
data class Group(val id: String, val name: String)

/**
 * A live channel. [epgId] is its id in the XMLTV guide; Dispatcharr uses the channel number.
 * [catchupDays] is 0 without catch-up.
 */
data class Channel(
    val streamId: Int,
    val number: Int,
    val name: String,
    val logo: String?,
    val epgId: String,
    val groupId: String?,
    val catchupDays: Int,
)

/** Why a server couldn't be used, worded for the user by the screen that shows it. */
enum class Problem { UNREACHABLE, NOT_XC, WRONG_LOGIN, ACCOUNT_INACTIVE }

class XcException(val problem: Problem, cause: Throwable? = null) : Exception(problem.name, cause)

/**
 * The Xtream Codes API as Dispatcharr serves it: URLs and replies. Network-free, so it can be
 * unit-tested; [XcClient] does the fetching.
 */
object Xc {

    fun apiUrl(server: Server, action: String? = null): String {
        val login = "username=${query(server.username)}&password=${query(server.password)}"
        return "${server.address}/player_api.php?$login" + if (action == null) "" else "&action=$action"
    }

    fun xmltvUrl(server: Server) =
        "${server.address}/xmltv.php?username=${query(server.username)}&password=${query(server.password)}"

    fun liveUrl(server: Server, channel: Channel) =
        "${server.address}/live/${path(server.username)}/${path(server.password)}/${channel.streamId}"

    /** Throws unless the reply to a bare player_api.php call says the login works. */
    fun checkLogin(json: String) {
        val user = parseObject(json).optJSONObject("user_info") ?: throw XcException(Problem.NOT_XC)
        if (user.int("auth") != 1) throw XcException(Problem.WRONG_LOGIN)
        val status = user.text("status")
        if (status != null && !status.equals("Active", ignoreCase = true)) throw XcException(Problem.ACCOUNT_INACTIVE)
    }

    fun parseGroups(json: String): List<Group> = parseArray(json).objects().mapNotNull { group ->
        val id = group.text("category_id") ?: return@mapNotNull null
        Group(id, group.text("category_name") ?: id)
    }

    /** The channels in number order. Relative logo URLs are resolved against [address]. */
    fun parseChannels(json: String, address: String): List<Channel> = parseArray(json).objects().mapNotNull { stream ->
        val id = stream.int("stream_id") ?: return@mapNotNull null
        val number = stream.int("num") ?: id
        Channel(
            streamId = id,
            number = number,
            name = stream.text("name") ?: number.toString(),
            logo = stream.text("stream_icon")?.let { resolve(address, it) },
            epgId = stream.text("epg_channel_id") ?: number.toString(),
            groupId = stream.text("category_id"),
            catchupDays = if (stream.int("tv_archive") == 1) stream.int("tv_archive_duration") ?: 0 else 0,
        )
    }.sortedWith(compareBy({ it.number }, { it.name }))

    private fun parseObject(json: String) = try {
        JSONObject(json)
    } catch (e: JSONException) {
        throw XcException(Problem.NOT_XC, e)
    }

    private fun parseArray(json: String) = try {
        JSONArray(json)
    } catch (e: JSONException) {
        throw XcException(Problem.NOT_XC, e)
    }

    private fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }

    // XC servers disagree on types: Dispatcharr sends numbers, others send them as strings.
    private fun JSONObject.int(key: String): Int? = when (val value = opt(key)) {
        is Number -> value.toInt()
        is String -> value.trim().toDoubleOrNull()?.toInt()
        else -> null
    }

    // optString would turn a JSON null into "null".
    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else opt(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    private fun resolve(address: String, url: String) = try {
        URI("$address/").resolve(url).toString()
    } catch (e: Exception) { // IllegalArgumentException from a malformed URL
        null
    }

    private fun query(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun path(value: String) = query(value).replace("+", "%20")
}
