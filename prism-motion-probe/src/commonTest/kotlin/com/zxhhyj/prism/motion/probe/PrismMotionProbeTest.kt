package com.zxhhyj.prism.motion.probe

import com.zxhhyj.prism.motion.PrismMotionFormat
import com.zxhhyj.prism.motion.format.Bytes
import com.zxhhyj.prism.motion.format.Mp4
import com.zxhhyj.prism.motion.format.PrismAlphaMode
import com.zxhhyj.prism.motion.format.PrismContainer
import com.zxhhyj.prism.motion.format.PrismMetadata
import com.zxhhyj.prism.motion.format.PrismPayloadKind
import com.zxhhyj.prism.motion.ktx.Ktx
import com.zxhhyj.prism.motion.ktx.KtxFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 只接受 .prism，并核对完整原生载荷。 / Accepts only .prism and cross-checks complete native payloads. */
class PrismMotionProbeTest {
    private val vapMetadata = PrismMetadata(100, 200, 60, 30, 1, PrismAlphaMode.Premultiplied)
    private val ktxMetadata = PrismMetadata(8, 4, 2, 24, 1, PrismAlphaMode.Straight)

    @Test
    fun readsVapOuterMetadataAndPreservesNativePayload() {
        val native = vapFile()
        val bytes = PrismContainer.write(vapMetadata, PrismPayloadKind.Vap, native)
        val result = PrismMotionProbe.probe(bytes, "clip.prism")
        assertEquals(PrismMotionFormat.Prism, result.format)
        assertEquals(PrismPayloadKind.Vap, result.payloadKind)
        assertEquals(100, result.width)
        assertEquals(200, result.height)
        assertEquals(60, result.frameCount)
        assertEquals(30, result.fps)
        assertEquals(30, result.fpsNumerator)
        assertEquals(1, result.fpsDenominator)
        assertEquals(2_000L, result.durationMs)
        assertEquals(true, result.hasAlpha)
        assertEquals(PrismAlphaMode.Premultiplied, result.alphaMode)
        assertTrue(result.ok, result.issues.toString())
        assertContentEquals(native, PrismContainer.extractPayload(bytes, true))
    }

    @Test
    fun readsKtxOuterMetadataAndPreservesNativePayload() {
        val native = ktxFile()
        val bytes = PrismContainer.write(ktxMetadata, PrismPayloadKind.Ktx2, native)
        val result = PrismMotionProbe.probe(bytes, "clip.PRISM")
        assertEquals(PrismMotionFormat.Prism, result.format)
        assertEquals(PrismPayloadKind.Ktx2, result.payloadKind)
        assertEquals(8, result.width)
        assertEquals(4, result.height)
        assertEquals(2, result.frameCount)
        assertEquals(24, result.fps)
        assertEquals(83L, result.durationMs)
        assertTrue(result.ok, result.issues.toString())
        assertContentEquals(native, PrismContainer.extractPayload(bytes, true))
    }

    @Test
    fun rejectsAllOldExternalFormatsAndDoesNotInferFormatFromSuffix() {
        for ((native, fileName) in listOf(vapFile() to "clip.mp4", ktxFile() to "clip.ktx", ktxFile() to "clip.ktx2")) {
            val result = PrismMotionProbe.probe(native, fileName)
            assertNull(result.format)
            assertFalse(result.ok)
            assertTrue(result.issues.any { it.code == "obsolete-format" })
            assertTrue(result.issues.any { it.code == "obsolete-container" })
        }
        val misnamedNative = PrismMotionProbe.probe(vapFile(), "clip.prism")
        assertNull(misnamedNative.format)
        assertFalse(misnamedNative.ok)
        val misnamedPrism = PrismMotionProbe.probe(PrismContainer.write(ktxMetadata, PrismPayloadKind.Ktx2, ktxFile()), "clip.mp4")
        assertEquals(PrismMotionFormat.Prism, misnamedPrism.format)
        assertFalse(misnamedPrism.ok)
        assertTrue(misnamedPrism.issues.any { it.code == "obsolete-format" })
    }

    @Test
    fun unknownContainerIsAnErrorEvenWithPrismSuffix() {
        val result = PrismMotionProbe.probe("not an animation".encodeToByteArray(), "a.prism")
        assertNull(result.format)
        assertFalse(result.ok)
        assertTrue(result.issues.any { it.code == "unknown-container" })
    }

    @Test
    fun rejectsNativeKindMismatchInBothDirections() {
        for ((kind, native) in listOf(PrismPayloadKind.Vap to ktxFile(), PrismPayloadKind.Ktx2 to vapFile())) {
            val result = PrismMotionProbe.probe(PrismContainer.write(ktxMetadata, kind, native), "a.prism")
            assertEquals(kind, result.payloadKind)
            assertFalse(result.ok)
            assertTrue(result.issues.any { it.code == "payload-kind-mismatch" })
        }
    }

    @Test
    fun reportsInnerMetadataMismatchWithoutOverridingOuterValues() {
        for (outer in listOf(
            ktxMetadata.copy(width = 9), ktxMetadata.copy(height = 5), ktxMetadata.copy(frameCount = 3),
            ktxMetadata.copy(fpsNumerator = 25), ktxMetadata.copy(alphaMode = PrismAlphaMode.Opaque),
            ktxMetadata.copy(alphaMode = PrismAlphaMode.Premultiplied),
        )) {
            val result = PrismMotionProbe.probe(PrismContainer.write(outer, PrismPayloadKind.Ktx2, ktxFile()), "a.prism")
            assertEquals(outer.width, result.width)
            assertEquals(outer.height, result.height)
            assertEquals(outer.frameCount, result.frameCount)
            assertEquals(outer.durationMs, result.durationMs)
            assertEquals(outer.alphaMode, result.alphaMode)
            assertFalse(result.ok)
            assertTrue(result.issues.any { it.code == "metadata-mismatch" }, result.issues.toString())
        }
        val vap = PrismMotionProbe.probe(PrismContainer.write(vapMetadata.copy(width = 99), PrismPayloadKind.Vap, vapFile()), "a.prism")
        assertFalse(vap.ok)
        assertEquals(99, vap.width)
        assertTrue(vap.issues.any { it.code == "metadata-mismatch" })
    }

    @Test
    fun retainsRationalFpsWhenNativeTimingIsAbsentAndRejectsContradictoryTiming() {
        val outer = ktxMetadata.copy(fpsNumerator = 30_000, fpsDenominator = 1_001)
        val result = PrismMotionProbe.probe(PrismContainer.write(outer, PrismPayloadKind.Ktx2, ktxFile(fps = null)), "a.prism")
        assertNull(result.fps)
        assertEquals(30_000, result.fpsNumerator)
        assertEquals(1_001, result.fpsDenominator)
        assertEquals(66L, result.durationMs)
        assertTrue(result.ok, result.issues.toString())
        val mismatch = PrismMotionProbe.probe(PrismContainer.write(outer, PrismPayloadKind.Ktx2, ktxFile(fps = 30)), "a.prism")
        assertFalse(mismatch.ok)
        assertTrue(mismatch.issues.any { it.code == "metadata-mismatch" })
    }

    @Test
    fun verifiesCrcAndShaAndNeverFallsBackToNativeParsing() {
        val bytes = PrismContainer.write(ktxMetadata, PrismPayloadKind.Ktx2, ktxFile())
        for (offset in listOf(68, 152, bytes.lastIndex)) {
            val altered = bytes.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            val result = PrismMotionProbe.probe(altered, "a.prism")
            assertEquals(PrismMotionFormat.Prism, result.format)
            assertNull(result.width)
            assertNull(result.payloadKind)
            assertFalse(result.ok)
            assertTrue(result.issues.any { it.code == "bad-prism" })
        }
        for (size in listOf(8, 191, 192, 4096, bytes.lastIndex)) {
            val result = PrismMotionProbe.probe(bytes.copyOf(size), "a.prism")
            assertFalse(result.ok)
            assertTrue(result.issues.any { it.code == "bad-prism" })
        }
    }

    @Test
    fun rejectsMalformedNativeContainersWithValidOuterChecksums() {
        val native = ktxFile()
        for ((offset, value) in listOf(32 to 0L, 80 to Long.MIN_VALUE, 88 to Long.MAX_VALUE)) {
            val malformed = native.copyOf()
            if (offset == 32) Bytes.putU32le(malformed, offset, value.toInt()) else Bytes.putU64le(malformed, offset, value)
            val result = PrismMotionProbe.probe(PrismContainer.write(ktxMetadata, PrismPayloadKind.Ktx2, malformed), "a.prism")
            assertFalse(result.ok)
            assertTrue(result.issues.any { it.code == "bad-ktx" })
        }
        val plainMp4 = Mp4.box("ftyp", "isom".encodeToByteArray()) + Mp4.box("moov", byteArrayOf())
        val result = PrismMotionProbe.probe(PrismContainer.write(vapMetadata, PrismPayloadKind.Vap, plainMp4), "a.prism")
        assertFalse(result.ok)
        assertTrue(result.issues.any { it.code == "bad-mp4" })
    }

    @Test
    fun checksOpaqueAndPremultipliedRgbaMetadataAgainstNativePixels() {
        for (alpha in PrismAlphaMode.entries) {
            val native = ktxFile(opaque = alpha == PrismAlphaMode.Opaque, premultiplied = alpha == PrismAlphaMode.Premultiplied)
            val result = PrismMotionProbe.probe(PrismContainer.write(ktxMetadata.copy(alphaMode = alpha), PrismPayloadKind.Ktx2, native), "a.prism")
            assertTrue(result.ok, result.issues.toString())
            assertEquals(alpha != PrismAlphaMode.Opaque, result.hasAlpha)
        }
    }

    private fun vapFile(): ByteArray {
        val json = """{"info":{"v":3,"f":60,"w":100,"h":200,"videoW":100,"videoH":400,"fps":30,"hasAlpha":true}}"""
        val tkhd = ByteArray(84)
        Bytes.putU32be(tkhd, 76, 100 * 65_536)
        Bytes.putU32be(tkhd, 80, 400 * 65_536)
        return Mp4.box("ftyp", "isom".encodeToByteArray()) +
            Mp4.box("moov", Mp4.box("trak", Mp4.box("tkhd", tkhd))) + Mp4.box("vapc", json.encodeToByteArray())
    }

    private fun ktxFile(fps: Int? = 24, opaque: Boolean = false, premultiplied: Boolean = false): ByteArray {
        val layer = ByteArray(8 * 4 * 4) { if (it % 4 == 3) (if (opaque) 255 else 128).toByte() else 64 }
        val values = linkedMapOf("anim.premultiplied" to premultiplied.toString())
        if (fps != null) values["anim.fps"] = fps.toString()
        return Ktx.write(KtxFormat.RGBA8, 8, 4, listOf(layer, layer.copyOf()), values)
    }
}
