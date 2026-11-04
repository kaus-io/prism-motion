package com.zxhhyj.prism.motion

import com.zxhhyj.prism.motion.format.PrismAssetDescriptor
import com.zxhhyj.prism.motion.format.PrismContainer
import com.zxhhyj.prism.motion.format.PrismPayloadKind
import com.zxhhyj.prism.motion.format.PrismSha256
import java.io.RandomAccessFile

/** JVM 的只读容器入口；文件只读头部或指定载荷区域。 / Read-only JVM container access using headers or a bounded payload region. */
public object PrismJvmSources {
    /** 只接受 .prism 或带正确魔数的内存来源，再按载荷分流。 / Accepts only Prism files or memory magic and dispatches by payload kind. */
    public fun supports(source: PrismMotionSource, kind: PrismPayloadKind): Boolean = when (source) {
        is PrismMotionSource.Asset -> false
        is PrismMotionSource.File -> PrismMotionFormat.ofFileName(source.path) == PrismMotionFormat.Prism && inspect(source).kind == kind
        is PrismMotionSource.Bytes -> PrismContainer.isPrism(source.bytes) && inspect(source).kind == kind
    }

    /** 只读固定前缀以选择引擎；拒绝旧资源后缀。 / Reads only the fixed prefix for dispatch and rejects legacy resource suffixes. */
    public fun inspect(source: PrismMotionSource): PrismAssetDescriptor = when (source) {
        is PrismMotionSource.Asset -> error("JVM cannot open Android assets: ${source.path}")
        is PrismMotionSource.File -> {
            require(PrismMotionFormat.ofFileName(source.path) == PrismMotionFormat.Prism) { "Only .prism resources are supported" }
            RandomAccessFile(source.path, "r").use { file ->
                val prefix = ByteArray(PrismContainer.PREFIX_SIZE)
                file.readFully(prefix)
                PrismContainer.readPrefix(prefix, file.length())
            }
        }
        is PrismMotionSource.Bytes -> PrismContainer.read(source.bytes)
    }

    /** 软件解码仅物化载荷，校验摘要，不读取或提取整个外层文件。 / Materializes and hashes only the payload for software decoding, without extracting an outer file. */
    public fun payload(source: PrismMotionSource): PrismPayloadData {
        val descriptor = inspect(source)
        require(descriptor.payloadLength <= Int.MAX_VALUE) { "JVM software decoding payload exceeds its byte-array limit" }
        val bytes = when (source) {
            is PrismMotionSource.Asset -> error("JVM cannot open Android assets")
            is PrismMotionSource.File -> RandomAccessFile(source.path, "r").use { file ->
                require(file.length() == descriptor.fileSize) { "Prism file changed while loading" }
                file.seek(descriptor.payloadOffset)
                ByteArray(descriptor.payloadLength.toInt()).also(file::readFully)
            }
            is PrismMotionSource.Bytes -> source.bytes.copyOfRange(descriptor.payloadOffset.toInt(), descriptor.fileSize.toInt())
        }
        require(PrismSha256.digest(bytes).contentEquals(descriptor.payloadSha256)) { "Prism payload checksum mismatch" }
        return PrismPayloadData(descriptor, bytes)
    }
}
