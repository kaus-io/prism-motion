#include "vap_ahb_probe.h"
#include "vap_vk_engine.h"
#include "vap_vk_shared_device.h"

#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <utility>
#include <vector>

#define LOG_TAG "VapVkJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

    /**
     * Process-wide registry mapping opaque Java handles to live engines.
     *
     * The old JNI contract passed a raw `Engine*` back to Java. Compose disposes the
     * player and the embedded Surface independently, so `nativeClearOutputSurface`
     * (or any other call) can arrive after `nativeDestroy`; dereferencing the freed
     * pointer crashed inside `QueueGuard`. Handles are now monotonic ids and every
     * entry point holds a shared reference for the duration of the call, making late
     * calls no-ops and racing destroy with an in-flight present safe.
     *
     * 进程级句柄表：Java 拿到的不再是裸 `Engine*`。Compose 的播放器与内嵌 Surface
     * 生命周期相互独立，`nativeDestroy` 之后仍可能收到 `nativeClearOutputSurface`
     * 等调用；解引用已释放指针会在 `QueueGuard` 内崩溃。现在句柄是单调递增 id，
     * 每个入口在调用期间持有 shared 引用，销毁后的迟到调用安全地变成空操作，
     * 销毁与在途 present 的竞争也不会再 UAF。
     */
    class EngineRegistry {
    public:
        jlong add(vap_vk::Engine *engine) {
            std::lock_guard<std::mutex> lock(mutex_);
            jlong handle = next_++;
            while (handle == 0 || engines_.find(handle) != engines_.end()) {
                handle = next_++;
            }
            engines_.emplace(handle, std::shared_ptr<vap_vk::Engine>(
                    engine, [](vap_vk::Engine *value) { value->destroy(); }));
            return handle;
        }

        std::shared_ptr<vap_vk::Engine> acquire(jlong handle) {
            std::lock_guard<std::mutex> lock(mutex_);
            auto it = engines_.find(handle);
            return it == engines_.end() ? nullptr : it->second;
        }

        std::shared_ptr<vap_vk::Engine> remove(jlong handle) {
            std::lock_guard<std::mutex> lock(mutex_);
            auto it = engines_.find(handle);
            if (it == engines_.end()) return nullptr;
            std::shared_ptr<vap_vk::Engine> engine = std::move(it->second);
            engines_.erase(it);
            return engine;
        }

    private:
        std::mutex mutex_;
        std::unordered_map<jlong, std::shared_ptr<vap_vk::Engine>> engines_;
        jlong next_ = 1;
    };

    EngineRegistry &engineRegistry() {
        // Intentionally leaked so static destruction order cannot race a late JNI call.
        // 有意泄漏：避免静态析构顺序与迟到的 JNI 调用竞争。
        static EngineRegistry *registry = new EngineRegistry();
        return *registry;
    }

    std::shared_ptr<vap_vk::Engine> acquireEngine(jlong handle) {
        if (handle == 0) return nullptr;
        return engineRegistry().acquire(handle);
    }

    /**
     * Convert a pixel rect to normalized UVs, inset by 1 texel when the rect is large
     * enough so LINEAR / YCbCr 4:2:0 chroma does not sample encoder padding or the
     * RGB–alpha gap (visible as a 1px black fringe on the right and bottom).
     *
     * 将像素矩形转为归一化 UV；矩形足够大时内缩 1 texel，避免 LINEAR / YCbCr 4:2:0
     * 色度采样到编码器补边或 RGB–Alpha 间隙（表现为右/下 1px 黑边）。
     */
    vap_vk::RectUv pixelRectToUv(jint x, jint y, jint w, jint h, float vw, float vh) {
        if (w <= 0 || h <= 0) {
            return {0.f, 0.f, 0.f, 0.f};
        }
        const float inset = (w > 2 && h > 2) ? 1.f : 0.f;
        const float x0 = (static_cast<float>(x) + inset) / vw;
        const float y0 = (static_cast<float>(y) + inset) / vh;
        const float x1 = (static_cast<float>(x + w) - inset) / vw;
        const float y1 = (static_cast<float>(y + h) - inset) / vh;
        return {x0, y0, std::max(x0, x1), std::max(y0, y1)};
    }

} // namespace

/**
 * Create a native compositor and convert packed pixel rectangles to normalized UVs.
 * 创建原生合成器，并将打包像素矩形转换为归一化 UV。
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativeCreate(
        JNIEnv *,
        jobject,
        jint videoW,
        jint videoH,
        jint alphaX,
        jint alphaY,
        jint alphaW,
        jint alphaH,
        jint rgbX,
        jint rgbY,
        jint rgbW,
        jint rgbH) {
    const float vw = static_cast<float>(std::max(1, static_cast<int>(videoW)));
    const float vh = static_cast<float>(std::max(1, static_cast<int>(videoH)));
    vap_vk::CreateParams params{};
    params.videoW = videoW;
    params.videoH = videoH;
    params.alphaUv = pixelRectToUv(alphaX, alphaY, alphaW, alphaH, vw, vh);
    params.rgbUv = pixelRectToUv(rgbX, rgbY, rgbW, rgbH, vw, vh);
    params.hasAlpha = alphaW > 0 && alphaH > 0;
    auto *engine = vap_vk::Engine::create(params);
    if (!engine) return 0;
    return engineRegistry().add(engine);
}

/**
 * Destroy the native compositor identified by an opaque Java handle.
 * 销毁由 Java 不透明句柄标识的原生合成器。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    // Drop the registry's reference. If another thread is inside a JNI call the
    // engine is destroyed as soon as that call returns; later handles are invalid
    // but harmless because every entry point resolves through the registry.
    // 移除句柄表引用。若其他线程正在 JNI 调用中，引擎会在该调用返回后销毁；
    // 之后传入的旧句柄不再命中，所有入口都会安全返回。
    engineRegistry().remove(handle);
}

/**
 * Bind a Java Surface as the engine output while balancing the JNI window reference.
 * 将 Java Surface 绑定为引擎输出，并配平 JNI 获取的窗口引用。
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativeSetOutputSurface(
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
        LOGE("ANativeWindow_fromSurface failed");
        return JNI_FALSE;
    }
    const bool ok = engine->setOutputWindow(window, width, height);
    ANativeWindow_release(window); // setOutputWindow retains its own reference.
                                   // setOutputWindow 会持有独立引用。
    return ok ? JNI_TRUE : JNI_FALSE;
}

/**
 * Detach and release the engine's current output Surface.
 * 解除并释放引擎当前的输出 Surface。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativeClearOutputSurface(
        JNIEnv *,
        jobject,
        jlong handle) {
    auto engine = acquireEngine(handle);
    if (engine) engine->clearOutputWindow();
}

/**
 * Convert Java packed ARGB pixels to RGBA and upload them as the opening texture.
 * 将 Java 打包 ARGB 像素转为 RGBA，并上传为 opening 纹理。
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativeSetOpeningPixels(
        JNIEnv *env,
        jobject,
        jlong handle,
        jintArray pixels,
        jint width,
        jint height) {
    auto engine = acquireEngine(handle);
    if (!engine || !pixels || width <= 0 || height <= 0) return JNI_FALSE;
    const int64_t expected = static_cast<int64_t>(width) * static_cast<int64_t>(height);
    const jsize len = env->GetArrayLength(pixels);
    if (expected <= 0 || len != static_cast<jsize>(expected)) return JNI_FALSE;
    jint *src = env->GetIntArrayElements(pixels, nullptr);
    if (!src) return JNI_FALSE;
    std::vector<uint32_t> rgba(static_cast<size_t>(len));
    for (jsize i = 0; i < len; i++) {
        const uint32_t argb = static_cast<uint32_t>(src[i]);
        const uint32_t r = (argb >> 16) & 0xffu;
        const uint32_t g = (argb >> 8) & 0xffu;
        const uint32_t b = argb & 0xffu;
        const uint32_t a = (argb >> 24) & 0xffu;
        rgba[static_cast<size_t>(i)] = r | (g << 8) | (b << 16) | (a << 24);
    }
    env->ReleaseIntArrayElements(pixels, src, JNI_ABORT);
    return engine->setOpeningPixels(rgba.data(), static_cast<int>(width), static_cast<int>(height))
           ? JNI_TRUE : JNI_FALSE;
}

/**
 * Present the uploaded opening texture through the immediate Vulkan path.
 * 通过 Vulkan 即时路径呈现已上传的 opening 纹理。
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativePresentOpening(
        JNIEnv *,
        jobject,
        jlong handle) {
    auto engine = acquireEngine(handle);
    return engine ? static_cast<jint>(engine->presentOpening()) : 0;
}

/**
 * Upload a KTX2 ASTC/ETC texture array. The output Surface must be bound first.
 * 上传 KTX2 ASTC/ETC 纹理数组；输出 Surface 必须已绑定。
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativeSetTextureArray(
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

/**
 * Present one layer of the uploaded texture array.
 * 呈现已上传纹理数组的指定 layer。
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativePresentSequenceFrame(
        JNIEnv *,
        jobject,
        jlong handle,
        jint layer) {
    auto engine = acquireEngine(handle);
    return engine && engine->presentSequenceFrame(static_cast<uint32_t>(layer))
           ? JNI_TRUE
           : JNI_FALSE;
}

/**
 * Stream one raw ASTC layer into the sequence array and present it.
 * 流式上传单层原始 ASTC 到纹理数组并呈现。
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativePresentSequenceFrameData(
        JNIEnv *env,
        jobject,
        jlong handle,
        jint layer,
        jbyteArray data) {
    auto engine = acquireEngine(handle);
    if (!engine || !data) return JNI_FALSE;
    const jsize length = env->GetArrayLength(data);
    if (length <= 0) return JNI_FALSE;
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return JNI_FALSE;
    const bool ok = engine->presentSequenceFrameData(
            static_cast<uint32_t>(layer),
            reinterpret_cast<const uint8_t *>(bytes),
            static_cast<size_t>(length));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/**
 * Present one Java HardwareBuffer through the immediate Vulkan path.
 * 通过 Vulkan 即时路径呈现一个 Java HardwareBuffer。
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativePresentHardwareBuffer(
        JNIEnv *env,
        jobject,
        jlong handle,
        jobject hardwareBuffer,
        jlong bufferId) {
    auto engine = acquireEngine(handle);
    if (!engine || !hardwareBuffer) return JNI_FALSE;
    AHardwareBuffer *ahb = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (!ahb) {
        LOGE("AHardwareBuffer_fromHardwareBuffer failed");
        return JNI_FALSE;
    }
    // Do not release the borrowed result here: some OEM implementations can UAF-crash if
    // ImageReader/MediaCodec still touches the queue. The engine acquires a separate GPU-lifetime ref.
    // 此处不要释放借用结果：部分 OEM 在 ImageReader/MediaCodec 仍访问队列时会触发 UAF；
    // 引擎会另行获取一份覆盖 GPU 使用期的引用。
    return engine->presentHardwareBuffer(ahb, static_cast<uint64_t>(bufferId))
                   ? JNI_TRUE
                   : JNI_FALSE;
}

/**
 * Prepare one Java HardwareBuffer for a later shared batch submission.
 * 为后续共享批量提交准备一个 Java HardwareBuffer。
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativePreparePresent(
        JNIEnv *env,
        jobject,
        jlong handle,
        jobject hardwareBuffer,
        jlong bufferId) {
    auto engine = acquireEngine(handle);
    if (!engine || !hardwareBuffer) return JNI_FALSE;
    AHardwareBuffer *ahb = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (!ahb) {
        LOGE("AHardwareBuffer_fromHardwareBuffer failed");
        return JNI_FALSE;
    }
    // Apply the same borrowed-handle lifetime rule as nativePresentHardwareBuffer.
    // 与 nativePresentHardwareBuffer 遵循相同的借用句柄生命周期规则。
    return static_cast<jint>(engine->preparePresentHardwareBuffer(ahb, static_cast<uint64_t>(bufferId)));
}

/**
 * Submit non-null engine handles as one prepared Vulkan batch. Only the first [count] entries
 * of [handles] are read; [handles] may be a longer caller-owned buffer.
 * 将非空引擎句柄作为一个已准备的 Vulkan 批次提交。只读取 [handles] 的前 [count] 项；
 * [handles] 可以是调用方所有、更长的缓冲。
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativeSubmitBatch(JNIEnv *env, jobject, jlongArray handles,
                                                     jint count) {
    if (!handles) return JNI_TRUE;
    jsize n = env->GetArrayLength(handles);
    if (count >= 0 && count < n) n = count;
    if (n <= 0) return JNI_TRUE;
    if (n > 64) n = 64;
    jlong raw[64];
    env->GetLongArrayRegion(handles, 0, n, raw);
    // Hold a shared reference for every participating engine until the batch submit
    // returns, so a concurrent nativeDestroy cannot free one mid-submit.
    // 每个参与引擎在批量提交返回前都持有 shared 引用，避免并发 nativeDestroy
    // 在提交途中释放引擎。
    std::vector<std::shared_ptr<vap_vk::Engine>> keep;
    keep.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; i++) {
        auto engine = acquireEngine(raw[i]);
        if (engine) keep.push_back(std::move(engine));
    }
    std::vector<vap_vk::Engine *> engines;
    engines.reserve(keep.size());
    for (const auto &engine : keep) engines.push_back(engine.get());
    return vap_vk::Engine::submitPreparedBatch(engines.data(),
                                               static_cast<int>(engines.size()))
                   ? JNI_TRUE
                   : JNI_FALSE;
}

/**
 * Retire batch-backed frame slots without blocking on unfinished fences.
 * 在不等待未完成栅栏的前提下回收批次帧槽位。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativeRetireSignaled(JNIEnv *, jobject, jlong handle) {
    auto engine = acquireEngine(handle);
    if (engine) engine->retireSignaledBatches();
}

/**
 * Report whether the native engine has a usable device and swapchain.
 * 报告原生引擎是否具有可用的设备与交换链。
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativeReady(JNIEnv *, jobject, jlong handle) {
    auto engine = acquireEngine(handle);
    return (engine && engine->ready()) ? JNI_TRUE : JNI_FALSE;
}

/**
 * Run AHB diagnostics and return a report prefixed with its failure count.
 * 运行 AHB 诊断并返回以失败数开头的报告。
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativeProbeAhb(JNIEnv *env, jobject, jstring videoPath) {
    std::vector<char> buf(16 * 1024);
    const char *path = videoPath ? env->GetStringUTFChars(videoPath, nullptr) : nullptr;
    const int fails = vap_ahb_probe::run(buf.data(), buf.size(), path ? path : "");
    if (path) env->ReleaseStringUTFChars(videoPath, path);
    // Prefix the failure count so adb can grep it without parsing the full report.
    // 将失败数置于首行，便于 adb 无需解析完整报告即可检索。
    char headed[16 * 1024 + 64];
    snprintf(headed, sizeof(headed), "fails=%d\n%s", fails, buf.data());
    return env->NewStringUTF(headed);
}

/**
 * Pin a Java HardwareBuffer wrapper and return its native pointer for B0 diagnostics.
 * The caller owns the acquired reference and must release it through nativeProbeAhbRelease;
 * pointer equality among held references denotes the same underlying buffer because a
 * referenced address cannot be recycled.
 * 为 B0 诊断固定 Java HardwareBuffer 包装并返回原生指针。调用方拥有所获取的引用，
 * 必须通过 nativeProbeAhbRelease 释放；持有期间地址不会复用，因此指针相等表示同一底层缓冲区。
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativeProbeAhbAcquire(JNIEnv *env, jobject, jobject buffer) {
    AHardwareBuffer *ahb = AHardwareBuffer_fromHardwareBuffer(env, buffer);
    if (ahb) AHardwareBuffer_acquire(ahb);
    return reinterpret_cast<jlong>(ahb);
}

/**
 * Release a native AHB reference returned by nativeProbeAhbAcquire.
 * 释放 nativeProbeAhbAcquire 返回的原生 AHB 引用。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativeProbeAhbRelease(JNIEnv *, jobject, jlong ptr) {
    if (ptr) AHardwareBuffer_release(reinterpret_cast<AHardwareBuffer *>(ptr));
}

extern "C" JNIEXPORT void JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativePrewarmSharedDevice(JNIEnv *, jobject) {
    vap_vk::SharedDevice::prewarm();
}

extern "C" JNIEXPORT void JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativeResetImportPool(JNIEnv *, jobject, jlong handle) {
    auto engine = acquireEngine(handle);
    if (engine) engine->resetImportPool();
}

extern "C" JNIEXPORT void JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativeReleaseOpeningResources(JNIEnv *, jobject, jlong handle) {
    auto engine = acquireEngine(handle);
    if (engine) engine->releaseOpeningResources();
}

extern "C" JNIEXPORT void JNICALL
Java_com_zxhhyj_vap_vk_VapVkNative_nativeCancelPrepared(JNIEnv *, jobject, jlong handle) {
    auto engine = acquireEngine(handle);
    if (engine) engine->cancelPreparedPresent();
}
