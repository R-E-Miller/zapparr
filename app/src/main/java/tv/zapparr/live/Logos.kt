package tv.zapparr.live

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Channel logos, fetched on two background threads, scaled down to [height] pixels and kept in a
 * small memory cache. [onLoaded] runs on the main thread each time one arrives.
 */
class Logos(private val height: Int, private val onLoaded: () -> Unit) {

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    // Main thread only.
    private val pending = HashSet<String>()
    private val failed = HashSet<String>()
    private val main = Handler(Looper.getMainLooper())

    /** The logo at [url] if it has loaded; otherwise starts loading it and returns null. Main thread only. */
    fun get(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        if (url in failed || !pending.add(url)) return null
        loader.execute {
            val bitmap = try {
                fetch(url)
            } catch (e: Exception) { // IOException, or a malformed URL or image
                null
            }
            main.post {
                pending.remove(url)
                if (bitmap == null) {
                    failed += url
                } else {
                    cache.put(url, bitmap)
                    onLoaded()
                }
            }
        }
        return null
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
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        if (decoded.height <= height) return decoded
        val width = maxOf(1, decoded.width * height / decoded.height)
        return decoded.scale(width, height).also { if (it !== decoded) decoded.recycle() }
    }

    // A logo is small; anything bigger than MAX_BYTES is skipped rather than held in memory.
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

    private companion object {
        const val CACHE_BYTES = 4 * 1024 * 1024
        const val MAX_BYTES = 1024 * 1024
        const val CONNECT_TIMEOUT = 5_000
        const val READ_TIMEOUT = 10_000
        val loader = Executors.newFixedThreadPool(2)
    }
}
