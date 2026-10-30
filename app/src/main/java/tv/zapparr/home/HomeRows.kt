package tv.zapparr.home

import android.content.Context
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.zapparr.R
import tv.zapparr.apps.AppsDeck
import tv.zapparr.apps.Card
import tv.zapparr.apps.channelCard
import tv.zapparr.library.DetailsActivity
import tv.zapparr.library.ProgressStore
import tv.zapparr.live.GuideDeck
import tv.zapparr.live.GuideStore
import tv.zapparr.live.OnNow
import tv.zapparr.look.Look
import tv.zapparr.look.Option

/**
 * What the Apps deck's Continue Watching and On now rows show, and what OK on a card does. OK on a
 * Library title plays it from where it was left; on another app's, opens it there; on a channel,
 * watches it. Holding OK on a Library title offers to remove it.
 */
internal class HomeRows(
    private val activity: ComponentActivity,
    private val apps: AppsDeck,
    private val guide: GuideDeck,
    private val guideStore: GuideStore,
    private val askPermission: () -> Unit,
) {
    private val progress = ProgressStore(activity)
    private val prefs = activity.getSharedPreferences("watch_next", Context.MODE_PRIVATE)
    private var job: Job? = null

    /** Whether the user said no to sharing other apps' Watch Next with Zapparr. */
    var declined: Boolean
        get() = prefs.getBoolean(DECLINED, false)
        set(value) = prefs.edit { putBoolean(DECLINED, value) }

    fun showContinueWatching() {
        job?.cancel()
        if (!Look.current.on(Option.CONTINUE_WATCHING)) return apps.showContinueWatching(emptyList())
        job = activity.lifecycleScope.launch {
            val others = withContext(Dispatchers.IO) { WatchNext.read(activity) }
            val cards = ContinueWatching.cards(progress.all(), others, activity.packageName).map(::card)
            // Until Zapparr may read other apps' Watch Next, the row ends with a card that asks.
            val ask = if (WatchNext.allowed(activity) || declined) null else askCard()
            apps.showContinueWatching(cards + listOfNotNull(ask))
        }
    }

    fun showOnNow() {
        val lineup = guide.lineup
        if (lineup == null || !Look.current.on(Option.ON_NOW)) {
            apps.showOnNow(emptyList())
            return
        }
        val now = System.currentTimeMillis()
        val channels = OnNow.channels(guideStore.recent(), guideStore.favorites(), lineup.channels)
        apps.showOnNow(channels.map { activity.channelCard(it, lineup.programs[it.epgId], now) { guide.watch(it) } })
    }

    private fun card(item: Unfinished): Card = when (item) {
        is Unfinished.InLibrary -> {
            val (key, saved) = item
            val played = saved.played
            val (position, duration) = saved.progress.position to saved.progress.duration
            Card(
                key = key,
                title = played.name,
                subtitle = listOfNotNull(
                    played.series?.let { activity.getString(R.string.continue_episode, played.season, played.episode) },
                    ContinueWatching.minutesLeft(position, duration)?.let { minutesLeft(it) },
                ).joinToString(SEPARATOR),
                image = played.art ?: played.poster,
                progress = ContinueWatching.fraction(position, duration),
                open = { DetailsActivity.resume(activity, key, saved) },
                menu = { anchor -> showRemove(anchor, key) },
            )
        }
        is Unfinished.InApp -> {
            val row = item.row
            Card(
                key = "app:${row.packageName}:${row.title}:${row.season}:${row.episode}",
                title = row.title,
                subtitle = listOfNotNull(
                    if (row.season != null && row.episode != null) activity.getString(R.string.continue_app_episode, row.season, row.episode) else null,
                    ContinueWatching.minutesLeft(row.position, row.duration)?.let { minutesLeft(it) },
                    row.appName,
                ).joinToString(SEPARATOR),
                image = row.image,
                progress = ContinueWatching.fraction(row.position, row.duration),
                open = { openApp(row) },
            )
        }
    }

    private fun askCard() = Card(
        key = "ask",
        title = activity.getString(R.string.continue_other_apps),
        subtitle = activity.getString(R.string.continue_other_apps_hint),
        image = null,
        progress = null,
        open = askPermission,
        placeholder = activity.getString(R.string.continue_allow),
    )

    private fun openApp(row: WatchNextRow) {
        val intent = WatchNext.intent(activity, row)
        try {
            if (intent != null) return activity.startActivity(intent)
        } catch (e: RuntimeException) { // ActivityNotFoundException, or SecurityException from a guarded activity
            // Falls through to the message.
        }
        Toast.makeText(activity, activity.getString(R.string.cannot_open, row.appName), Toast.LENGTH_SHORT).show()
    }

    private fun showRemove(anchor: View, key: String) {
        val popup = PopupMenu(activity, anchor)
        popup.menu.add(R.string.menu_remove_continue)
        popup.setOnMenuItemClickListener {
            progress.remove(key)
            showContinueWatching()
            true
        }
        popup.show()
    }

    private fun minutesLeft(minutes: Int) = activity.resources.getQuantityString(R.plurals.guide_minutes_left, minutes, minutes)

    private companion object {
        const val DECLINED = "declined"
        const val SEPARATOR = "  ·  "
    }
}
