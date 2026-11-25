package app.liquidspot.xposed.features.player.lyrics.ai

import app.liquidspot.xposed.HookContext
import app.liquidspot.xposed.media.NowPlayingStore
import app.liquidspot.xposed.util.Logger
import java.lang.reflect.Field
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

object AudioCapture {
    const val RATE = 16_000

    @Volatile
    var enabled = false
        set(value) {
            field = value
            if (value) NativeAudio.load()
            NativeAudio.setCapturing(value)
        }

    private class Track(val key: String, val baseMs: Long) {
        val chunks = ArrayList<ShortArray>()
        var count = 0L
        var acc = 0f
        var accSamples = 0
        var phase = 0f

        fun endMs() = baseMs + count * 1000 / RATE

        fun add(value: Float) {
            if (count >= MAX_SAMPLES) return
            val index = (count % CHUNK).toInt()
            if (index == 0) chunks += ShortArray(CHUNK)
            chunks.last()[index] = (value.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            count++
        }
    }

    private var current: Track? = null
    private val lock = Any()
    private var installed = false
    private var rateField: Field? = null
    private var channelField: Field? = null
    private var frameField: Field? = null
    private var oboeField: Field? = null
    @Volatile
    var nativeSource = false
        private set
    @Volatile
    private var lastWrite = 0L
    @Volatile
    private var writes = 0L
    private var format = ""

    fun install(ctx: HookContext) {
        if (installed) return
        installed = true
        val driver = runCatching { ctx.findClass(DRIVER) }.getOrElse {
            Logger.w("IA: AudioDriver do Spotify não encontrado; a sincronização por IA fica indisponível", it)
            return
        }
        rateField = field(driver, "mSampleRate")
        channelField = field(driver, "mChannelConfig")
        frameField = field(driver, "mBytesPerFrame")
        oboeField = runCatching { driver.getDeclaredField("sUsingOboe").apply { isAccessible = true } }.getOrNull()
        val write = runCatching { driver.getDeclaredMethod("write", ByteArray::class.java, Int::class.javaPrimitiveType) }.getOrElse {
            Logger.w("IA: AudioDriver.write não encontrado", it)
            return
        }
        ctx.hooks.before(write) {
            if (!enabled) return@before
            val data = arg<ByteArray?>(0) ?: return@before
            val length = arg<Int>(1).coerceIn(0, data.size)
            val self = thisObject ?: return@before
            runCatching { onWrite(self, data, length) }
        }
        Logger.i("IA: captura de áudio pronta")
    }

    private fun field(cls: Class<*>, name: String): Field? =
        runCatching { cls.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()

    private fun onWrite(driver: Any, data: ByteArray, length: Int) {
        val rate = rateField?.getInt(driver)?.takeIf { it > 0 } ?: return
        val channels = when (channelField?.getInt(driver)) {
            MONO -> 1
            STEREO -> 2
            else -> 2
        }
        val frameBytes = frameField?.getInt(driver)?.takeIf { it > 0 } ?: (channels * 2)
        val sampleBytes = frameBytes / channels
        lastWrite = android.os.SystemClock.uptimeMillis()
        if (writes++ == 0L) {
            format = "$rate Hz, $channels canais, $sampleBytes bytes por amostra"
            Logger.i("IA: primeiro áudio capturado ($format)")
        }
        if (sampleBytes != 2 && sampleBytes != 4) return
        val key = NowPlayingStore.track?.key ?: return
        val position = NowPlayingStore.positionMs()
        synchronized(lock) {
            val track = trackFor(key, position)
            val buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)
            val step = rate.toFloat() / RATE
            val frames = length / frameBytes
            for (f in 0 until frames) {
                var sum = 0f
                for (c in 0 until channels) {
                    sum += if (sampleBytes == 2) buffer.getShort().toFloat() / Short.MAX_VALUE else buffer.getFloat()
                }
                push(track, sum / channels, step)
            }
        }
    }

    private fun trackFor(key: String, position: Long): Track {
        val track = current
        if (track != null && track.key == key && abs(track.endMs() - position) <= DRIFT_MS) return track
        return Track(key, position).also { current = it }
    }

    private fun push(track: Track, value: Float, step: Float) {
        track.acc += value
        track.accSamples++
        track.phase += 1f
        if (track.phase >= step) {
            track.phase -= step
            track.add(track.acc / track.accSamples)
            track.acc = 0f
            track.accSamples = 0
        }
    }

    fun feedMono(samples: FloatArray, count: Int, rate: Int) {
        if (!enabled || count <= 0 || rate <= 0) return
        nativeSource = true
        lastWrite = android.os.SystemClock.uptimeMillis()
        if (writes++ == 0L) {
            format = "nativo, $rate Hz"
            Logger.i("IA: primeiro áudio capturado ($format)")
        }
        val key = NowPlayingStore.track?.key ?: return
        val position = NowPlayingStore.positionMs()
        val step = rate.toFloat() / RATE
        synchronized(lock) {
            val track = trackFor(key, position)
            for (i in 0 until count) push(track, samples[i], step)
        }
    }

    fun receiving(): Boolean = android.os.SystemClock.uptimeMillis() - lastWrite < RECEIVING_MS

    fun usingOboe(): Boolean? = runCatching { oboeField?.getBoolean(null) }.getOrNull()

    fun describe(): String {
        val oboe = usingOboe()
        return "Captura ligada=$enabled, escritas=$writes, formato=[$format], sUsingOboe=$oboe, nativo=${NativeAudio.describe()}"
    }

    fun capturedUntil(key: String): Long = synchronized(lock) {
        current?.takeIf { it.key == key }?.endMs() ?: Long.MIN_VALUE
    }

    fun window(key: String, startMs: Long, endMs: Long): FloatArray? = synchronized(lock) {
        val track = current?.takeIf { it.key == key } ?: return null
        val from = (startMs - track.baseMs) * RATE / 1000
        val to = (endMs - track.baseMs) * RATE / 1000
        if (from < 0 || to > track.count || to <= from) return null
        FloatArray((to - from).toInt()) { i ->
            val s = from + i
            track.chunks[(s / CHUNK).toInt()][(s % CHUNK).toInt()].toFloat() / Short.MAX_VALUE
        }
    }

    private const val DRIVER = "com.spotify.playbacknative.AudioDriver"
    private const val MONO = 4
    private const val STEREO = 12
    private const val CHUNK = RATE
    private const val MAX_SAMPLES = RATE * 60L * 12
    private const val DRIFT_MS = 2_500L
    private const val RECEIVING_MS = 3_000L
}
