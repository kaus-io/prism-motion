package com.zxhhyj.prism.motion.probe

import com.zxhhyj.prism.motion.AnimationFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnimationProbeTest {

    @Test
    fun readsVapMetadataFromTheVapcBox() {
        val result = AnimationProbe.probe(vapFile(), "clip.mp4")

        assertEquals(AnimationFormat.Vap, result.format)
        assertEquals(100, result.width)
        assertEquals(200, result.height)
        assertEquals(60, result.frameCount)
        assertEquals(30, result.fps)
        assertEquals(2_000L, result.durationMs)
        assertEquals(true, result.hasAlpha)
        assertTrue(result.ok)
    }

    @Test
    fun plainMp4IsAllowedButFlagged() {
        val result = AnimationProbe.probe(mp4(FileInfo(width = 64, height = 32, durationMs = 500)), "plain.mp4")

        assertEquals(AnimationFormat.Vap, result.format)
        assertEquals(64, result.width)
        assertEquals(32, result.height)
        assertEquals(500L, result.durationMs)
        assertTrue(result.ok, "普通 MP4 只是警告，不应判失败")
        assertTrue(result.issues.any { it.code == "missing-vapc" })
    }

    @Test
    fun readsKapMetadataFromTheKtx2KeyValues() {
        val result = AnimationProbe.probe(ktx2(width = 320, height = 240, layers = 10, fps = 30), "clip.ktx2")

        assertEquals(AnimationFormat.Kap, result.format)
        assertEquals(320, result.width)
        assertEquals(240, result.height)
        assertEquals(10, result.frameCount)
        assertEquals(30, result.fps)
        assertEquals(333L, result.durationMs)
        assertTrue(result.ok)
    }

    @Test
    fun unknownContainerIsAnError() {
        val result = AnimationProbe.probe("not an animation".encodeToByteArray(), "a.gif")

        assertNull(result.format)
        assertFalse(result.ok)
        assertTrue(result.issues.any { it.code == "unknown-container" })
    }

    @Test
    fun suffixThatDisagreesWithContentOnlyWarns() {
        val result = AnimationProbe.probe(ktx2(8, 8, 2, 30), "misnamed.mp4")

        assertEquals(AnimationFormat.Kap, result.format)
        assertTrue(result.ok)
        assertTrue(result.issues.any { it.code == "format-mismatch" })
    }

    // ---- fixtures ------------------------------------------------------

    private data class FileInfo(val width: Int, val height: Int, val durationMs: Long)

    private fun vapFile(): ByteArray {
        val json = """{"info":{"v":3,"f":60,"w":100,"h":200,"videoW":100,"videoH":400,"fps":30,"hasAlpha":true}}"""
        return mp4(FileInfo(100, 200, 1_000)) + box("vapc", json.encodeToByteArray())
    }

    private fun mp4(info: FileInfo): ByteArray {
        val timescale = 1_000
        val mvhd = ByteArray(100)
        putU32(mvhd, 12, timescale)
        putU32(mvhd, 16, info.durationMs.toInt())
        val tkhd = ByteArray(92)
        putU32(tkhd, 76, info.width * 65_536)
        putU32(tkhd, 80, info.height * 65_536)
        val moov = box("moov", box("mvhd", mvhd) + box("trak", box("tkhd", tkhd)))
        return box("ftyp", "isom".encodeToByteArray()) + moov
    }

    private fun ktx2(width: Int, height: Int, layers: Int, fps: Int): ByteArray {
        val header = ByteArray(80)
        val magic = byteArrayOf(
            0xAB.toByte(), 0x4B, 0x54, 0x58, 0x20, 0x32, 0x30,
            0xBB.toByte(), 0x0D, 0x0A, 0x1A, 0x0A,
        )
        magic.copyInto(header)
        putU32le(header, 12, 25)
        putU32le(header, 20, width)
        putU32le(header, 24, height)
        putU32le(header, 32, layers)
        putU32le(header, 36, 1)
        putU32le(header, 40, 1)
        val kv = buildKeyValues(mapOf("anim.fps" to fps.toString(), "anim.loop" to "true"))
        putU32le(header, 56, 80)
        putU32le(header, 60, kv.size)
        return header + kv
    }

    private fun buildKeyValues(values: Map<String, String>): ByteArray {
        val chunks = ArrayList<ByteArray>()
        for ((key, value) in values) {
            val entry = "$key\u0000$value".encodeToByteArray()
            val size = ByteArray(4)
            putU32le(size, 0, entry.size)
            chunks += size
            chunks += entry
            chunks += ByteArray((4 - entry.size % 4) % 4)
        }
        return chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        putU32(out, 0, out.size)
        type.encodeToByteArray().copyInto(out, 4)
        payload.copyInto(out, 8)
        return out
    }

    /** KTX2 的字段是小端序，与 MP4 相反。 */
    private fun putU32le(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }

    private fun putU32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }
}
