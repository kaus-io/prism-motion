package com.zxhhyj.vap.decode

import com.zxhhyj.vap.player.VapSource
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.global.avformat.AVIO_FLAG_READ
import org.bytedeco.ffmpeg.global.avformat.avio_closep
import org.bytedeco.ffmpeg.global.avformat.avio_open
import org.bytedeco.ffmpeg.global.avformat.avio_read
import org.bytedeco.ffmpeg.global.avformat.avio_seek
import org.bytedeco.ffmpeg.global.avformat.avio_size
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmVapRegionInputTest {
    @Test
    fun nativeSubfileExposesOnlyBoundedSeekablePayload() = withFixture { file ->
        val region = VapSource.Region(VapSource.AbsolutePath(file.absolutePath), 2L, 3L)
        val input = AVIOContext(null)
        assertTrue(avio_open(input, jvmVapRegionInput(region), AVIO_FLAG_READ) >= 0, "FFmpeg subfile must be available")
        try {
            assertEquals(3L, avio_size(input))
            val bytes = ByteArray(8)
            assertEquals(3, avio_read(input, bytes, bytes.size))
            assertContentEquals(byteArrayOf(1, 2, 3), bytes.copyOf(3))
            assertTrue(avio_read(input, bytes, 1) < 0)
            assertEquals(1L, avio_seek(input, 1L, 0))
            assertEquals(2, avio_read(input, bytes, 2))
            assertContentEquals(byteArrayOf(2, 3), bytes.copyOf(2))
        } finally {
            avio_closep(input)
        }
    }

    @Test
    fun rejectsOutOfBoundsAndAssetRegionsBeforeNativeOpen() = withFixture { file ->
        val source = VapSource.AbsolutePath(file.absolutePath)
        assertFailsWith<IllegalArgumentException> { jvmVapRegionInput(VapSource.Region(source, 6L, 2L)) }
        assertFailsWith<IllegalArgumentException> { jvmVapRegionInput(VapSource.Region(source, Long.MAX_VALUE - 1L, 1L)) }
        val error = assertFailsWith<IllegalStateException> {
            jvmVapRegionInput(VapSource.Region(VapSource.Asset("clip.prism"), 0L, 1L))
        }
        assertTrue(error.message.orEmpty().contains("Android-only"))
    }

    private fun withFixture(action: (File) -> Unit) {
        val file = Files.createTempFile("vap-native-region-", ".bin").toFile()
        try {
            file.writeBytes(byteArrayOf(99, 98, 1, 2, 3, 97, 96))
            action(file)
        } finally {
            Files.deleteIfExists(file.toPath())
        }
    }
}
