package app.liquidspot.xposed.features.player.lyrics

import android.os.Handler
import android.os.Looper
import app.liquidspot.xposed.util.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

class LyricsRepository(cacheRoot: File, private val config: () -> LyricsConfig) {
    sealed class Result {
        data class Found(val bundle: LyricsBundle) : Result()
        data object NotFound : Result()
        data class Failed(val error: Throwable) : Result()
    }

    private val dir = File(cacheRoot, "liquidspot/lyrics")
    private val memory = object : LinkedHashMap<String, Result>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result>?) = size > 40
    }
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "LiquidSpot-Lyrics").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    fun load(query: LyricsQuery, callback: (Result) -> Unit) {
        val settings = config()
        val key = CACHE_VERSION + "|" + query.key + "|" + settings.signature
        synchronized(memory) { memory[key] }?.let {
            callback(it)
            return
        }
        executor.execute {
            readDisk(key)?.let { cached ->
                synchronized(memory) { memory[key] = cached }
                main.post { callback(cached) }
                return@execute
            }
            try {
                LyricsEngine.fetch(query, settings) { bundle ->
                    val result = if (bundle.options.isEmpty()) Result.NotFound else Result.Found(bundle)
                    if (bundle.complete) {
                        synchronized(memory) { memory[key] = result }
                        writeDisk(key, result)
                    }
                    if (bundle.complete || result is Result.Found) main.post { callback(result) }
                }
            } catch (t: Throwable) {
                Logger.w("Letras: falha ao buscar '${query.title}'", t)
                main.post { callback(Result.Failed(t)) }
            }
        }
    }

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    private fun readDisk(key: String): Result? = runCatching {
        val file = fileFor(key)
        if (!file.isFile) return null
        val json = JSONObject(file.readText())
        if (json.optBoolean("notFound")) {
            if (System.currentTimeMillis() - json.optLong("time") > NOT_FOUND_TTL_MS) return null
            return Result.NotFound
        }
        val options = json.getJSONArray("options")
        val list = (0 until options.length()).map { i -> fromJson(options.getJSONObject(i)) }
        Result.Found(LyricsBundle(list, complete = true))
    }.getOrNull()

    private fun writeDisk(key: String, result: Result) {
        runCatching {
            dir.mkdirs()
            val json = when (result) {
                is Result.Found -> JSONObject().put("options", JSONArray().apply { result.bundle.options.forEach { put(toJson(it)) } })
                Result.NotFound -> JSONObject().put("notFound", true).put("time", System.currentTimeMillis())
                is Result.Failed -> return
            }
            fileFor(key).writeText(json.toString())
        }.onFailure { Logger.w("Letras: não foi possível gravar o cache", it) }
    }

    private fun toJson(lyrics: Lyrics) = JSONObject()
        .put("synced", lyrics.synced)
        .put("instrumental", lyrics.instrumental)
        .put("source", lyrics.source)
        .put("lines", JSONArray().apply { lyrics.lines.forEach { put(lineJson(it)) } })

    private fun lineJson(line: LyricLine): JSONObject {
        val json = JSONObject().put("t", line.timeMs).put("x", line.text)
        if (line.words.isNotEmpty()) json.put("w", wordsJson(line.words))
        if (line.endMs >= 0) json.put("e", line.endMs)
        line.agent?.let { json.put("a", it) }
        line.opposite?.let { json.put("o", it) }
        line.background?.let { json.put("b", it) }
        if (line.backgroundWords.isNotEmpty()) json.put("bw", wordsJson(line.backgroundWords))
        return json
    }

    private fun wordsJson(words: List<LyricWord>) =
        JSONArray().apply { words.forEach { put(JSONArray().put(it.startMs).put(it.endMs).put(it.from).put(it.to)) } }

    private fun wordsFromJson(array: JSONArray?): List<LyricWord> = array?.let {
        (0 until it.length()).mapNotNull { i ->
            it.optJSONArray(i)?.let { w -> LyricWord(w.getLong(0), w.getLong(1), w.getInt(2), w.getInt(3)) }
        }
    }.orEmpty()

    private fun lineFromJson(json: JSONObject): LyricLine = LyricLine(
        timeMs = json.getLong("t"),
        text = json.getString("x"),
        words = wordsFromJson(json.optJSONArray("w")),
        endMs = json.optLong("e", -1L),
        agent = json.optString("a").ifEmpty { null },
        opposite = if (json.has("o")) json.optBoolean("o") else null,
        background = json.optString("b").ifEmpty { null },
        backgroundWords = wordsFromJson(json.optJSONArray("bw")),
    )

    private fun fromJson(json: JSONObject): Lyrics {
        val lines = json.getJSONArray("lines")
        return Lyrics(
            lines = (0 until lines.length()).map { i -> lineFromJson(lines.getJSONObject(i)) },
            synced = json.getBoolean("synced"),
            instrumental = json.optBoolean("instrumental"),
            source = json.optString("source"),
        )
    }

    private companion object {
        const val CACHE_VERSION = "4"
        const val NOT_FOUND_TTL_MS = 6L * 60 * 60 * 1000
    }
}
