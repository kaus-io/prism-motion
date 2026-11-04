package com.zxhhyj.prism.motion

import com.zxhhyj.prism.motion.format.PrismAlphaMode
import com.zxhhyj.prism.motion.format.PrismContainer
import com.zxhhyj.prism.motion.format.PrismMetadata
import com.zxhhyj.prism.motion.format.PrismPayloadKind
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PrismJvmSourcesTest {
    private val metadata = PrismMetadata(2, 2, 1, 30, 1, PrismAlphaMode.Opaque)
    private val payload = byteArrayOf(1, 2, 3, 4)

    @Test
    fun fileAndMemoryReadersReturnOnlyThePayload() {
        val wrapped = PrismContainer.write(metadata, PrismPayloadKind.Ktx2, payload)
        val file = Files.createTempFile("prism-core-source-", ".prism").toFile()
        try {
            file.writeBytes(wrapped)
            for (source in listOf(PrismMotionSource.File(file.path), PrismMotionSource.Bytes(wrapped))) {
                val loaded = PrismJvmSources.payload(source)
                assertEquals(metadata, loaded.descriptor.metadata)
                assertEquals(PrismMotionEngineId.Kap, loaded.descriptor.kind.engineId)
                assertContentEquals(payload, loaded.bytes)
            }
        } finally {
            check(file.delete())
        }
    }

    @Test
    fun nativeExtensionAndNativeByteStreamsAreRejected() {
        val file = Files.createTempFile("prism-core-legacy-", ".ktx").toFile()
        try {
            file.writeBytes(PrismContainer.write(metadata, PrismPayloadKind.Ktx2, payload))
            assertFailsWith<IllegalArgumentException> { PrismJvmSources.inspect(PrismMotionSource.File(file.path)) }
            assertFailsWith<IllegalArgumentException> { PrismJvmSources.inspect(PrismMotionSource.Bytes(payload)) }
        } finally {
            check(file.delete())
        }
    }

    @Test
    fun payloadIntegrityIsCheckedBeforeSoftwareDecode() {
        val wrapped = PrismContainer.write(metadata, PrismPayloadKind.Vap, payload)
        wrapped[wrapped.lastIndex] = 99
        assertFailsWith<IllegalArgumentException> { PrismJvmSources.payload(PrismMotionSource.Bytes(wrapped)) }
    }

    @Test
    fun fractionalFpsAndDisagreeingNativeMetadataFailExplicitly() {
        assertFailsWith<IllegalArgumentException> { PrismMetadata(2, 2, 1, 30_000, 1001).integerPlaybackFps() }
        metadata.requireNativeMetadata(2, 2, 1, 30)
        assertFailsWith<IllegalArgumentException> { metadata.requireNativeMetadata(3, 2, 1, 30) }
    }
}
