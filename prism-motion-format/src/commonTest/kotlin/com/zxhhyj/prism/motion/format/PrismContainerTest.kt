package com.zxhhyj.prism.motion.format

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** v1 布局和严格边界测试。 / Tests the v1 layout and strict bounds. */
class PrismContainerTest {
    private val metadata = PrismMetadata(320, 180, 60, 30_000, 1_001)
    private val payload = byteArrayOf(0, -1, 1, -128) + "original-native-payload".encodeToByteArray()

    @Test
    fun deterministicOutputMatchesIndependentGoldenPrefix() {
        val bytes = sample()
        val expected = "505249534d00000001000000400000001b100000000000004000000000000000" +
            "40000000000000008000000000000000010000000000000001a05d6400000000" +
            "0100000040010000b40000003c00000030750000e90300000100000001000000" +
            "0000000000000000000000000000000000000000000000000000000000000000" +
            "010000000000000000100000000000001b0000000000000082fc13310d2d1665" +
            "574e444c2c02c2e6bc0ae5733cd95bd2b44b4042ac89ebf50000000000000000"
        assertEquals(expected, PrismSha256.hex(bytes.copyOf(PrismContainer.PREFIX_SIZE)))
        assertContentEquals(bytes, sample())
        assertEquals(64, PrismContainer.HEADER_SIZE)
        assertEquals(192, PrismContainer.PREFIX_SIZE)
        assertEquals(4096, PrismContainer.PAYLOAD_OFFSET)
        assertTrue(bytes.copyOfRange(192, 4096).all { it == 0.toByte() })
        assertContentEquals(payload, bytes.copyOfRange(4096, bytes.size))
    }

    @Test
    fun metadataAndPayloadRoundTripAcrossEveryEnum() {
        for (kind in PrismPayloadKind.entries) for (alpha in PrismAlphaMode.entries) {
            val originalMetadata = metadata.copy(alphaMode = alpha)
            val originalPayload = ByteArray(5000) { (it * 37).toByte() }
            val bytes = PrismContainer.write(originalMetadata, kind, originalPayload)
            val descriptor = PrismContainer.verify(bytes)
            assertEquals(originalMetadata, descriptor.metadata)
            assertEquals(kind, descriptor.kind)
            assertEquals(4096L, descriptor.payloadOffset)
            assertEquals(originalPayload.size.toLong(), descriptor.payloadLength)
            assertEquals(bytes.size.toLong(), descriptor.fileSize)
            assertContentEquals(PrismSha256.digest(originalPayload), descriptor.payloadSha256)
            assertEquals(PrismSha256.hex(PrismSha256.digest(originalPayload)), descriptor.payloadSha256Hex)
            assertContentEquals(originalPayload, PrismContainer.extractPayload(bytes, verifyPayload = true))
            assertEquals(descriptor, PrismContainer.readPrefix(bytes.copyOf(192), bytes.size.toLong()))
            assertEquals(descriptor.hashCode(), PrismContainer.read(bytes).hashCode())
        }
    }

    @Test
    fun preservesFullNativeMp4AndKtx2PayloadsWithoutInterpretation() {
        val mp4 = byteArrayOf(0, 0, 0, 24) + "ftypmp42".encodeToByteArray() + ByteArray(12) { it.toByte() }
        val ktx2 = byteArrayOf(-85, 0x4b, 0x54, 0x58, 0x20, 0x32, 0x30, -69, 13, 10, 26, 10) +
            ByteArray(100) { (255 - it).toByte() }
        for ((kind, native) in listOf(PrismPayloadKind.Vap to mp4, PrismPayloadKind.Ktx2 to ktx2)) {
            val before = native.copyOf()
            val bytes = PrismContainer.write(metadata, kind, native)
            assertContentEquals(before, native)
            assertContentEquals(before, PrismContainer.extractPayload(bytes, true))
        }
    }

    @Test
    fun prefixReaderNeverNeedsPayloadOrPaddingBytes() {
        val bytes = sample()
        val prefix = bytes.copyOf(192)
        assertEquals(PrismContainer.read(bytes), PrismContainer.readPrefix(prefix, bytes.size.toLong()))
        val withCorruptPadding = bytes.copyOf().also { it[192] = 1 }
        assertEquals(PrismContainer.read(bytes), PrismContainer.readPrefix(withCorruptPadding, bytes.size.toLong()))
        assertFailsWith<IllegalArgumentException> { PrismContainer.read(withCorruptPadding) }
        Bytes.putU64le(prefix, 16, Long.MAX_VALUE)
        Bytes.putU64le(prefix, 144, Long.MAX_VALUE - 4096L)
        rewriteCrc(prefix)
        assertEquals(Long.MAX_VALUE - 4096L, PrismContainer.readPrefix(prefix, Long.MAX_VALUE).payloadLength)
    }

    @Test
    fun rejectsLegacyMagicAndTruncatedMagic() {
        val bytes = sample()
        assertTrue(PrismContainer.isPrism(bytes))
        assertTrue(PrismContainer.isPrism(bytes.copyOf(8)))
        for (length in 0 until 8) assertFalse(PrismContainer.isPrism(bytes.copyOf(length)))
        for (index in 0 until 8) {
            val changed = bytes.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertFalse(PrismContainer.isPrism(changed))
            assertFailsWith<IllegalArgumentException> { PrismContainer.read(changed) }
        }
        val legacy = listOf(
            byteArrayOf(0, 0, 0, 24) + "ftypmp42".encodeToByteArray(),
            byteArrayOf(-85, 0x4b, 0x54, 0x58, 0x20, 0x32, 0x30, -69, 13, 10, 26, 10),
        )
        for (native in legacy) {
            val padded = native.copyOf(192)
            assertFalse(PrismContainer.isPrism(padded))
            assertFailsWith<IllegalArgumentException> { PrismContainer.readPrefix(padded, 5000) }
        }
    }

    @Test
    fun rejectsTruncatedPrefixesAndFullContainers() {
        val bytes = sample()
        for (length in 0 until 192) {
            assertFailsWith<IllegalArgumentException> {
                PrismContainer.readPrefix(bytes.copyOf(length), bytes.size.toLong())
            }
        }
        for (length in listOf(192, 4095, 4096, bytes.lastIndex)) {
            assertFailsWith<IllegalArgumentException> { PrismContainer.read(bytes.copyOf(length)) }
        }
        assertFailsWith<IllegalArgumentException> { PrismContainer.read(bytes + byteArrayOf(0)) }
        for (size in listOf(-1L, 0L, 192L, 4096L, bytes.size - 1L, bytes.size + 1L)) {
            assertFailsWith<IllegalArgumentException> { PrismContainer.readPrefix(bytes.copyOf(192), size) }
        }
    }

    @Test
    fun rejectsUnsupportedFixedFieldsWithOtherwiseValidCrc() {
        val invalidU32Fields = listOf(
            12 to 0, 12 to 128, 48 to 0, 48 to 2, 52 to 1,
            64 to 0, 64 to 2,
            68 to 0, 72 to 0, 76 to 0, 80 to 0, 84 to 0,
            68 to Int.MIN_VALUE, 72 to -1, 76 to -1, 80 to -1, 84 to -1,
            88 to 3, 88 to -1, 92 to 0, 92 to 2,
            128 to 0, 128 to 3,
        )
        for ((offset, value) in invalidU32Fields) {
            val bytes = sample()
            Bytes.putU32le(bytes, offset, value)
            rewriteCrc(bytes)
            assertFailsWith<IllegalArgumentException>("offset=$offset value=$value") { PrismContainer.read(bytes) }
        }
        for (offset in listOf(8, 9, 10, 11)) {
            val bytes = sample()
            bytes[offset] = 2
            rewriteCrc(bytes)
            assertFailsWith<IllegalArgumentException> { PrismContainer.read(bytes) }
        }
        for ((offset, value) in listOf(24 to 0L, 32 to 63L, 40 to 129L, 136 to 192L, 144 to 0L)) {
            val bytes = sample()
            Bytes.putU64le(bytes, offset, value)
            rewriteCrc(bytes)
            assertFailsWith<IllegalArgumentException> { PrismContainer.read(bytes) }
        }
    }

    @Test
    fun rejectsAllReservedBytesAndAlignmentPadding() {
        for (offset in (60 until 64) + (96 until 128) + (132 until 136) + (184 until 192)) {
            val bytes = sample()
            bytes[offset] = 1
            rewriteCrc(bytes)
            assertFailsWith<IllegalArgumentException>("reserved offset=$offset") { PrismContainer.read(bytes) }
        }
        for (offset in listOf(192, 193, 2048, 4095)) {
            val bytes = sample().also { it[offset] = 1 }
            assertFailsWith<IllegalArgumentException> { PrismContainer.read(bytes) }
            assertFailsWith<IllegalArgumentException> { PrismContainer.extractPayload(bytes) }
        }
    }

    @Test
    fun rejectsHighBitUnsignedFieldsAndOverflowingPayloadRanges() {
        for (offset in listOf(16, 24, 32, 40, 136, 144)) {
            val prefix = sample().copyOf(192)
            Bytes.putU64le(prefix, offset, Long.MIN_VALUE)
            rewriteCrc(prefix)
            val actualSize = if (offset == 16) Long.MIN_VALUE else sample().size.toLong()
            assertFailsWith<IllegalArgumentException> { PrismContainer.readPrefix(prefix, actualSize) }
        }
        val prefix = sample().copyOf(192)
        Bytes.putU64le(prefix, 16, Long.MAX_VALUE)
        Bytes.putU64le(prefix, 144, Long.MAX_VALUE)
        rewriteCrc(prefix)
        assertFailsWith<IllegalArgumentException> { PrismContainer.readPrefix(prefix, Long.MAX_VALUE) }
        val mismatch = sample()
        Bytes.putU64le(mismatch, 144, payload.size + 1L)
        rewriteCrc(mismatch)
        assertFailsWith<IllegalArgumentException> { PrismContainer.read(mismatch) }
    }

    @Test
    fun crcCoversMetadataIndexAndDigestAndIgnoresItsOwnField() {
        for (offset in listOf(68, 76, 88, 128, 144, 152, 183)) {
            val bytes = sample().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            val failure = assertFailsWith<IllegalArgumentException> { PrismContainer.readPrefix(bytes, bytes.size.toLong()) }
            assertTrue(failure.message.orEmpty().contains("CRC32"))
        }
        for (offset in 56..59) {
            val bytes = sample().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            assertFailsWith<IllegalArgumentException> { PrismContainer.read(bytes) }
        }
    }

    @Test
    fun payloadShaVerificationIsExplicitAndDetectsTampering() {
        val bytes = sample().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertEquals(metadata, PrismContainer.read(bytes).metadata)
        assertEquals(metadata, PrismContainer.readPrefix(bytes.copyOf(192), bytes.size.toLong()).metadata)
        assertFailsWith<IllegalArgumentException> { PrismContainer.verify(bytes) }
        assertFailsWith<IllegalArgumentException> { PrismContainer.read(bytes, verifyPayload = true) }
        assertFailsWith<IllegalArgumentException> { PrismContainer.extractPayload(bytes, verifyPayload = true) }
        val changedDigest = sample().also { it[152] = (it[152].toInt() xor 1).toByte() }
        rewriteCrc(changedDigest)
        PrismContainer.read(changedDigest)
        assertFailsWith<IllegalArgumentException> { PrismContainer.verify(changedDigest) }
    }

    @Test
    fun rejectsEmptyPayload() {
        assertFailsWith<IllegalArgumentException> { PrismContainer.write(metadata, PrismPayloadKind.Vap, byteArrayOf()) }
    }

    @Test
    fun rationalDurationUsesExactCheckedArithmetic() {
        assertEquals(2_002L, metadata.durationMs)
        assertEquals(33L, metadata.copy(frameCount = 1, fpsNumerator = 30, fpsDenominator = 1).durationMs)
        assertEquals(1_001L, metadata.copy(frameCount = 24, fpsNumerator = 24_000).durationMs)
        assertEquals(0L, metadata.copy(frameCount = 1, fpsNumerator = Int.MAX_VALUE, fpsDenominator = 1).durationMs)
        assertEquals(2_147_483_647_000L, metadata.copy(
            frameCount = Int.MAX_VALUE, fpsNumerator = Int.MAX_VALUE, fpsDenominator = Int.MAX_VALUE,
        ).durationMs)
        assertFailsWith<IllegalArgumentException> {
            metadata.copy(frameCount = Int.MAX_VALUE, fpsNumerator = 1, fpsDenominator = Int.MAX_VALUE)
        }
        val overflowPrefix = sample()
        Bytes.putU32le(overflowPrefix, 76, Int.MAX_VALUE)
        Bytes.putU32le(overflowPrefix, 80, 1)
        Bytes.putU32le(overflowPrefix, 84, Int.MAX_VALUE)
        rewriteCrc(overflowPrefix)
        assertFailsWith<IllegalArgumentException> { PrismContainer.read(overflowPrefix) }
    }

    @Test
    fun metadataConstructorRejectsNonpositiveAndUnsupportedFields() {
        for (value in listOf(0, -1, Int.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException> { metadata.copy(width = value) }
            assertFailsWith<IllegalArgumentException> { metadata.copy(height = value) }
            assertFailsWith<IllegalArgumentException> { metadata.copy(frameCount = value) }
            assertFailsWith<IllegalArgumentException> { metadata.copy(fpsNumerator = value) }
            assertFailsWith<IllegalArgumentException> { metadata.copy(fpsDenominator = value) }
        }
        assertFailsWith<IllegalArgumentException> { metadata.copy(schemaVersion = 2) }
        assertEquals(Int.MAX_VALUE, metadata.copy(width = Int.MAX_VALUE, height = Int.MAX_VALUE).width)
    }

    private fun sample(): ByteArray = PrismContainer.write(metadata, PrismPayloadKind.Vap, payload)

    private fun rewriteCrc(bytes: ByteArray) {
        var checksum = 0xffffffffL
        for (index in 0 until 192) {
            checksum = checksum xor (if (index in 56..59) 0L else (bytes[index].toLong() and 255L))
            repeat(8) {
                checksum = if (checksum and 1L == 1L) (checksum ushr 1) xor 0xedb88320L else checksum ushr 1
            }
        }
        Bytes.putU32le(bytes, 56, (checksum xor 0xffffffffL).toInt())
    }
}
