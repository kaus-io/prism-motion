package com.zxhhyj.prism.motion.clinative

/**
 * Windows 构建暂时没有接入 stb_image / OpenH264：
 * 交叉编译这两个库需要 mingw-w64 工具链，本机没有。
 * 探测、校验、`pack kap --format astc-*` 不受影响，仍可用。
 */
internal actual object ImageDecoder {
    actual fun decodePng(bytes: ByteArray): Frame =
        error("Windows 构建暂不支持 PNG 解码：请用 Linux/macOS 版，或补 mingw-w64 交叉编译")
}

internal actual object H264Encoder {
    actual fun encode(width: Int, height: Int, fps: Int, bitrateKbps: Int, frames: List<Frame>): H264Stream =
        error("Windows 构建暂不支持 H.264 编码：请用 Linux/macOS 版，或补 mingw-w64 交叉编译")
}
