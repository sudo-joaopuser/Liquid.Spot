package app.liquidspot.ui.ai

import android.content.ContentResolver
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import app.liquidspot.BuildConfig
import app.liquidspot.core.AiFiles
import io.github.libxposed.service.XposedService
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

class AiInstaller(private val service: XposedService, private val prefs: SharedPreferences) {
    data class Status(val modelSource: String?, val modelSize: Long, val runtime: String?) {
        val modelInstalled: Boolean get() = modelSource != null
        val runtimeInstalled: Boolean get() = runtime == AiFiles.runtimeTag(deviceAbi())
    }

    fun status() = Status(
        modelSource = prefs.getString(AiFiles.PREF_MODEL_SOURCE, null)?.takeIf { it.isNotEmpty() },
        modelSize = prefs.getLong(AiFiles.PREF_MODEL_SIZE, 0L),
        runtime = prefs.getString(AiFiles.PREF_RUNTIME, null),
    )

    suspend fun download(progress: (Float) -> Unit) {
        val runtimeShare = if (status().runtimeInstalled) 0f else RUNTIME_SHARE
        val size = write(AiFiles.MODEL) { out ->
            http(AiFiles.MODEL_URL).useBody { input, length ->
                copy(input, out, length.takeIf { it > 0 } ?: AiFiles.MODEL_BYTES) { progress(it * (1f - runtimeShare)) }
            }
        }
        write(AiFiles.VOCAB) { out ->
            http(AiFiles.VOCAB_URL).useBody { input, _ -> copy(input, out, 0L) {} }
        }
        commitModel(AiFiles.SOURCE_DOWNLOAD, size)
        if (runtimeShare > 0f) installRuntime { progress(1f - runtimeShare + it * runtimeShare) }
        progress(1f)
    }

    suspend fun import(resolver: ContentResolver, uri: Uri, progress: (Float) -> Unit) {
        val total = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }?.takeIf { it > 0 } ?: 0L
        val zip = resolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(4)
            val read = input.read(head)
            read == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        } ?: throw IllegalArgumentException("arquivo ilegível")
        var size = 0L
        var vocab = false
        if (zip) {
            resolver.openInputStream(uri)?.use { raw ->
                val counting = CountingStream(raw)
                ZipInputStream(counting).use { stream ->
                    while (true) {
                        val entry = stream.nextEntry ?: break
                        val name = entry.name.substringAfterLast('/').lowercase()
                        when {
                            entry.isDirectory -> Unit
                            size == 0L && name.endsWith(".onnx") -> size = write(AiFiles.MODEL) { out ->
                                copy(stream, out, 0L) { if (total > 0) progress(counting.count.toFloat() / total * 0.9f) }
                            }
                            !vocab && name == "vocab.json" -> {
                                val text = stream.readBytes().toString(Charsets.UTF_8)
                                JSONObject(text)
                                write(AiFiles.VOCAB) { it.write(text.toByteArray()) }
                                vocab = true
                            }
                        }
                    }
                }
            }
            if (size == 0L) throw IllegalArgumentException("o zip não tem um arquivo .onnx")
        } else {
            size = write(AiFiles.MODEL) { out ->
                resolver.openInputStream(uri)?.use { copy(it, out, total) { p -> progress(p * 0.9f) } }
            }
        }
        if (!vocab) write(AiFiles.VOCAB) { it.write(JSONObject(AiFiles.DEFAULT_VOCAB).toString().toByteArray()) }
        commitModel(AiFiles.SOURCE_IMPORT, size)
        if (!status().runtimeInstalled) installRuntime { progress(0.9f + it * 0.1f) }
        progress(1f)
    }

    fun remove() {
        for (name in listOf(AiFiles.MODEL, AiFiles.VOCAB, AiFiles.RUNTIME, AiFiles.RUNTIME_JNI)) {
            runCatching { service.deleteRemoteFile(name) }
        }
        prefs.edit()
            .remove(AiFiles.PREF_MODEL_SOURCE)
            .remove(AiFiles.PREF_MODEL_SIZE)
            .remove(AiFiles.PREF_RUNTIME)
            .apply()
    }

    fun discardPartial() {
        val s = status()
        if (!s.modelInstalled) {
            runCatching { service.deleteRemoteFile(AiFiles.MODEL) }
            runCatching { service.deleteRemoteFile(AiFiles.VOCAB) }
        }
        if (!s.runtimeInstalled) {
            runCatching { service.deleteRemoteFile(AiFiles.RUNTIME) }
            runCatching { service.deleteRemoteFile(AiFiles.RUNTIME_JNI) }
        }
    }

    private fun commitModel(source: String, size: Long) {
        prefs.edit().putString(AiFiles.PREF_MODEL_SOURCE, source).putLong(AiFiles.PREF_MODEL_SIZE, size).apply()
    }

    private suspend fun installRuntime(progress: (Float) -> Unit) {
        val abi = deviceAbi()
        val zip = RemoteZip(AiFiles.RUNTIME_URL)
        val entries = zip.entries()
        val wanted = listOf("jni/$abi/libonnxruntime.so" to AiFiles.RUNTIME, "jni/$abi/libonnxruntime4j_jni.so" to AiFiles.RUNTIME_JNI)
        val total = wanted.sumOf { (path, _) -> entries[path]?.compressed ?: throw IllegalStateException("motor sem a arquitetura $abi") }
        var done = 0L
        for ((path, name) in wanted) {
            val entry = entries.getValue(path)
            write(name) { out ->
                zip.open(entry).use { input -> copy(input, out, 0L) {} }
            }
            done += entry.compressed
            progress(done.toFloat() / total)
        }
        prefs.edit().putString(AiFiles.PREF_RUNTIME, AiFiles.runtimeTag(abi)).apply()
    }

    private suspend fun write(name: String, block: suspend (OutputStream) -> Unit): Long {
        runCatching { service.deleteRemoteFile(name) }
        val pfd = service.openRemoteFile(name)
        val out = CountingOutput(ParcelFileDescriptor.AutoCloseOutputStream(pfd))
        out.use { block(it) }
        return out.count
    }

    private suspend fun copy(input: InputStream, out: OutputStream, total: Long, progress: (Float) -> Unit) {
        val buffer = ByteArray(BUFFER)
        var copied = 0L
        var lastReport = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            copied += read
            if (total > 0 && copied - lastReport > REPORT_BYTES) {
                lastReport = copied
                progress((copied.toFloat() / total).coerceIn(0f, 1f))
            }
        }
    }

    private class Body(val conn: HttpURLConnection) {
        inline fun <T> useBody(block: (InputStream, Long) -> T): T = try {
            conn.inputStream.use { block(it, conn.contentLengthLong) }
        } finally {
            conn.disconnect()
        }
    }

    private fun http(url: String, range: String? = null): Body {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "LiquidSpot/${BuildConfig.VERSION_NAME}")
        if (range != null) conn.setRequestProperty("Range", "bytes=$range")
        val code = conn.responseCode
        if (code != 200 && code != 206) {
            conn.disconnect()
            throw IllegalStateException("HTTP $code em $url")
        }
        return Body(conn)
    }

    private inner class RemoteZip(private val url: String) {
        inner class Entry(val method: Int, val compressed: Long, val localOffset: Long)

        fun entries(): Map<String, Entry> {
            val tail = http(url, "-$TAIL_BYTES").useBody { input, _ -> input.readBytes() }
            var eocd = -1
            for (i in tail.size - 22 downTo 0) {
                if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() && tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()) {
                    eocd = i
                    break
                }
            }
            if (eocd < 0) throw IllegalStateException("índice do zip não encontrado")
            val end = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
            val count = end.getShort(eocd + 10).toInt() and 0xFFFF
            val cdSize = end.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
            val cdOffset = end.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
            val cd = http(url, "$cdOffset-${cdOffset + cdSize - 1}").useBody { input, _ -> input.readBytes() }
            val b = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)
            val out = HashMap<String, Entry>()
            var p = 0
            repeat(count) {
                val method = b.getShort(p + 10).toInt() and 0xFFFF
                val compressed = b.getInt(p + 20).toLong() and 0xFFFFFFFFL
                val nameLen = b.getShort(p + 28).toInt() and 0xFFFF
                val extraLen = b.getShort(p + 30).toInt() and 0xFFFF
                val commentLen = b.getShort(p + 32).toInt() and 0xFFFF
                val local = b.getInt(p + 42).toLong() and 0xFFFFFFFFL
                val name = String(cd, p + 46, nameLen, Charsets.UTF_8)
                out[name] = Entry(method, compressed, local)
                p += 46 + nameLen + extraLen + commentLen
            }
            return out
        }

        fun open(entry: Entry): InputStream {
            val header = http(url, "${entry.localOffset}-${entry.localOffset + 29}").useBody { input, _ -> input.readBytes() }
            val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val dataStart = entry.localOffset + 30 + (h.getShort(26).toInt() and 0xFFFF) + (h.getShort(28).toInt() and 0xFFFF)
            val body = http(url, "$dataStart-${dataStart + entry.compressed - 1}")
            val raw = object : java.io.FilterInputStream(body.conn.inputStream) {
                override fun close() {
                    super.close()
                    body.conn.disconnect()
                }
            }
            return if (entry.method == 8) InflaterInputStream(raw, Inflater(true), BUFFER) else raw
        }
    }

    private class CountingOutput(out: OutputStream) : java.io.FilterOutputStream(out) {
        var count = 0L
        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        override fun write(b: Int) {
            out.write(b)
            count++
        }
    }

    private class CountingStream(input: InputStream) : java.io.FilterInputStream(input) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    companion object {
        private const val BUFFER = 64 * 1024
        private const val REPORT_BYTES = 512 * 1024
        private const val TAIL_BYTES = 65_536
        private const val RUNTIME_SHARE = 0.07f

        fun deviceAbi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
    }
}
