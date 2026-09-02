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
        val expected = "dd4537f4e3c27487886a6bb1e2e4a0d5154144de4adc723147aef14352f6f7b9"
        val oneShot = Sha256().update(input).digestHex()
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

        assertEquals(expected, oneShot)
        assertEquals(expected, actual.digestHex())
        assertEquals(oneShot, actual.digestHex())
    }

    @Test
    fun hashesPaddingBoundaries() {
        for (size in intArrayOf(55, 56, 63, 64, 65)) {
            val input = ByteArray(size) { index -> (index * 13 + 5).toByte() }
            val expected = PADDING_BOUNDARY_DIGESTS.getValue(size)
            val oneShot = Sha256().update(input).digestHex()
            val chunked = Sha256()

            input.forEach { chunked.update(byteArrayOf(it)) }

            assertEquals(expected, oneShot, "one-shot size=$size")
            assertEquals(expected, chunked.digestHex(), "chunked size=$size")
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

    private companion object {
        val PADDING_BOUNDARY_DIGESTS = mapOf(
            55 to "63c06c35402b73061b2b60338121fe8f4895640a178ed01c4b2e40ff8dcd1755",
            56 to "86512867c0c0b58974d4a9f76fab04b3ed84c5ae5845bd807f809a7884ec24e5",
            63 to "56547b4ccd1b089babcb872418aa6d3be61e3aba1b97e0eda0934d729ebe9e0c",
            64 to "44aee5fa258a25ab9eeebaa630ea0ea92b017efb95fbce6f91c9c181e4d8ebe2",
            65 to "75a506795a148c07e58b5579d08badc50e39e2e0092c23042a429401dc10d7bf",
        )
    }
}
