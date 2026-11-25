package app.liquidspot.xposed.features.player.lyrics.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.liquidspot.core.AiFiles
import app.liquidspot.xposed.HookContext
import app.liquidspot.xposed.ModulePrefs
import app.liquidspot.xposed.features.player.lyrics.LyricWord
import app.liquidspot.xposed.features.player.lyrics.Lyrics
import app.liquidspot.xposed.media.NowPlayingStore
import app.liquidspot.xposed.util.Logger
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

object AiSync {
    enum class Problem { UNAVAILABLE, UNSUPPORTED, NO_AUDIO }

    private var xposed: XposedInterface? = null
    private var prefs: ModulePrefs? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "LiquidSpot-AiSync").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private var job: Future<*>? = null
    @Volatile
    private var generation = 0
    private var bridge: OrtBridge? = null
    private var bridgeTag: String? = null
    private var aligner: CtcAligner? = null

    fun install(ctx: HookContext) {
        xposed = ctx.xposed
        prefs = ctx.prefs
        AudioCapture.install(ctx)
        AudioCapture.enabled = installed
        Logger.i("IA: instalada=$installed")
        cacheDir = File(ctx.appInfo.dataDir, "cache/liquidspot/ai")
        executor.execute { clearIfRequested() }
        ctx.prefs.onChange(AiFiles.PREF_CACHE_CLEAR) { executor.execute { clearIfRequested() } }
        ctx.prefs.onChange(AiFiles.PREF_CACHE_LIMIT_MB) { executor.execute { trimCache() } }
        for (key in listOf(AiFiles.PREF_MODEL_SOURCE, AiFiles.PREF_RUNTIME)) {
            ctx.prefs.onChange(key) {
                AudioCapture.enabled = installed
                executor.execute { closeBridge() }
            }
        }
    }

    private var cacheDir: File? = null

    @Volatile
    private var heartbeat = 0L

    @Volatile
    private var inferenceMs = 0L

    fun heartbeat() {
        heartbeat = android.os.SystemClock.uptimeMillis()
    }

    private val awake: Boolean get() = android.os.SystemClock.uptimeMillis() - heartbeat < AWAKE_MS

    private val cacheEnabled: Boolean
        get() = prefs?.getBoolean(AiFiles.PREF_CACHE_ENABLED, AiFiles.CACHE_ENABLED_DEFAULT) ?: AiFiles.CACHE_ENABLED_DEFAULT

    private val cacheLimitBytes: Long
        get() = (prefs?.getInt(AiFiles.PREF_CACHE_LIMIT_MB, AiFiles.CACHE_LIMIT_DEFAULT_MB) ?: AiFiles.CACHE_LIMIT_DEFAULT_MB) * 1_048_576L

    private fun clearIfRequested() {
        val dir = cacheDir ?: return
        val token = prefs?.getLong(AiFiles.PREF_CACHE_CLEAR, 0L) ?: 0L
        if (token == 0L) return
        val marker = File(dir.parentFile, CLEARED_MARKER)
        if (runCatching { marker.readText().toLong() }.getOrNull() == token) return
        val count = dir.listFiles()?.count { it.delete() } ?: 0
        runCatching {
            marker.parentFile?.mkdirs()
            marker.writeText(token.toString())
        }
        Logger.i("IA: $count sincronias salvas apagadas")
    }

    private fun trimCache() {
        val dir = cacheDir ?: return
        val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (file in files) {
            total += file.length()
            if (total > cacheLimitBytes) file.delete()
        }
    }

    private val runtimeTag: String? get() = prefs?.getString(AiFiles.PREF_RUNTIME, null)?.takeIf { it.isNotEmpty() }

    val installed: Boolean
        get() = xposed != null && !prefs?.getString(AiFiles.PREF_MODEL_SOURCE, null).isNullOrEmpty() && runtimeTag != null

    fun start(context: Context, trackKey: String, lyrics: Lyrics, onUpdate: (Lyrics, Float) -> Unit, onProblem: (Problem) -> Unit) {
        stop()
        val ticket = ++generation
        val cacheDir = File(context.cacheDir, "liquidspot/ai")
        job = executor.submit {
            try {
                run(context.applicationContext, ticket, trackKey, lyrics, cacheDir, onUpdate, onProblem)
            } catch (_: InterruptedException) {
            } catch (t: Throwable) {
                Logger.e("IA: a sincronização por sílaba parou com erro", t)
                post(ticket) { onProblem(Problem.UNAVAILABLE) }
            }
        }
    }

    fun stop() {
        generation++
        job?.cancel(true)
        job = null
    }

    private fun post(ticket: Int, block: () -> Unit) {
        main.post { if (ticket == generation) block() }
    }

    private fun run(
        context: Context,
        ticket: Int,
        trackKey: String,
        source: Lyrics,
        cacheDir: File,
        onUpdate: (Lyrics, Float) -> Unit,
        onProblem: (Problem) -> Unit,
    ) {
        val api = xposed ?: return post(ticket) { onProblem(Problem.UNAVAILABLE) }
        val tag = runtimeTag ?: return post(ticket) { onProblem(Problem.UNAVAILABLE) }
        val model = openBridge(context, api, tag) ?: return post(ticket) { onProblem(Problem.UNAVAILABLE) }
        val ctc = aligner ?: CtcAligner(readVocab(api)).also { aligner = it }

        val lines = source.lines.toMutableList()
        val candidates = lines.indices.filter { i ->
            val line = lines[i]
            line.timeMs >= 0 && line.words.isEmpty() && line.text.isNotBlank()
        }
        val tokens = candidates.associateWith { ctc.tokenize(lines[it].text) }
        val alignable = tokens.values.count { it != null }.coerceAtLeast(1)
        if (candidates.isEmpty() || tokens.values.count { it != null } < candidates.size * MIN_SUPPORTED) {
            return post(ticket) { onProblem(Problem.UNSUPPORTED) }
        }

        val cacheFile = File(cacheDir, sha1(trackKey + "|" + source.source + "|" + lines.joinToString("\n") { it.text }) + ".json")
        val cached = if (cacheEnabled) readCache(cacheFile) else emptyMap()
        val done = HashMap<Int, List<LyricWord>>()
        for ((i, words) in cached) if (i in candidates) done[i] = words
        if (done.isNotEmpty()) {
            for ((i, words) in done) lines[i] = lines[i].copy(words = words)
            val snapshot = source.copy(lines = lines.toList())
            post(ticket) { onUpdate(snapshot, done.size.toFloat() / alignable) }
        }

        val pending = candidates.filter { it !in done && tokens[it] != null }.toMutableList()
        Logger.i("IA: ${pending.size} linhas para sincronizar (${done.size} já no cache), áudio capturado até ${AudioCapture.capturedUntil(trackKey)} ms")
        var startedAt = android.os.SystemClock.uptimeMillis()
        var warnedSilence = false
        var liveLine = -1
        var liveEnd = 0L
        var beatMs = DEFAULT_BEAT_MS
        var liveReached = -1
        fun nextStart(i: Int): Long = lines.drop(i + 1).firstOrNull { it.timeMs > lines[i].timeMs }?.timeMs ?: (lines[i].timeMs + DEFAULT_LINE_MS)
        while (pending.isNotEmpty() && ticket == generation) {
            if (!awake) {
                Thread.sleep(PAUSED_POLL_MS)
                startedAt = android.os.SystemClock.uptimeMillis()
                liveEnd = 0L
                continue
            }
            var progressed = false
            var finals = 0
            val captured = AudioCapture.capturedUntil(trackKey)
            val position = NowPlayingStore.positionMs()
            var predicted = false
            for (i in pending) {
                val line = lines[i]
                if (line.words.isNotEmpty() || line.timeMs - position > PREDICT_AHEAD_MS || nextStart(i) <= position) continue
                lines[i] = line.copy(words = ctc.predictedSyllables(line.text, line.timeMs, nextStart(i) - LIVE_TAIL_MS, beatMs))
                predicted = true
            }
            if (predicted) {
                val snapshot = source.copy(lines = lines.toList())
                post(ticket) { onUpdate(snapshot, done.size.toFloat() / alignable) }
            }
            val live = pending.firstOrNull { i -> lines[i].timeMs <= position + LIVE_LEAD_MS && position < nextStart(i) }
            if (live != null) {
                val line = lines[live]
                val start = (line.timeMs - PAD_BEFORE_MS).coerceAtLeast(0L)
                val end = minOf(captured, start + MAX_WINDOW_MS)
                if (live != liveLine) {
                    liveLine = live
                    liveEnd = 0L
                    liveReached = -1
                }
                if (end - start >= LIVE_MIN_MS && end - liveEnd >= maxOf(LIVE_STEP_MS, (inferenceMs * LIVE_DUTY).toLong())) {
                    liveEnd = end
                    val samples = AudioCapture.window(trackKey, start, end)
                    val result = samples?.let { alignLive(model, ctc, line.text, tokens.getValue(live)!!, it, start, line.timeMs, nextStart(live) - LIVE_TAIL_MS, beatMs, liveReached) }
                    if (result != null) {
                        val (words, reached) = result
                        liveReached = reached
                        lines[live] = line.copy(words = words)
                        val snapshot = source.copy(lines = lines.toList())
                        post(ticket) { onUpdate(snapshot, done.size.toFloat() / alignable) }
                    }
                }
            }
            val iterator = pending.iterator()
            while (iterator.hasNext() && ticket == generation && finals < FINALS_PER_ROUND) {
                val i = iterator.next()
                val line = lines[i]
                val next = nextStart(i)
                val start = (line.timeMs - PAD_BEFORE_MS).coerceAtLeast(0L)
                val end = minOf(next + PAD_AFTER_MS, start + MAX_WINDOW_MS)
                if (captured < end) continue
                iterator.remove()
                val samples = AudioCapture.window(trackKey, start, end) ?: continue
                val words = alignLine(model, ctc, line.text, tokens.getValue(i)!!, samples, start, line.timeMs) ?: run {
                    Logger.d("IA: linha $i não alinhou")
                    null
                } ?: continue
                finals++
                done[i] = words
                val weight = ctc.weightOf(line.text)
                if (weight > 0f) {
                    val lineBeat = ((words.last().endMs - words.first().startMs) / weight).toLong().coerceIn(MIN_BEAT_MS, MAX_BEAT_MS)
                    beatMs = (beatMs * 2 + lineBeat) / 3
                }
                lines[i] = line.copy(words = words)
                progressed = true
                val snapshot = source.copy(lines = lines.toList())
                post(ticket) { onUpdate(snapshot, done.size.toFloat() / alignable) }
            }
            if (progressed && cacheEnabled) {
                writeCache(cacheFile, done)
                trimCache()
            }
            if (!warnedSilence && done.isEmpty() && android.os.SystemClock.uptimeMillis() - startedAt > SILENCE_MS && !AudioCapture.receiving()) {
                warnedSilence = true
                Logger.w("IA: nenhum áudio chegou em ${SILENCE_MS / 1000} s. ${AudioCapture.describe()}")
                post(ticket) { onProblem(Problem.NO_AUDIO) }
                return
            }
            Thread.sleep(if (live != null) LIVE_POLL_MS else POLL_MS)
        }
    }

    private fun alignLine(
        model: OrtBridge,
        ctc: CtcAligner,
        text: String,
        tokens: CtcAligner.Tokens,
        samples: FloatArray,
        windowStart: Long,
        lineStart: Long,
    ): List<LyricWord>? {
        normalize(samples)
        val began = android.os.SystemClock.uptimeMillis()
        val logits = model.logits(samples) ?: return null
        inferenceMs = android.os.SystemClock.uptimeMillis() - began
        if (logits.isEmpty()) return null
        val spans = ctc.align(logits, tokens) ?: return null
        val frameMs = samples.size * 1000f / AudioCapture.RATE / logits.size
        val words = ctc.syllables(text, tokens, spans, frameMs, windowStart)
        if (words.isEmpty()) return null
        return anchored(words, lineStart)
    }

    private fun alignLive(
        model: OrtBridge,
        ctc: CtcAligner,
        text: String,
        tokens: CtcAligner.Tokens,
        samples: FloatArray,
        windowStart: Long,
        lineStart: Long,
        limit: Long,
        beatMs: Long,
        minReached: Int,
    ): Pair<List<LyricWord>, Int>? {
        normalize(samples)
        val began = android.os.SystemClock.uptimeMillis()
        val logits = model.logits(samples) ?: return null
        inferenceMs = android.os.SystemClock.uptimeMillis() - began
        if (logits.isEmpty()) return null
        val partial = ctc.alignPartial(logits, tokens)?.takeIf { it.reached >= minReached } ?: return null
        val frameMs = samples.size * 1000f / AudioCapture.RATE / logits.size
        val words = ctc.liveSyllables(text, tokens, partial, frameMs, windowStart, limit, beatMs)
        if (words.isEmpty()) return null
        return anchored(words, lineStart) to partial.reached
    }

    private fun anchored(words: List<LyricWord>, lineStart: Long): List<LyricWord> {
        if (AudioCapture.nativeSource) return words
        val shift = lineStart - words.first().startMs
        return if (abs(shift) <= MAX_ANCHOR_SHIFT_MS) words.map { it.copy(startMs = it.startMs + shift, endMs = it.endMs + shift) } else words
    }

    private fun normalize(samples: FloatArray) {
        var mean = 0.0
        for (v in samples) mean += v
        mean /= samples.size
        var variance = 0.0
        for (v in samples) variance += (v - mean) * (v - mean)
        val std = kotlin.math.sqrt(variance / samples.size + 1e-7)
        for (i in samples.indices) samples[i] = ((samples[i] - mean) / std).toFloat()
    }

    private fun openBridge(context: Context, api: XposedInterface, tag: String): OrtBridge? {
        bridge?.let { if (bridgeTag == tag) return it }
        closeBridge()
        return runCatching { OrtBridge.open(context, api, tag) }
            .onSuccess {
                bridge = it
                bridgeTag = tag
                Logger.i("IA: modelo de sílabas carregado")
            }
            .onFailure { Logger.e("IA: não foi possível carregar o motor ou o modelo", it) }
            .getOrNull()
    }

    private fun closeBridge() {
        bridge?.close()
        bridge = null
        bridgeTag = null
        aligner = null
    }

    private fun readVocab(api: XposedInterface): Map<String, Int> = runCatching {
        val text = api.openRemoteFile(AiFiles.VOCAB).use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
        }
        val json = JSONObject(text)
        json.keys().asSequence().associateWith { json.getInt(it) }
    }.getOrNull()?.takeIf { it.containsKey("|") } ?: AiFiles.DEFAULT_VOCAB

    private fun readCache(file: File): Map<Int, List<LyricWord>> = runCatching {
        if (!file.isFile) return emptyMap()
        val json = JSONObject(file.readText())
        json.keys().asSequence().associate { key ->
            val array = json.getJSONArray(key)
            key.toInt() to (0 until array.length()).map { i ->
                val w = array.getJSONArray(i)
                LyricWord(w.getLong(0), w.getLong(1), w.getInt(2), w.getInt(3))
            }
        }
    }.getOrDefault(emptyMap())

    private fun writeCache(file: File, done: Map<Int, List<LyricWord>>) {
        runCatching {
            file.parentFile?.mkdirs()
            val json = JSONObject()
            for ((i, words) in done) {
                json.put(i.toString(), JSONArray().apply { words.forEach { put(JSONArray().put(it.startMs).put(it.endMs).put(it.from).put(it.to)) } })
            }
            file.writeText(json.toString())
        }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private const val PAD_BEFORE_MS = 300L
    private const val PAD_AFTER_MS = 400L
    private const val DEFAULT_LINE_MS = 6_000L
    private const val MAX_WINDOW_MS = 12_000L
    private const val MAX_ANCHOR_SHIFT_MS = 1_200L
    private const val MIN_SUPPORTED = 0.6f
    private const val POLL_MS = 400L
    private const val PAUSED_POLL_MS = 500L
    private const val AWAKE_MS = 1_000L
    private const val FINALS_PER_ROUND = 2
    private const val LIVE_DUTY = 2.5f
    private const val CLEARED_MARKER = "ai-cache-cleared"
    private const val LIVE_POLL_MS = 150L
    private const val LIVE_STEP_MS = 450L
    private const val LIVE_MIN_MS = 700L
    private const val LIVE_LEAD_MS = 150L
    private const val LIVE_TAIL_MS = 150L
    private const val PREDICT_AHEAD_MS = 3_000L
    private const val DEFAULT_BEAT_MS = 280L
    private const val MIN_BEAT_MS = 110L
    private const val MAX_BEAT_MS = 800L
    private const val SILENCE_MS = 12_000L
}
