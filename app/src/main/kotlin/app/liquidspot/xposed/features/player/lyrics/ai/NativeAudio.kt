package app.liquidspot.xposed.features.player.lyrics.ai

import app.liquidspot.xposed.util.Logger

object NativeAudio {
    @JvmStatic
    private external fun install(): Int

    @JvmStatic
    private external fun setEnabled(enabled: Boolean)

    @JvmStatic
    private external fun written(): Long

    @JvmStatic
    private external fun sampleRate(): Int

    @JvmStatic
    private external fun blocks(): Long

    @JvmStatic
    private external fun read(out: FloatArray, from: Long): Int

    private var status = "não carregado"
    private var ready = false
    private var pump: Thread? = null

    fun load() {
        if (ready || status != "não carregado") return
        status = runCatching {
            System.loadLibrary(LIBRARY)
            when (install()) {
                0 -> {
                    ready = true
                    "hook instalado"
                }
                1 -> "a API nativa do LSPosed não chamou native_init"
                2 -> "libaaudio indisponível"
                else -> "não foi possível fazer o hook do AAudio"
            }
        }.getOrElse { "falha ao carregar ($it)" }
        if (ready) Logger.i("IA: áudio nativo pronto") else Logger.w("IA: áudio nativo indisponível: $status")
    }

    fun setCapturing(enabled: Boolean) {
        if (!ready) return
        setEnabled(enabled)
        if (enabled && pump == null) {
            pump = Thread(::run, "LiquidSpot-NativeAudio").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
                start()
            }
        }
    }

    fun describe(): String = if (ready) "$status, blocos=${blocks()}, taxa=${sampleRate()} Hz" else status

    private fun run() {
        val buffer = FloatArray(BUFFER)
        var cursor = written()
        while (true) {
            try {
                Thread.sleep(PUMP_MS)
            } catch (_: InterruptedException) {
                return
            }
            if (!AudioCapture.enabled) {
                cursor = written()
                continue
            }
            while (true) {
                val count = read(buffer, cursor)
                if (count < 0) {
                    cursor = written()
                    break
                }
                if (count == 0) break
                cursor += count
                runCatching { AudioCapture.feedMono(buffer, count, sampleRate()) }
            }
        }
    }

    private const val LIBRARY = "liquidspotaudio"
    private const val BUFFER = 16_384
    private const val PUMP_MS = 40L
}
