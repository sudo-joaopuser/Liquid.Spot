#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <atomic>
#include <cstdint>

#define LOG_TAG "LiquidSpot"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

typedef int (*HookFunType)(void *func, void *replace, void **backup);
typedef int (*UnhookFunType)(void *func);
typedef void (*NativeOnModuleLoaded)(const char *name, void *handle);

struct NativeAPIEntries {
    uint32_t version;
    HookFunType hookFunc;
    UnhookFunType unhookFunc;
};

struct AAudioStreamStruct;
struct AAudioStreamBuilderStruct;
typedef AAudioStreamStruct AAudioStream;
typedef AAudioStreamBuilderStruct AAudioStreamBuilder;
typedef int32_t (*DataCallback)(AAudioStream *stream, void *userData, void *audioData, int32_t numFrames);

namespace {
    constexpr int32_t FORMAT_I16 = 1;
    constexpr int32_t FORMAT_FLOAT = 2;
    constexpr int32_t FORMAT_I32 = 4;
    constexpr int32_t DIRECTION_OUTPUT = 0;
    constexpr uint64_t RING = 1u << 20;

    HookFunType gHook = nullptr;
    std::atomic<bool> gEnabled{false};
    std::atomic<uint64_t> gWritten{0};
    std::atomic<int32_t> gRate{0};
    std::atomic<uint64_t> gBlocks{0};
    float gRing[RING];

    int32_t (*pGetFormat)(AAudioStream *) = nullptr;
    int32_t (*pGetChannels)(AAudioStream *) = nullptr;
    int32_t (*pGetRate)(AAudioStream *) = nullptr;
    int32_t (*pGetDirection)(AAudioStream *) = nullptr;

    void (*origSetDataCallback)(AAudioStreamBuilder *, DataCallback, void *) = nullptr;
    int32_t (*origWrite)(AAudioStream *, const void *, int32_t, int64_t) = nullptr;

    struct Wrapped {
        DataCallback callback;
        void *userData;
    };

    void capture(AAudioStream *stream, const void *data, int32_t frames) {
        if (!gEnabled.load(std::memory_order_relaxed) || data == nullptr || frames <= 0) return;
        if (pGetDirection != nullptr && pGetDirection(stream) != DIRECTION_OUTPUT) return;
        const int32_t format = pGetFormat(stream);
        const int32_t channels = pGetChannels(stream);
        if (channels <= 0) return;
        if (format != FORMAT_I16 && format != FORMAT_FLOAT && format != FORMAT_I32) return;
        gRate.store(pGetRate(stream), std::memory_order_relaxed);
        const uint64_t start = gWritten.load(std::memory_order_relaxed);
        for (int32_t f = 0; f < frames; f++) {
            float sum = 0.0f;
            for (int32_t c = 0; c < channels; c++) {
                const int64_t i = static_cast<int64_t>(f) * channels + c;
                if (format == FORMAT_I16) {
                    sum += static_cast<const int16_t *>(data)[i] / 32768.0f;
                } else if (format == FORMAT_FLOAT) {
                    sum += static_cast<const float *>(data)[i];
                } else {
                    sum += static_cast<float>(static_cast<const int32_t *>(data)[i] / 2147483648.0);
                }
            }
            gRing[(start + f) & (RING - 1)] = sum / channels;
        }
        gWritten.store(start + frames, std::memory_order_release);
        gBlocks.fetch_add(1, std::memory_order_relaxed);
    }

    int32_t trampoline(AAudioStream *stream, void *userData, void *audioData, int32_t numFrames) {
        auto *wrapped = static_cast<Wrapped *>(userData);
        const int32_t result = wrapped->callback(stream, wrapped->userData, audioData, numFrames);
        capture(stream, audioData, numFrames);
        return result;
    }

    void hookedSetDataCallback(AAudioStreamBuilder *builder, DataCallback callback, void *userData) {
        if (callback == nullptr) {
            origSetDataCallback(builder, callback, userData);
            return;
        }
        origSetDataCallback(builder, trampoline, new Wrapped{callback, userData});
    }

    int32_t hookedWrite(AAudioStream *stream, const void *buffer, int32_t numFrames, int64_t timeoutNanos) {
        const int32_t written = origWrite(stream, buffer, numFrames, timeoutNanos);
        if (written > 0) capture(stream, buffer, written);
        return written;
    }

    template<typename T>
    bool resolve(void *library, const char *name, T *out) {
        *out = reinterpret_cast<T>(dlsym(library, name));
        return *out != nullptr;
    }
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    gHook = entries != nullptr ? entries->hookFunc : nullptr;
    return nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_liquidspot_xposed_features_player_lyrics_ai_NativeAudio_install(JNIEnv *, jclass) {
    if (gHook == nullptr) return 1;
    void *aaudio = dlopen("libaaudio.so", RTLD_NOW);
    if (aaudio == nullptr) return 2;
    if (!resolve(aaudio, "AAudioStream_getFormat", &pGetFormat) ||
        !resolve(aaudio, "AAudioStream_getChannelCount", &pGetChannels) ||
        !resolve(aaudio, "AAudioStream_getSampleRate", &pGetRate)) {
        return 2;
    }
    resolve(aaudio, "AAudioStream_getDirection", &pGetDirection);
    void *setDataCallback = dlsym(aaudio, "AAudioStreamBuilder_setDataCallback");
    void *write = dlsym(aaudio, "AAudioStream_write");
    int hooked = 0;
    if (setDataCallback != nullptr &&
        gHook(setDataCallback, reinterpret_cast<void *>(hookedSetDataCallback), reinterpret_cast<void **>(&origSetDataCallback)) == 0) {
        hooked++;
    }
    if (write != nullptr &&
        gHook(write, reinterpret_cast<void *>(hookedWrite), reinterpret_cast<void **>(&origWrite)) == 0) {
        hooked++;
    }
    if (hooked == 0) return 3;
    LOGI("IA: hook nativo do AAudio instalado (%d funções)", hooked);
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_app_liquidspot_xposed_features_player_lyrics_ai_NativeAudio_setEnabled(JNIEnv *, jclass, jboolean enabled) {
    gEnabled.store(enabled == JNI_TRUE, std::memory_order_relaxed);
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_liquidspot_xposed_features_player_lyrics_ai_NativeAudio_written(JNIEnv *, jclass) {
    return static_cast<jlong>(gWritten.load(std::memory_order_acquire));
}

extern "C" JNIEXPORT jint JNICALL
Java_app_liquidspot_xposed_features_player_lyrics_ai_NativeAudio_sampleRate(JNIEnv *, jclass) {
    return gRate.load(std::memory_order_relaxed);
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_liquidspot_xposed_features_player_lyrics_ai_NativeAudio_blocks(JNIEnv *, jclass) {
    return static_cast<jlong>(gBlocks.load(std::memory_order_relaxed));
}

extern "C" JNIEXPORT jint JNICALL
Java_app_liquidspot_xposed_features_player_lyrics_ai_NativeAudio_read(JNIEnv *env, jclass, jfloatArray out, jlong from) {
    const uint64_t written = gWritten.load(std::memory_order_acquire);
    auto start = static_cast<uint64_t>(from);
    if (start >= written) return 0;
    if (written - start > RING) return -1;
    const jsize capacity = env->GetArrayLength(out);
    const auto count = static_cast<jsize>(written - start < static_cast<uint64_t>(capacity) ? written - start : capacity);
    jfloat *buffer = env->GetFloatArrayElements(out, nullptr);
    if (buffer == nullptr) return 0;
    for (jsize i = 0; i < count; i++) buffer[i] = gRing[(start + i) & (RING - 1)];
    env->ReleaseFloatArrayElements(out, buffer, 0);
    return count;
}
