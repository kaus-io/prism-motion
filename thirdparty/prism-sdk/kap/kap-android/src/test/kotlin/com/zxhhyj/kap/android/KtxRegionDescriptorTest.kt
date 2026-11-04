package com.zxhhyj.kap.android

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.os.ParcelFileDescriptor
import com.zxhhyj.kap.core.Ktx2Info
import com.zxhhyj.kap.core.Ktx2Parser
import com.zxhhyj.kap.core.Ktx2Writer
import com.zxhhyj.kap.core.KtxFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.RandomAccessFile

/** 文件与 APK 风格区域的描述符归属测试，不创建播放器或加载 JNI。 / Descriptor ownership tests without a player or JNI. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class KtxRegionDescriptorTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun fileRegionReadsExactPayloadAndNativeInputSurvivesReaderClose() {
        val payload = payload()
        val fixture = temporaryFolder.newFile("wrapped.prism")
        fixture.writeBytes(ByteArray(4096) { 0x55 } + payload + ByteArray(53) { 0x66 })
        val source = KtxAnimationSource.Region(KtxAnimationSource.File(fixture.path), 4096L, payload.size.toLong())
        val reader = openKtxReader(context, source)
        val input = try {
            val info = Ktx2Parser.parse(readPrefix(reader), requireLevelData = false)
            assertEquals(24, info.fps)
            assertArrayEquals(payload, reader.readAll())
            assertThrows(IllegalArgumentException::class.java) { reader.readAt(reader.size, ByteArray(1), 0, 1) }
            reader.openNativeInput()
        } finally {
            reader.close()
        }
        input.use {
            val actual = ByteArray(payload.size)
            it.readAt(0L, actual, 0, actual.size)
            assertArrayEquals(payload, actual)
            assertThrows(IllegalArgumentException::class.java) { it.readAt(payload.size.toLong(), ByteArray(1), 0, 1) }
        }
        assertThrows(Exception::class.java) { input.readAt(0L, ByteArray(1), 0, 1) }
        openKtxReader(context, source).use { assertArrayEquals(payload, it.readAll()) }
    }

    @Test fun assetBaseAndContainerOffsetComposeForHeaderAndNativeLevelReads() {
        val payload = payload()
        val apkPrefix = ByteArray(123) { 0x33 }
        val container = ByteArray(4096) { 0x44 } + payload + ByteArray(29) { 0x55 }
        val fixture = temporaryFolder.newFile("apk-data.bin")
        fixture.writeBytes(apkPrefix + container + ByteArray(41) { 0x66 })
        val descriptor = AssetFileDescriptor(
            ParcelFileDescriptor.open(fixture, ParcelFileDescriptor.MODE_READ_ONLY),
            apkPrefix.size.toLong(),
            container.size.toLong(),
        )
        val reader = RegionKtxReader(AssetKtxReader(descriptor), 4096L, payload.size.toLong())
        val expectedInfo = Ktx2Parser.parse(payload)
        val input = try {
            val info = Ktx2Parser.parse(readPrefix(reader), requireLevelData = false)
            assertEquals(expectedInfo, info)
            assertArrayEquals(payload, reader.readAll())
            reader.openNativeInput(info.levelOffset, info.levelLength)
        } finally {
            reader.close()
        }
        input.use {
            val actual = ByteArray(expectedInfo.levelLength.toInt())
            it.readAt(0L, actual, 0, actual.size)
            assertArrayEquals(payload.copyOfRange(expectedInfo.levelOffset.toInt(), payload.size), actual)
            assertThrows(IllegalArgumentException::class.java) { it.readAt(actual.size.toLong(), ByteArray(1), 0, 1) }
        }
    }

    @Test fun rejectsRegionBeyondCurrentFileLengthAndRechecksAfterTruncation() {
        val payload = payload()
        val fixture = temporaryFolder.newFile("mutable.prism")
        fixture.writeBytes(ByteArray(4096) + payload)
        val source = KtxAnimationSource.Region(KtxAnimationSource.File(fixture.path), 4096L, payload.size.toLong())
        openKtxReader(context, source).use { assertArrayEquals(payload, it.readAll()) }
        RandomAccessFile(fixture, "rw").use { it.setLength(fixture.length() - 1L) }
        assertThrows(IllegalArgumentException::class.java) { openKtxReader(context, source).close() }
        val pastEnd = source.copy(offset = fixture.length())
        assertThrows(IllegalArgumentException::class.java) { openKtxReader(context, pastEnd).close() }
        val huge = source.copy(offset = 0L, length = Long.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { openKtxReader(context, huge).close() }
    }

    @Test fun nativeRegionPreservesOffsetsBeyondSignedIntRange() {
        val payload = payload()
        val offset = Int.MAX_VALUE.toLong() + 4096L
        val fixture = temporaryFolder.newFile("sparse.prism")
        RandomAccessFile(fixture, "rw").use {
            it.seek(offset)
            it.write(payload)
        }
        val source = KtxAnimationSource.Region(KtxAnimationSource.File(fixture.path), offset, payload.size.toLong())
        openKtxReader(context, source).use { reader ->
            assertArrayEquals(payload, reader.readAll())
            reader.openNativeInput().use { input ->
                val actual = ByteArray(payload.size)
                input.readAt(0L, actual, 0, actual.size)
                assertArrayEquals(payload, actual)
            }
        }
    }

    private fun payload(): ByteArray = Ktx2Writer.write(
        KtxFormat.RGBA8,
        2,
        2,
        listOf(ByteArray(16) { (it + 1).toByte() }),
        mapOf(Ktx2Info.KEY_FPS to "24"),
    )
}
