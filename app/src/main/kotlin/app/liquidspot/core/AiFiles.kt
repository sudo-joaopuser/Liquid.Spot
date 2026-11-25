package app.liquidspot.core

import app.liquidspot.BuildConfig

object AiFiles {
    const val MODEL = "ai_model.onnx"
    const val VOCAB = "ai_vocab.json"
    const val RUNTIME = "ai_libonnxruntime.so"
    const val RUNTIME_JNI = "ai_libonnxruntime4j_jni.so"

    const val PREF_MODEL_SOURCE = "ai.model_source"
    const val PREF_MODEL_SIZE = "ai.model_size"
    const val PREF_RUNTIME = "ai.runtime"
    const val PREF_CACHE_ENABLED = "ai.cache_enabled"
    const val PREF_CACHE_LIMIT_MB = "ai.cache_limit_mb"
    const val PREF_CACHE_CLEAR = "ai.cache_clear"
    const val CACHE_ENABLED_DEFAULT = true
    const val CACHE_LIMIT_DEFAULT_MB = 20
    val CACHE_LIMIT_OPTIONS_MB = listOf(5, 20, 50, 100)

    const val SOURCE_DOWNLOAD = "download"
    const val SOURCE_IMPORT = "import"

    const val MODEL_URL = "https://huggingface.co/Xenova/wav2vec2-base-960h/resolve/main/onnx/model_quantized.onnx"
    const val VOCAB_URL = "https://huggingface.co/Xenova/wav2vec2-base-960h/resolve/main/vocab.json"
    const val MODEL_BYTES = 95_286_046L

    val ORT_VERSION: String get() = BuildConfig.ORT_VERSION
    val RUNTIME_URL: String
        get() = "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/$ORT_VERSION/onnxruntime-android-$ORT_VERSION.aar"

    fun runtimeTag(abi: String) = "$ORT_VERSION|$abi"

    val DEFAULT_VOCAB: Map<String, Int> = linkedMapOf(
        "<pad>" to 0, "<s>" to 1, "</s>" to 2, "<unk>" to 3, "|" to 4,
        "E" to 5, "T" to 6, "A" to 7, "O" to 8, "N" to 9, "I" to 10, "H" to 11, "S" to 12, "R" to 13,
        "D" to 14, "L" to 15, "U" to 16, "M" to 17, "W" to 18, "C" to 19, "F" to 20, "G" to 21, "Y" to 22,
        "P" to 23, "B" to 24, "V" to 25, "K" to 26, "'" to 27, "X" to 28, "J" to 29, "Q" to 30, "Z" to 31,
    )
}
