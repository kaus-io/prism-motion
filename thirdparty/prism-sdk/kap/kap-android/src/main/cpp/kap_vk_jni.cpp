#include "ktx_vk_engine.h"
#include "ktx_vk_shared_device.h"
#include "ktx_zstd_file.h"

#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <zstd.h>

#include <memory>
#include <mutex>
#include <unordered_map>
#include <utility>
#include <cerrno>
#include <cstdio>
#include <cstring>

#define KTX_JNI_LOG_TAG "KtxAnimJni"
#define KTX_JNI_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, KTX_JNI_LOG_TAG, __VA_ARGS__)

namespace {
    /**
     * Maps opaque Java handles to live engines. Late calls after `nativeDestroy`
     * resolve to null instead of dereferencing freed memory; each call keeps a
     * shared reference while it runs.
     *
     * 把不透明 Java 句柄映射到存活引擎。`nativeDestroy` 之后的迟到调用解析为空，
     * 不会解引用已释放内存；每次调用期间持有 shared 引用。
     */
    class EngineRegistry {
    public:
        jlong add(kap_vk::Engine *engine) {
            std::lock_guard<std::mutex> lock(mutex_);
            jlong handle = next_++;
            while (handle == 0 || engines_.find(handle) != engines_.end()) {
                handle = next_++;
            }
            engines_.emplace(handle, std::shared_ptr<kap_vk::Engine>(
                    engine, [](kap_vk::Engine *value) { value->destroy(); }));
            return handle;
        }

        std::shared_ptr<kap_vk::Engine> acquire(jlong handle) {
            std::lock_guard<std::mutex> lock(mutex_);
            auto it = engines_.find(handle);
            return it == engines_.end() ? nullptr : it->second;
        }

        std::shared_ptr<kap_vk::Engine> remove(jlong handle) {
            std::lock_guard<std::mutex> lock(mutex_);
            auto it = engines_.find(handle);
            if (it == engines_.end()) return nullptr;
            std::shared_ptr<kap_vk::Engine> engine = std::move(it->second);
            engines_.erase(it);
            return engine;
        }

    private:
        std::mutex mutex_;
        std::unordered_map<jlong, std::shared_ptr<kap_vk::Engine>> engines_;
        jlong next_ = 1;
    };

    EngineRegistry &engineRegistry() {
        // Leaked intentionally so static destruction cannot race a late JNI call.
        // 有意泄漏：避免静态析构与迟到的 JNI 调用竞争。
        static EngineRegistry *registry = new EngineRegistry();
        return *registry;
    }

    std::shared_ptr<kap_vk::Engine> acquireEngine(jlong handle) {
        if (handle == 0) return nullptr;
        return engineRegistry().acquire(handle);
    }
} // namespace

static jboolean decompressZstdFile(
        JNIEnv *env, jint inputFd, jlong offset, jlong compressedLength,
        jint outputFd, jlong decodedLength, jlong prefixLength) {
    jclass threadClass = env->FindClass("java/lang/Thread");
    if (!threadClass) return JNI_FALSE;
    jmethodID current = env->GetStaticMethodID(threadClass, "currentThread", "()Ljava/lang/Thread;");
    jmethodID interrupted = env->GetMethodID(threadClass, "isInterrupted", "()Z");
    if (!current || !interrupted) return JNI_FALSE;
    jobject thread = env->CallStaticObjectMethod(threadClass, current);
    if (!thread || env->ExceptionCheck()) return JNI_FALSE;
    struct Cancellation {
        JNIEnv *env;
        jobject thread;
        jmethodID interrupted;
    } cancellation{env, thread, interrupted};
    const bool ok = kap::decodeZstdToFile(
            inputFd, offset, compressedLength, outputFd, decodedLength,
            [](void *value) {
                auto *state = static_cast<Cancellation *>(value);
                return state->env->CallBooleanMethod(state->thread, state->interrupted) == JNI_TRUE ||
                       state->env->ExceptionCheck();
            }, &cancellation, prefixLength);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zxhhyj_kap_android_KtxNative_nativeDecompressZstdToFile(
        JNIEnv *env, jobject, jint inputFd, jlong offset, jlong compressedLength,
        jint outputFd, jlong decodedLength) {
    return decompressZstdFile(env, inputFd, offset, compressedLength, outputFd, decodedLength, 0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zxhhyj_kap_android_KtxNative_nativeDecompressZstdPrefix(
        JNIEnv *env, jobject, jint inputFd, jlong offset, jlong compressedLength,
        jint outputFd, jlong decodedLength, jlong prefixLength) {
    if (prefixLength <= 0) return JNI_FALSE;
    return decompressZstdFile(env, inputFd, offset, compressedLength, outputFd,
                              decodedLength, prefixLength);
}

extern "C" JNIEXPORT void JNICALL
Java_com_zxhhyj_kap_android_KtxNative_nativePrewarm(JNIEnv *, jobject) {
    kap_vk::SharedDevice::prewarm();
}

extern "C" JNIEXPORT void JNICALL
Java_com_zxhhyj_kap_android_KtxNative_nativeSetNarrowTeardown(
        JNIEnv *, jobject, jlong handle, jboolean enabled) {
    auto engine = acquireEngine(handle);
    if (engine) engine->setNarrowTeardown(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zxhhyj_kap_android_KtxNative_nativeSetHueRows(
        JNIEnv *env, jobject, jlong handle, jfloatArray rows) {
    auto engine = acquireEngine(handle);
    if (!engine || !rows || env->GetArrayLength(rows) != 12) return JNI_FALSE;
    jfloat values[12];
    env->GetFloatArrayRegion(rows, 0, 12, values);
    if (env->ExceptionCheck()) return JNI_FALSE;
    return engine->setHueRows(values, 12) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zxhhyj_kap_android_KtxNative_nativeSetTextureArrayFile(
        JNIEnv *, jobject, jlong handle, jint format, jint width, jint height,
        jint layers, jint fd, jlong offset, jint length) {
    auto engine = acquireEngine(handle);
    if (!engine || width <= 0 || height <= 0 || layers <= 0 || fd < 0 || offset < 0 || length <= 0) {
        return JNI_FALSE;
    }
    return engine->setTextureArrayFile(format, width, height, layers, fd, offset, length)
            ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_zxhhyj_kap_android_KtxNative_nativeCreate(JNIEnv *, jobject) {
    auto *engine = kap_vk::Engine::create();
    if (!engine) return 0;
    return engineRegistry().add(engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_zxhhyj_kap_android_KtxNative_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    engineRegistry().remove(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zxhhyj_kap_android_KtxNative_nativeSetOutputSurface(
        JNIEnv *env,
        jobject,
        jlong handle,
        jobject surface,
        jint width,
        jint height) {
    auto engine = acquireEngine(handle);
    if (!engine || !surface) return JNI_FALSE;
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        KTX_JNI_LOGE("ANativeWindow_fromSurface failed");
        return JNI_FALSE;
    }
    const bool ok = engine->setOutputWindow(window, width, height);
    ANativeWindow_release(window); // Engine retains its own reference.
                                  // 引擎会持有独立引用。
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_zxhhyj_kap_android_KtxNative_nativeClearOutputSurface(
        JNIEnv *,
        jobject,
        jlong handle) {
    auto engine = acquireEngine(handle);
    if (engine) engine->clearOutputWindow();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zxhhyj_kap_android_KtxNative_nativeSetTextureArray(
        JNIEnv *env,
        jobject,
        jlong handle,
        jbyteArray data) {
    auto engine = acquireEngine(handle);
    if (!engine || !data) return JNI_FALSE;
    const jsize length = env->GetArrayLength(data);
    if (length <= 0) return JNI_FALSE;
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return JNI_FALSE;
    const bool ok = engine->setTextureArray(
            reinterpret_cast<const uint8_t *>(bytes), static_cast<size_t>(length));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zxhhyj_kap_android_KtxNative_nativeSetTextureArrayRaw(
        JNIEnv *env,
        jobject,
        jlong handle,
        jint format,
        jint width,
        jint height,
        jint layers,
        jbyteArray data) {
    auto engine = acquireEngine(handle);
    if (!engine || !data || width <= 0 || height <= 0 || layers <= 0) return JNI_FALSE;
    const jsize length = env->GetArrayLength(data);
    if (length <= 0) return JNI_FALSE;
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return JNI_FALSE;
    const bool ok = engine->setTextureArrayRaw(
            static_cast<uint32_t>(format),
            static_cast<uint32_t>(width),
            static_cast<uint32_t>(height),
            static_cast<uint32_t>(layers),
            reinterpret_cast<const uint8_t *>(bytes),
            static_cast<size_t>(length));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zxhhyj_kap_android_KtxNative_nativeSetTextureArrayZstd(
        JNIEnv *env,
        jobject,
        jlong handle,
        jint format,
        jint width,
        jint height,
        jint layers,
        jbyteArray compressed,
        jint uncompressedLength) {
    auto engine = acquireEngine(handle);
    if (!engine || !compressed || width <= 0 || height <= 0 || layers <= 0 ||
        uncompressedLength <= 0) {
        return JNI_FALSE;
    }
    const jsize compressedLength = env->GetArrayLength(compressed);
    if (compressedLength <= 0) return JNI_FALSE;
    jbyte *bytes = env->GetByteArrayElements(compressed, nullptr);
    if (!bytes) return JNI_FALSE;
    const bool ok = engine->setTextureArrayZstd(
            static_cast<uint32_t>(format),
            static_cast<uint32_t>(width),
            static_cast<uint32_t>(height),
            static_cast<uint32_t>(layers),
            reinterpret_cast<const uint8_t *>(bytes),
            static_cast<size_t>(compressedLength),
            static_cast<size_t>(uncompressedLength));
    env->ReleaseByteArrayElements(compressed, bytes, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_zxhhyj_kap_android_KtxNative_nativeDecompressZstd(
        JNIEnv *env,
        jobject,
        jbyteArray compressed,
        jint uncompressedLength) {
    if (!compressed || uncompressedLength <= 0) return nullptr;
    const jsize compressedLength = env->GetArrayLength(compressed);
    if (compressedLength <= 0) return nullptr;
    jbyteArray result = env->NewByteArray(uncompressedLength);
    if (!result) return nullptr;
    jbyte *raw = env->GetByteArrayElements(result, nullptr);
    if (!raw) return nullptr;
    jbyte *bytes = env->GetByteArrayElements(compressed, nullptr);
    if (!bytes) {
        env->ReleaseByteArrayElements(result, raw, JNI_ABORT);
        return nullptr;
    }
    // Decompress directly into the Java array; no native copy + SetByteArrayRegion.
    // 直接解压到 Java 数组，省掉 native 缓冲与 SetByteArrayRegion 的二次拷贝。
    const size_t decoded = ZSTD_decompress(
            raw, static_cast<size_t>(uncompressedLength),
            reinterpret_cast<const uint8_t *>(bytes),
            static_cast<size_t>(compressedLength));
    env->ReleaseByteArrayElements(compressed, bytes, JNI_ABORT);
    env->ReleaseByteArrayElements(result, raw, 0);
    if (ZSTD_isError(decoded) || decoded != static_cast<size_t>(uncompressedLength)) {
        KTX_JNI_LOGE("ZSTD_decompress failed: %s", ZSTD_getErrorName(decoded));
        return nullptr;
    }
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zxhhyj_kap_android_KtxNative_nativePresentFrame(
        JNIEnv *,
        jobject,
        jlong handle,
        jint layer) {
    auto engine = acquireEngine(handle);
    return engine && engine->presentFrame(static_cast<uint32_t>(layer)) ? JNI_TRUE
                                                                        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zxhhyj_kap_android_KtxNative_nativePresentFrameFile(
        JNIEnv *env,
        jobject,
        jlong handle,
        jint layer,
        jint fd,
        jlong offset,
        jint length) {
    auto engine = acquireEngine(handle);
    if (!engine) return JNI_FALSE;
    int ioError = 0;
    bool ok = false;
    if (fd < 0 || offset < 0 || length <= 0 || layer < 0) {
        ioError = EINVAL;
    } else {
        ok = engine->presentFrameFile(static_cast<uint32_t>(layer), fd, offset,
                                      static_cast<size_t>(length), &ioError);
    }
    if (ioError != 0) {
        char message[192];
        std::snprintf(message, sizeof(message), "KTX frame pread failed: %s (errno=%d)",
                      std::strerror(ioError), ioError);
        jclass errorClass = env->FindClass(ioError == EINTR
                ? "java/io/InterruptedIOException" : "java/io/IOException");
        if (errorClass) env->ThrowNew(errorClass, message);
        return JNI_FALSE;
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zxhhyj_kap_android_KtxNative_nativeReady(
        JNIEnv *,
        jobject,
        jlong handle) {
    auto engine = acquireEngine(handle);
    return engine && engine->ready() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zxhhyj_kap_android_KtxNative_nativePresentFrameBytes(
        JNIEnv *env, jobject, jlong handle, jint layer, jbyteArray bytes) {
    auto engine = acquireEngine(handle);
    if (!engine || !bytes || layer < 0) return JNI_FALSE;
    const auto length = static_cast<size_t>(env->GetArrayLength(bytes));
    jbyte *data = env->GetByteArrayElements(bytes, nullptr);
    if (!data) return JNI_FALSE;
    const bool ok = engine->presentFrameBytes(static_cast<uint32_t>(layer), data, length);
    env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
