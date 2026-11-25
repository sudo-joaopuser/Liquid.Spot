package app.liquidspot.xposed.features.player

import android.os.Handler
import android.os.Looper
import app.liquidspot.BuildConfig
import app.liquidspot.xposed.util.Logger
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object TrackLinks {
    data class Links(val albumId: String?, val artistIds: List<String>)

    private const val PAGE = "https://open.spotify.com/track/"
    private const val MAX_HEAD_CHARS = 200_000
    private val ALBUM = Regex("""<meta name="music:album" content="https://open\.spotify\.com/album/([A-Za-z0-9]{22})""")
    private val ARTIST = Regex("""<meta name="music:musician" content="https://open\.spotify\.com/artist/([A-Za-z0-9]{22})""")

    private val cache = object : LinkedHashMap<String, Links>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Links>?) = size > 30
    }
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "LiquidSpot-TrackLinks").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    fun load(trackId: String, callback: (Links?) -> Unit) {
        synchronized(cache) { cache[trackId] }?.let {
            callback(it)
            return
        }
        executor.execute {
            val links = runCatching { fetch(trackId) }
                .onFailure { Logger.w("Links da música: falha ao buscar $trackId", it) }
                .getOrNull()
            if (links != null) synchronized(cache) { cache[trackId] = links }
            main.post { callback(links) }
        }
    }

    private fun fetch(trackId: String): Links? {
        val conn = URL(PAGE + trackId).openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("User-Agent", "LiquidSpot/${BuildConfig.VERSION_NAME}")
        try {
            if (conn.responseCode != 200) return null
            val head = StringBuilder()
            conn.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(8_192)
                while (head.length < MAX_HEAD_CHARS) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    head.append(buffer, 0, read)
                    if (head.indexOf("</head>") >= 0) break
                }
            }
            val album = ALBUM.find(head)?.groupValues?.get(1)
            val artists = ARTIST.findAll(head).map { it.groupValues[1] }.toList()
            return if (album == null && artists.isEmpty()) null else Links(album, artists)
        } finally {
            conn.disconnect()
        }
    }
}
