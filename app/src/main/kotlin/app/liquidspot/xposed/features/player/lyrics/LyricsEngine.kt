package app.liquidspot.xposed.features.player.lyrics

import app.liquidspot.BuildConfig
import app.liquidspot.core.LyricsSource
import app.liquidspot.xposed.ModulePrefs
import app.liquidspot.xposed.features.player.lyrics.providers.LyricsProviders
import app.liquidspot.xposed.util.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class LyricsQuery(val trackId: String?, val title: String, val artist: String, val album: String, val durationMs: Long) {
    val key: String get() = trackId ?: "$title|$artist|$album|${durationMs / 1000}"
}

data class LyricsConfig(val order: List<LyricsSource>, val enabled: Set<LyricsSource>, val paxSenixKey: String) {
    val signature: String get() = order.filter { it in enabled }.joinToString(",") + "|" + paxSenixKey.isNotBlank()

    companion object {
        fun from(prefs: ModulePrefs): LyricsConfig {
            val order = LyricsSource.parseOrder(prefs.getString(LyricsSource.ORDER_KEY, null))
            val enabled = LyricsSource.entries.filter { prefs.getBoolean(it.prefKey, it.defaultEnabled) }.toSet()
            return LyricsConfig(order, enabled, prefs.getString(LyricsSource.PAXSENIX_KEY, null).orEmpty().trim())
        }
    }
}

data class LyricsBundle(val options: List<Lyrics>, val complete: Boolean, val settled: Lyrics? = null) {
    val preferred: Lyrics? get() = options.firstOrNull { it.synced } ?: options.firstOrNull()
    val best: Lyrics? get() = settled ?: if (complete) preferred else null
}

object LyricsEngine {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "LiquidSpot-LyricsSource").apply { isDaemon = true } }

    fun fetch(query: LyricsQuery, config: LyricsConfig, onUpdate: (LyricsBundle) -> Unit) {
        val active = config.order.filter { it in config.enabled && (!it.requiresKey || config.paxSenixKey.isNotBlank()) }
        val completion = ExecutorCompletionService<Pair<Int, Lyrics?>>(pool)
        val futures = active.mapIndexed { i, source ->
            completion.submit(Callable { i to runCatching { fetchOne(source, query, config) }.getOrNull() })
        }
        val results = arrayOfNulls<Lyrics>(active.size)
        val done = BooleanArray(active.size)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_S)
        repeat(active.size) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return@repeat
            val future = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: return@repeat
            val (index, lyrics) = runCatching { future.get() }.getOrNull() ?: return@repeat
            done[index] = true
            results[index] = lyrics
            onUpdate(LyricsBundle(results.filterNotNull(), complete = false, settled = settled(results, done)))
        }
        futures.forEach { it.cancel(true) }
        onUpdate(LyricsBundle(results.filterNotNull(), complete = true, settled = settled(results, done)))
    }

    private fun settled(results: Array<Lyrics?>, done: BooleanArray): Lyrics? {
        for (i in results.indices) {
            if (!done[i]) return null
            val lyrics = results[i] ?: continue
            if (lyrics.synced) return lyrics
        }
        return null
    }

    private fun fetchOne(source: LyricsSource, query: LyricsQuery, config: LyricsConfig): Lyrics? {
        if (source == LyricsSource.SPOTIFY) return SpotifyLyrics.fetch(query)
        val provider = runCatching { LyricsProviders.Provider.valueOf(source.name) }.getOrNull() ?: return null
        val providerConfig = object : LyricsProviders.Config {
            override fun order(): List<LyricsProviders.Provider> = listOf(provider)
            override fun isEnabled(p: LyricsProviders.Provider) = true
            override fun paxSenixKey(): String = config.paxSenixKey
        }
        val result = runCatching {
            LyricsProviders.fetch(providerConfig, query.title, query.artist, query.album, query.durationMs, false, false)
        }.onFailure { Logger.w("Letras: falha em ${source.label}", it) }.getOrNull() ?: return null
        return toLyrics(result)?.copy(source = source.label)
    }

    private const val TIMEOUT_S = 20L

    private fun toLyrics(result: LyricsProviders.Result): Lyrics? {
        val lines = result.lines.filter { it.text != null && !isMarkup(it.text) }
        if (lines.isEmpty()) return null
        val synced = !result.isUntimed() && lines.any { it.start > 0 || it.end > 0 }
        val label = runCatching { LyricsSource.valueOf(result.source.name).label }.getOrDefault(result.source.label)
        val out = lines.flatMap { line -> if (synced) splitTimed(line) else splitPlain(line.text) }
            .filter { it.text.isNotEmpty() }
            .sortedBy { it.timeMs }
        if (out.isEmpty()) return null
        return Lyrics(lines = out, synced = synced, source = label)
    }

    private fun isMarkup(text: String): Boolean {
        val t = text.trim()
        return (t.startsWith("<") && t.endsWith(">")) || t == "\"/>"
    }

    private fun clean(text: String) = text.replace(WHITESPACE, " ").trim()

    private fun splitPlain(text: String): List<LyricLine> =
        text.split('\n').map { LyricLine(-1L, clean(it)) }

    private fun splitTimed(line: LyricsProviders.TimedLine): List<LyricLine> = withExtras(line, splitText(line))

    private fun withExtras(line: LyricsProviders.TimedLine, parts: List<LyricLine>): List<LyricLine> {
        if (parts.isEmpty()) return parts
        val endMs = if (line.end > line.start) (line.end * 1000).toLong() else -1L
        val bgText = line.bgText?.let(::clean)?.takeIf { it.isNotEmpty() }
        val bgWords = if (bgText != null) attachWords(bgText, line.bgWords.orEmpty()) else emptyList()
        return parts.mapIndexed { i, part ->
            val last = i == parts.lastIndex
            part.copy(
                endMs = if (last) endMs else parts[i + 1].timeMs,
                agent = line.agent,
                background = if (last) bgText else null,
                backgroundWords = if (last) bgWords else emptyList(),
            )
        }
    }

    private fun splitText(line: LyricsProviders.TimedLine): List<LyricLine> {
        val startMs = (line.start * 1000).toLong()
        val words = line.words.orEmpty()
        val text = clean(line.text)
        if (words.size < 2 || (text.length <= MAX_LINE_CHARS && '\n' !in line.text)) {
            val parts = line.text.split('\n').map(::clean).filter { it.isNotEmpty() }
            if (parts.size <= 1) return listOf(LyricLine(startMs, text, attachWords(text, words)))
            val span = ((line.end - line.start) * 1000).toLong().coerceAtLeast(0)
            return parts.mapIndexed { i, part -> LyricLine(startMs + span * i / parts.size, part) }
        }
        val out = ArrayList<LyricLine>()
        val chunk = StringBuilder()
        val chunkWords = ArrayList<LyricsProviders.TimedWord>()
        var chunkStart = words.first().start
        for ((i, word) in words.withIndex()) {
            if (chunk.isEmpty()) chunkStart = word.start
            chunkWords.add(word)
            chunk.append(word.text)
            if (!word.partOfWord) chunk.append(' ')
            val next = words.getOrNull(i + 1)
            val gap = if (next == null) 0.0 else next.start - word.end
            val breakHere = next == null || chunk.length >= MAX_LINE_CHARS ||
                (chunk.length >= MIN_SPLIT_CHARS && gap >= SPLIT_GAP_S) || gap >= LONG_GAP_S
            if (breakHere) {
                val chunkText = clean(chunk.toString())
                out.add(LyricLine((chunkStart * 1000).toLong(), chunkText, attachWords(chunkText, chunkWords)))
                chunk.setLength(0)
                chunkWords.clear()
            }
        }
        return out
    }

    private val WHITESPACE = Regex("\\s+")
    private fun attachWords(text: String, words: List<LyricsProviders.TimedWord>): List<LyricWord> {
        val out = ArrayList<LyricWord>(words.size)
        var cursor = 0
        for (word in words) {
            val piece = word.text?.trim().orEmpty()
            if (piece.isEmpty()) continue
            val at = text.indexOf(piece, cursor)
            if (at < 0) return emptyList()
            val startMs = (word.start * 1000).toLong()
            out.add(LyricWord(startMs, (word.end * 1000).toLong().coerceAtLeast(startMs + 1), at, at + piece.length))
            cursor = at + piece.length
        }
        return out
    }

    private const val MAX_LINE_CHARS = 60
    private const val MIN_SPLIT_CHARS = 24
    private const val SPLIT_GAP_S = 0.35
    private const val LONG_GAP_S = 1.2
}

object SpotifyLyrics {
    private const val ENDPOINT = "https://spotifyplus-api.devon-shoutz.workers.dev/api/lyrics/"

    fun fetch(query: LyricsQuery): Lyrics? {
        val id = query.trackId ?: return null
        return runCatching {
            val conn = URL(ENDPOINT + id).openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "LiquidSpot/${BuildConfig.VERSION_NAME}")
            try {
                if (conn.responseCode != 200) return@runCatching null
                parse(conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
        }.onFailure { Logger.w("Letras: falha nas letras do Spotify", it) }.getOrNull()
    }

    fun parse(body: String): Lyrics? {
        val json = JSONObject(body)
        val lines = when (json.optString("Type")) {
            "Line" -> json.optJSONArray("Content").objects()
                .filter { it.optString("Type", "Vocal") == "Vocal" }
                .map {
                    val start = (it.optDouble("StartTime", 0.0) * 1000).toLong()
                    LyricLine(
                        timeMs = start,
                        text = it.optString("Text").trim(),
                        endMs = endOf(it, start),
                        opposite = it.optBoolean("OppositeAligned"),
                    )
                }
            "Syllable" -> json.optJSONArray("Content").objects()
                .filter { it.optString("Type", "Vocal") == "Vocal" && it.has("Lead") }
                .map { item ->
                    val lead = syllableLine(item.getJSONObject("Lead"))
                    val background = item.optJSONArray("Background").objects().firstOrNull()?.let(::syllableLine)
                    lead.copy(
                        opposite = item.optBoolean("OppositeAligned"),
                        background = background?.text?.takeIf { it.isNotBlank() },
                        backgroundWords = background?.words.orEmpty(),
                    )
                }
            "Static" -> json.optJSONArray("Lines").objects().map { LyricLine(-1L, it.optString("Text").trim()) }
            else -> emptyList()
        }
        if (lines.isEmpty() || lines.all { it.text.isEmpty() }) return null
        val synced = json.optString("Type") != "Static"
        return Lyrics(lines.sortedBy { it.timeMs }, synced = synced, source = LyricsSource.SPOTIFY.label)
    }

    private fun syllableLine(lead: JSONObject): LyricLine {
        val text = StringBuilder()
        val words = ArrayList<LyricWord>()
        for (syllable in lead.optJSONArray("Syllables").objects()) {
            val piece = syllable.optString("Text")
            val from = text.length
            text.append(piece)
            if (piece.isNotBlank()) {
                val startMs = (syllable.optDouble("StartTime", 0.0) * 1000).toLong()
                val endMs = (syllable.optDouble("EndTime", 0.0) * 1000).toLong().coerceAtLeast(startMs + 1)
                words.add(LyricWord(startMs, endMs, from, from + piece.length))
            }
            if (!syllable.optBoolean("IsPartOfWord")) text.append(' ')
        }
        val start = (lead.optDouble("StartTime", 0.0) * 1000).toLong()
        return LyricLine(start, text.toString().trimEnd(), words, endMs = endOf(lead, start))
    }

    private fun endOf(json: JSONObject, start: Long): Long {
        val end = (json.optDouble("EndTime", 0.0) * 1000).toLong()
        return if (end > start) end else -1L
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
