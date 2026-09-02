package me.abuzaid.lensift.analysis

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Sha256Test {
    @Test
    fun hashesEmptyInput() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256().digestHex(),
        )
    }

    @Test
    fun hashesAbc() {
        val digest = Sha256().update("abc".encodeToByteArray()).digestHex()

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digest)
    }

    @Test
    fun hashesOneMillionAs() {
        val hasher = Sha256()
        repeat(1_000) { hasher.update(ByteArray(1_000) { 'a'.code.toByte() }) }

        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", hasher.digestHex())
    }

    @Test
    fun irregularChunksMatchOneShotDigest() {
        val input = ByteArray(1_027) { index -> (index * 31 + 17).toByte() }
        val expected = Sha256().update(input).digestHex()
        val actual = Sha256()
        var offset = 0
        val chunkSizes = intArrayOf(1, 17, 64, 3, 255, 2, 91, 128)

        for (chunkSize in chunkSizes) {
            if (offset == input.size) break
            val end = minOf(offset + chunkSize, input.size)
            actual.update(input.copyOfRange(offset, end))
            offset = end
        }
        if (offset < input.size) actual.update(input.copyOfRange(offset, input.size))

        assertEquals(expected, actual.digestHex())
    }

    @Test
    fun hashesPaddingBoundaries() {
        for (size in intArrayOf(55, 56, 63, 64, 65)) {
            val input = ByteArray(size) { index -> (index * 13 + 5).toByte() }
            val oneShot = Sha256().update(input).digestHex()
            val chunked = Sha256()

            input.forEach { chunked.update(byteArrayOf(it)) }

            assertEquals(oneShot, chunked.digestHex(), "size=$size")
        }
    }

    @Test
    fun digestCanBeRepeatedAndDoesNotExposeMutableState() {
        val hasher = Sha256().update("abc".encodeToByteArray())
        val first = hasher.digest()
        first[0] = 0

        assertContentEquals(
            hexToBytes("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
            hasher.digest(),
        )
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hasher.digestHex())
    }

    @Test
    fun rejectsUpdatesAfterFinalization() {
        val hasher = Sha256().update("abc".encodeToByteArray())
        hasher.digest()

        assertFailsWith<IllegalStateException> { hasher.update(byteArrayOf(1)) }
    }

    private fun hexToBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
