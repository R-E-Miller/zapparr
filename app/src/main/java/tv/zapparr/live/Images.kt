package tv.zapparr.live

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.WeakHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Pictures from the network: channel logos and posters. Fetched on two background threads, newest
 * request first, scaled down to [height] pixels and kept in a memory cache of [cacheBytes].
 * [opaque] pictures (posters) are decoded at 16 bits a pixel, half the memory. [onLoaded] runs on
 * the main thread each time one arrives.
 */
class Images(
    private val height: Int,
    cacheBytes: Int,
    private val opaque: Boolean = false,
    private val onLoaded: () -> Unit = {},
) {

    private val cache = object : LruCache<String, Bitmap>(cacheBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    // Main thread only.
    private val pending = HashSet<String>()
    private val failed = HashSet<String>()
    private val waiting = WeakHashMap<ImageView, String>()
    private val main = Handler(Looper.getMainLooper())

    /** The picture at [url] if it has loaded; otherwise starts loading it and returns null. Main thread only. */
    fun get(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        if (url !in failed && pending.add(url)) loader.execute(Fetch(url))
        return null
    }

    /**
     * Shows the picture at [url] in [view], now if it has loaded, or when it does. A reused view
     * (a tile scrolled back into a row) shows only the picture it was last asked for.
     */
    fun show(url: String?, view: ImageView) {
        val bitmap = url?.let(::get)
        view.setImageBitmap(bitmap)
        if (url != null && bitmap == null) waiting[view] = url else waiting.remove(view)
    }

    private fun arrived(url: String, bitmap: Bitmap?) {
        pending.remove(url)
        if (bitmap == null) {
            failed += url
            return
        }
        cache.put(url, bitmap)
        val ready = waiting.filterValues { it == url }.keys
        for (view in ready) {
            view.setImageBitmap(bitmap)
            waiting.remove(view)
        }
        onLoaded()
    }

    private inner class Fetch(val url: String) : Runnable {
        override fun run() {
            val bitmap = try {
                fetch(url)
            } catch (e: Exception) { // IOException, or a malformed URL or image
                null
            }
            main.post { arrived(url, bitmap) }
        }

        // Dropped from a full queue: it can be asked for again.
        fun drop() = main.post { pending.remove(url) }
    }

    private fun fetch(url: String): Bitmap? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
        }
        val bytes = try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.use(::readCapped) ?: return null
        } finally {
            connection.disconnect()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null // not an image Android can decode, like SVG
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= height) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            if (opaque) inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        if (decoded.height <= height) return decoded
        val width = maxOf(1, decoded.width * height / decoded.height)
        return decoded.scale(width, height).also { if (it !== decoded) decoded.recycle() }
    }

    // A logo or poster is small; anything bigger than MAX_BYTES is skipped rather than held in memory.
    private fun readCapped(stream: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) return out.toByteArray()
            out.write(buffer, 0, read)
            if (out.size() > MAX_BYTES) return null
        }
    }

    // Newest first, and only the newest QUEUE: scrolling fast through a long row asks for many
    // posters, and the ones on screen when it stops are the ones that matter.
    private class Lifo : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable): Boolean {
            while (size >= QUEUE) (pollLast() as? Images.Fetch)?.drop()
            return offerFirst(e)
        }
    }

    private companion object {
        const val MAX_BYTES = 1024 * 1024
        const val CONNECT_TIMEOUT = 5_000
        const val READ_TIMEOUT = 10_000
        const val QUEUE = 48
        val loader = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, Lifo())
    }
}
