package tv.zapparr.live

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import tv.zapparr.R

/**
 * Hands a stream to a video player. Android asks which one the first time, with an "Always"
 * choice. Phase 3 replaces this with Zapparr's own player.
 */
fun Context.watch(url: String, title: String) {
    try {
        startActivity(playerIntent(url, title))
    } catch (e: ActivityNotFoundException) {
        noPlayer()
    }
}

/** Hands the live [channel] to a video player, and remembers it in [store] for On now. */
fun Context.watch(server: Server, channel: Channel, store: GuideStore) {
    store.saveRecent(OnNow.watched(store.recent(), channel.streamId))
    watch(Xc.liveUrl(server, channel), channel.name)
}

/**
 * What [watch] sends. "title" is read by VLC, MX Player and Just Player for their on-screen name.
 * With [from] (milliseconds), it also asks the player to start there and to say where it stopped:
 * VLC does that by itself; MX Player and Just Player need "return_result".
 */
fun playerIntent(url: String, title: String, from: Long? = null): Intent {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(url.toUri(), "video/*").putExtra("title", title)
    if (from != null) {
        intent.putExtra("return_result", true)
        // An Int, as MX Player reads it; VLC takes an Int or a Long. 0 also tells VLC not to resume on its own.
        if (from > 0) intent.putExtra("position", from.toInt()) else intent.putExtra("from_start", true)
    }
    return intent
}

fun Context.noPlayer() = Toast.makeText(this, R.string.guide_no_player, Toast.LENGTH_LONG).show()
