package me.abuzaid.lensift.analysis

/** A streaming SHA-256 implementation for content fingerprints. */
class Sha256 {
    private val hash = INITIAL_HASH.copyOf()
    private val block = ByteArray(BLOCK_SIZE)
    private var bufferedBytes = 0
    private var messageLengthBytes = 0L
    private var finalizedDigest: ByteArray? = null

    fun update(bytes: ByteArray): Sha256 {
        check(finalizedDigest == null) { "Cannot update a finalized SHA-256 digest" }
        messageLengthBytes += bytes.size.toLong()

        var offset = 0
        if (bufferedBytes > 0) {
            val copied = minOf(BLOCK_SIZE - bufferedBytes, bytes.size)
            bytes.copyInto(block, bufferedBytes, offset, offset + copied)
            bufferedBytes += copied
            offset += copied
            if (bufferedBytes == BLOCK_SIZE) {
                processBlock(block, 0)
                bufferedBytes = 0
            }
        }

        while (offset + BLOCK_SIZE <= bytes.size) {
            processBlock(bytes, offset)
            offset += BLOCK_SIZE
        }

        if (offset < bytes.size) {
            bytes.copyInto(block, 0, offset, bytes.size)
            bufferedBytes = bytes.size - offset
        }
        return this
    }

    fun digest(): ByteArray {
        finalizedDigest?.let { return it.copyOf() }

        val bitLength = messageLengthBytes shl 3
        block[bufferedBytes++] = 0x80.toByte()
        if (bufferedBytes > LENGTH_OFFSET) {
            block.fill(0, bufferedBytes, BLOCK_SIZE)
            processBlock(block, 0)
            bufferedBytes = 0
        }
        block.fill(0, bufferedBytes, LENGTH_OFFSET)
        writeLongBigEndian(bitLength, block, LENGTH_OFFSET)
        processBlock(block, 0)

        val result = ByteArray(DIGEST_SIZE)
        hash.forEachIndexed { index, value -> writeIntBigEndian(value, result, index * Int.SIZE_BYTES) }
        finalizedDigest = result
        return result.copyOf()
    }

    fun digestHex(): String {
        val bytes = digest()
        val characters = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            characters[index * 2] = HEX_DIGITS[value ushr 4]
            characters[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
        }
        return characters.concatToString()
    }

    private fun processBlock(input: ByteArray, offset: Int) {
        val schedule = IntArray(64)
        for (index in 0 until 16) {
            schedule[index] = readIntBigEndian(input, offset + index * Int.SIZE_BYTES)
        }
        for (index in 16 until schedule.size) {
            schedule[index] = smallSigma1(schedule[index - 2]) + schedule[index - 7] +
                smallSigma0(schedule[index - 15]) + schedule[index - 16]
        }

        var a = hash[0]
        var b = hash[1]
        var c = hash[2]
        var d = hash[3]
        var e = hash[4]
        var f = hash[5]
        var g = hash[6]
        var h = hash[7]

        for (index in schedule.indices) {
            val first = h + bigSigma1(e) + choose(e, f, g) + ROUND_CONSTANTS[index] + schedule[index]
            val second = bigSigma0(a) + majority(a, b, c)
            h = g
            g = f
            f = e
            e = d + first
            d = c
            c = b
            b = a
            a = first + second
        }

        hash[0] += a
        hash[1] += b
        hash[2] += c
        hash[3] += d
        hash[4] += e
        hash[5] += f
        hash[6] += g
        hash[7] += h
    }

    private fun choose(x: Int, y: Int, z: Int): Int = (x and y) xor (x.inv() and z)

    private fun majority(x: Int, y: Int, z: Int): Int = (x and y) xor (x and z) xor (y and z)

    private fun bigSigma0(value: Int): Int = value.rotateRight(2) xor value.rotateRight(13) xor value.rotateRight(22)

    private fun bigSigma1(value: Int): Int = value.rotateRight(6) xor value.rotateRight(11) xor value.rotateRight(25)

    private fun smallSigma0(value: Int): Int = value.rotateRight(7) xor value.rotateRight(18) xor (value ushr 3)

    private fun smallSigma1(value: Int): Int = value.rotateRight(17) xor value.rotateRight(19) xor (value ushr 10)

    private fun readIntBigEndian(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun writeIntBigEndian(value: Int, target: ByteArray, offset: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun writeLongBigEndian(value: Long, target: ByteArray, offset: Int) {
        for (index in 0 until Long.SIZE_BYTES) {
            target[offset + index] = (value ushr ((Long.SIZE_BYTES - index - 1) * Byte.SIZE_BITS)).toByte()
        }
    }

    private companion object {
        const val BLOCK_SIZE = 64
        const val DIGEST_SIZE = 32
        const val LENGTH_OFFSET = 56
        const val HEX_DIGITS = "0123456789abcdef"

        val INITIAL_HASH = intArrayOf(
            0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
            0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19,
        )

        val ROUND_CONSTANTS = intArrayOf(
            0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b, 0x59f111f1,
            0x923f82a4.toInt(), 0xab1c5ed5.toInt(), 0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
            0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(), 0xe49b69c1.toInt(), 0xefbe4786.toInt(),
            0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152.toInt(), 0xa831c66d.toInt(),
            0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
            0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(), 0xd192e819.toInt(), 0xd6990624.toInt(),
            0xf40e3585.toInt(), 0x106aa070, 0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
            0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(), 0x90befffa.toInt(), 0xa4506ceb.toInt(),
            0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
        )
    }
}
