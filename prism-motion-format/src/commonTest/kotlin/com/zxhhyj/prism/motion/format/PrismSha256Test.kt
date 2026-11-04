package com.zxhhyj.prism.motion.format

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** SHA-256 标准向量、块边界与范围测试。 / Tests SHA-256 standard vectors, block boundaries and ranges. */
class PrismSha256Test {
    @Test
    fun standardVectors() {
        assertDigest("", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        assertDigest("abc", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        assertDigest(
            "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq",
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
        )
        assertDigest("a".repeat(1_000_000), "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0")
    }

    @Test
    fun independentBinaryVectorsCoverPaddingAndBlockBoundaries() {
        val vectors = listOf(
            1 to "6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d",
            55 to "463eb28e72f82e0a96c0a4cc53690c571281131f672aa229e0d45ae59b598b59",
            56 to "da2ae4d6b36748f2a318f23e7ab1dfdf45acdc9d049bd80e59de82a60895f562",
            63 to "29af2686fd53374a36b0846694cc342177e428d1647515f078784d69cdb9e488",
            64 to "fdeab9acf3710362bd2658cdc9a29e8f9c757fcf9811603a8c447cd1d9151108",
            65 to "4bfd2c8b6f1eec7a2afeb48b934ee4b2694182027e6d0fc075074f2fabb31781",
            119 to "da18797ed7c3a777f0847f429724a2d8cd5138e6ed2895c3fa1a6d39d18f7ec6",
            120 to "f52b23db1fbb6ded89ef42a23ce0c8922c45f25c50b568a93bf1c075420bbb7c",
            127 to "92ca0fa6651ee2f97b884b7246a562fa71250fedefe5ebf270d31c546bfea976",
            128 to "471fb943aa23c511f6f72f8d1652d9c880cfa392ad80503120547703e56a2be5",
            129 to "5099c6a56203f9687f7d33f4bfdf576d31dc91f6b695ecea38b2770c87631135",
        )
        for ((length, expected) in vectors) {
            val bytes = ByteArray(length) { it.toByte() }
            assertEquals(expected, PrismSha256.hex(PrismSha256.digest(bytes)), "length=$length")
            val framed = byteArrayOf(-1, -2, -3) + bytes + byteArrayOf(-4, -5)
            assertEquals(expected, PrismSha256.hex(PrismSha256.digest(framed, 3, length)))
            assertContentEquals(bytes, framed.copyOfRange(3, length + 3))
        }
    }

    @Test
    fun supportsDefaultAndEmptyRangesWithoutMutation() {
        val bytes = "prefixabc".encodeToByteArray()
        val original = bytes.copyOf()
        assertContentEquals(PrismSha256.digest("abc".encodeToByteArray()), PrismSha256.digest(bytes, 6))
        assertContentEquals(PrismSha256.digest(byteArrayOf()), PrismSha256.digest(bytes, bytes.size))
        assertContentEquals(PrismSha256.digest(byteArrayOf()), PrismSha256.digest(bytes, 4, 0))
        assertContentEquals(original, bytes)
        assertEquals("00017f80ff", PrismSha256.hex(byteArrayOf(0, 1, 127, -128, -1)))
    }

    @Test
    fun rejectsInvalidRangesWithoutIndexOverflow() {
        val bytes = byteArrayOf(1, 2, 3)
        for ((offset, length) in listOf(-1 to 0, 4 to 0, 0 to -1, 1 to 3, 2 to Int.MAX_VALUE, Int.MAX_VALUE to 1)) {
            assertFailsWith<IllegalArgumentException> { PrismSha256.digest(bytes, offset, length) }
        }
        assertFailsWith<IllegalArgumentException> { PrismSha256.digest(bytes, Int.MIN_VALUE) }
    }

    private fun assertDigest(input: String, expected: String) {
        assertEquals(expected, PrismSha256.hex(PrismSha256.digest(input.encodeToByteArray())))
    }
}
