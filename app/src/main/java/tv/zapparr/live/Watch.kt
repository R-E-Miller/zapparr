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
    // "title" is read by VLC, MX Player and Just Player for their on-screen name.
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(url.toUri(), "video/*").putExtra("title", title)
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, R.string.guide_no_player, Toast.LENGTH_LONG).show()
    }
}
