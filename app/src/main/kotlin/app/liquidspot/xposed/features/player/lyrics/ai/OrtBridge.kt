package app.liquidspot.xposed.features.player.lyrics.ai

import android.content.Context
import android.os.Process
import app.liquidspot.core.AiFiles
import dalvik.system.PathClassLoader
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.io.FileInputStream
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.channels.FileChannel

class OrtBridge private constructor(
    private val env: Any,
    private val session: Any,
    private val inputName: String,
    private val createTensor: Method,
    private val run: Method,
    private val resultGet: Method,
    private val valueGet: Method,
) {
    fun logits(samples: FloatArray): Array<FloatArray>? {
        val tensor = createTensor.invoke(null, env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())) as AutoCloseable
        try {
            val result = run.invoke(session, mapOf(inputName to tensor)) as AutoCloseable
            try {
                val value = resultGet.invoke(result, 0) ?: return null
                @Suppress("UNCHECKED_CAST")
                val out = valueGet.invoke(value) as? Array<Array<FloatArray>> ?: return null
                return out.firstOrNull()
            } finally {
                result.close()
            }
        } finally {
            tensor.close()
        }
    }

    fun close() {
        runCatching { (session as AutoCloseable).close() }
    }

    companion object {
        private const val PACKAGE = "ai.onnxruntime."
        private const val THREADS = 2

        fun open(context: Context, xposed: XposedInterface, runtimeTag: String): OrtBridge {
            val abi = runtimeTag.substringAfter('|')
            if (abi.contains("64") != Process.is64Bit()) throw IllegalStateException("motor de outra arquitetura ($abi)")
            val dir = File(context.codeCacheDir, "liquidspot-ai/" + runtimeTag.replace('|', '-'))
            dir.mkdirs()
            copyLibrary(xposed, AiFiles.RUNTIME, File(dir, "libonnxruntime.so"))
            copyLibrary(xposed, AiFiles.RUNTIME_JNI, File(dir, "libonnxruntime4j_jni.so"))

            val apk = xposed.moduleApplicationInfo.sourceDir
            val loader = PathClassLoader(apk, dir.absolutePath, ClassLoader.getSystemClassLoader())
            val envClass = loader.loadClass(PACKAGE + "OrtEnvironment")
            val optionsClass = loader.loadClass(PACKAGE + "OrtSession\$SessionOptions")
            val sessionClass = loader.loadClass(PACKAGE + "OrtSession")
            val tensorClass = loader.loadClass(PACKAGE + "OnnxTensor")
            val resultClass = loader.loadClass(PACKAGE + "OrtSession\$Result")
            val valueClass = loader.loadClass(PACKAGE + "OnnxValue")

            val env = envClass.getMethod("getEnvironment").invoke(null)!!
            val options = optionsClass.getConstructor().newInstance()
            optionsClass.getMethod("setIntraOpNumThreads", Int::class.javaPrimitiveType).invoke(options, THREADS)
            optionsClass.getMethod("setInterOpNumThreads", Int::class.javaPrimitiveType).invoke(options, 1)
            val model = mapModel(xposed)
            val session = envClass.getMethod("createSession", ByteBuffer::class.java, optionsClass).invoke(env, model, options)!!
            @Suppress("UNCHECKED_CAST")
            val inputs = sessionClass.getMethod("getInputNames").invoke(session) as Set<String>
            return OrtBridge(
                env = env,
                session = session,
                inputName = inputs.first(),
                createTensor = tensorClass.getMethod("createTensor", envClass, FloatBuffer::class.java, LongArray::class.java),
                run = sessionClass.getMethod("run", Map::class.java),
                resultGet = resultClass.getMethod("get", Int::class.javaPrimitiveType),
                valueGet = valueClass.getMethod("getValue"),
            )
        }

        private fun copyLibrary(xposed: XposedInterface, remote: String, target: File) {
            xposed.openRemoteFile(remote).use { pfd ->
                if (target.isFile && target.length() == pfd.statSize) return
                val tmp = File(target.path + ".tmp")
                tmp.delete()
                FileInputStream(pfd.fileDescriptor).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                target.setWritable(true)
                target.delete()
                if (!tmp.renameTo(target)) throw IllegalStateException("não foi possível preparar ${target.name}")
                target.setReadOnly()
            }
        }

        private fun mapModel(xposed: XposedInterface): ByteBuffer =
            xposed.openRemoteFile(AiFiles.MODEL).use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { input ->
                    input.channel.map(FileChannel.MapMode.READ_ONLY, 0, pfd.statSize)
                }
            }
    }
}
