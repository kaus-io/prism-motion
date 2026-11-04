package com.zxhhyj.prism.motion.clinative

import com.zxhhyj.prism.motion.format.PrismAlphaMode
import com.zxhhyj.prism.motion.format.PrismContainer
import com.zxhhyj.prism.motion.format.PrismMetadata
import com.zxhhyj.prism.motion.format.PrismPayloadKind
import com.zxhhyj.prism.motion.ktx.Ktx
import com.zxhhyj.prism.motion.ktx.KtxFormat
import com.zxhhyj.prism.motion.probe.PrismMotionProbe
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 外部格式、探测 JSON 与 VAP 像素语义契约。 / Contracts for external formats, probe JSON and VAP pixel semantics. */
class PrismCliContractTest {
    @Test
    fun rejectsOldOutputNamesBeforeReadingFramesOrInvokingCodecs() {
        for (backend in listOf("vap", "kap")) for (output in listOf("clip.mp4", "clip.ktx", "clip.ktx2", "clip.kap", "clip")) {
            assertEquals(EXIT_USAGE, PackCmd.run(backend, mapOf("frames" to "unused", "out" to output), asJson = true))
        }
    }

    @Test
    fun probeJsonDescribesPrismAndChoosesEngineFromPayloadKind() {
        val native = Ktx.write(KtxFormat.RGBA8, 1, 1, listOf(byteArrayOf(1, 2, 3, 127)),
            mapOf("anim.fps" to "30", "anim.premultiplied" to "false"))
        val bytes = PrismContainer.write(PrismMetadata(1, 1, 1, 30, 1, PrismAlphaMode.Straight), PrismPayloadKind.Ktx2, native)
        val result = PrismMotionProbe.probe(bytes, "clip.prism")
        assertTrue(result.ok, result.issues.toString())
        assertContentEquals(native, PrismContainer.extractPayload(bytes, true))
        val json = ProbeCmd.toJson(result)
        assertTrue(json.contains("\"format\":\"Prism\""), json)
        assertTrue(json.contains("\"payloadKind\":\"Ktx2\""), json)
        assertTrue(json.contains("\"engine\":\"Kap\""), json)
        assertTrue(json.contains("\"fpsNumerator\":30"), json)
        assertTrue(json.contains("\"fpsDenominator\":1"), json)
        assertTrue(json.contains("\"alphaMode\":\"Straight\""), json)
    }

    @Test
    fun vapPackedRgbIsPremultipliedAndAlphaRemainsInNativeMask() {
        val source = ByteArray(2 * 2 * 4) { index -> byteArrayOf(-56, 100, 50, -128)[index % 4] }
        val layout = VapPack.computeLayout(2, 2, 0.5f, hasAlpha = true)
        val packed = ByteArray(layout.videoW * layout.videoH * 4)
        VapPack.packFrame(source, 2, 2, layout, packed)
        assertEquals(100, packed[0].toInt() and 255)
        assertEquals(50, packed[1].toInt() and 255)
        assertEquals(25, packed[2].toInt() and 255)
        val mask = (layout.alphaY * layout.videoW + layout.alphaX) * 4
        assertEquals(128, packed[mask].toInt() and 255)
        assertTrue(VapPack.vapcJson(1, 2, 2, layout, 30).contains("\"hasAlpha\":true"))
    }
}
