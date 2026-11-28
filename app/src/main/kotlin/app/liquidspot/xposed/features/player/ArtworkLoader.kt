package app.liquidspot.xposed.features.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import app.liquidspot.xposed.util.Logger
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

object ArtworkLoader {
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val executor = Executors.newFixedThreadPool(2) { r -> Thread(r, "LiquidSpot-Art").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val inFlight = HashSet<String>()

    fun urlFor(uri: String?): String? = when {
        uri == null -> null
        uri.startsWith("https://") -> uri
        uri.startsWith("http://i.scdn.co/") -> uri.replaceFirst("http://", "https://")
        uri.startsWith("spotify:image:") -> IMAGE_BASE + uri.removePrefix("spotify:image:")
        uri.startsWith("content://") -> imageIdIn(uri)?.let { IMAGE_BASE + it }
        else -> null
    }

    private fun imageIdIn(uri: String): String? {
        val path = Uri.decode(uri.substringBefore('?'))
        val id = path.substringAfter("spotify:image:", "")
        return id.takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) }
    }

    private fun largest(url: String): String? {
        if (!url.startsWith(IMAGE_BASE)) return null
        val id = url.removePrefix(IMAGE_BASE)
        val size = ALBUM_SIZES.firstOrNull { id.startsWith(it) } ?: return null
        return IMAGE_BASE + ALBUM_LARGEST + id.removePrefix(size)
    }

    fun cached(uri: String?): Bitmap? = urlFor(uri)?.let { synchronized(cache) { cache.get(it) } }

    fun load(uri: String?, callback: (Bitmap) -> Unit) {
        val url = urlFor(uri) ?: return
        synchronized(cache) { cache.get(url) }?.let {
            callback(it)
            return
        }
        synchronized(inFlight) { if (!inFlight.add(url)) return }
        executor.execute {
            val bitmap = largest(url)?.let { download(it, quiet = true) } ?: download(url, quiet = false)
            synchronized(inFlight) { inFlight.remove(url) }
            if (bitmap != null) {
                synchronized(cache) { cache.put(url, bitmap) }
                main.post { callback(bitmap) }
            }
        }
    }

    private fun download(url: String, quiet: Boolean): Bitmap? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 10_000
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
            conn.inputStream.use { BitmapFactory.decodeStream(it) }?.let(::fit)
        } finally {
            conn.disconnect()
        }
    }.onFailure { if (!quiet) Logger.w("Capa: falha ao baixar $url", it) }.getOrNull()

    private fun fit(bitmap: Bitmap): Bitmap {
        val side = max(bitmap.width, bitmap.height)
        if (side <= MAX_SIDE) return bitmap
        val scale = MAX_SIDE.toFloat() / side
        val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt(), true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private const val IMAGE_BASE = "https://i.scdn.co/image/"
    private const val ALBUM_LARGEST = "ab67616d000082c1"
    private val ALBUM_SIZES = listOf("ab67616d0000b273", "ab67616d00001e02", "ab67616d00004851")
    private const val MAX_SIDE = 1440
    private const val CACHE_BYTES = 48 * 1024 * 1024
}
