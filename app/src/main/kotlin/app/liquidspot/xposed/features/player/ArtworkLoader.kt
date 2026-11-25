package app.liquidspot.xposed.features.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import app.liquidspot.xposed.util.Logger
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object ArtworkLoader {
    private val cache = LruCache<String, Bitmap>(6)
    private val executor = Executors.newFixedThreadPool(2) { r -> Thread(r, "LiquidSpot-Art").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val inFlight = HashSet<String>()

    fun urlFor(uri: String?): String? = when {
        uri == null -> null
        uri.startsWith("https://") -> uri
        uri.startsWith("http://i.scdn.co/") -> uri.replaceFirst("http://", "https://")
        uri.startsWith("spotify:image:") -> "https://i.scdn.co/image/" + uri.removePrefix("spotify:image:")
        else -> null
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
            val bitmap = runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout = 10_000
                try {
                    conn.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally {
                    conn.disconnect()
                }
            }.onFailure { Logger.w("Capa: falha ao baixar $url", it) }.getOrNull()
            synchronized(inFlight) { inFlight.remove(url) }
            if (bitmap != null) {
                synchronized(cache) { cache.put(url, bitmap) }
                main.post { callback(bitmap) }
            }
        }
    }
}
