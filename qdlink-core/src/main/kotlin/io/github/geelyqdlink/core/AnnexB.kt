package io.github.geelyqdlink.core

import java.io.ByteArrayOutputStream

object AnnexB {
    private val startCode = byteArrayOf(0, 0, 0, 1)

    fun normalize(accessUnit: ByteArray): ByteArray {
        if (accessUnit.hasStartCode()) return accessUnit.copyOf()
        val output = ByteArrayOutputStream(accessUnit.size + 32)
        var offset = 0
        while (offset < accessUnit.size) {
            if (accessUnit.size - offset < 4) throw ProtocolException("Truncated AVCC NAL length")
            val lengthLong = accessUnit.u32be(offset)
            if (lengthLong <= 0 || lengthLong > Int.MAX_VALUE) throw ProtocolException("Invalid AVCC NAL length")
            val length = lengthLong.toInt()
            offset += 4
            if (length > accessUnit.size - offset) throw ProtocolException("AVCC NAL exceeds access unit")
            output.write(startCode)
            output.write(accessUnit, offset, length)
            offset += length
        }
        return output.toByteArray()
    }

    fun combineCodecSpecificData(parts: List<ByteArray>): ByteArray =
        parts.filter { it.isNotEmpty() }.fold(ByteArray(0)) { accumulated, part -> accumulated + normalize(part) }

    private fun ByteArray.hasStartCode(): Boolean =
        (size >= 4 && this[0] == 0.toByte() && this[1] == 0.toByte() && this[2] == 0.toByte() && this[3] == 1.toByte()) ||
            (size >= 3 && this[0] == 0.toByte() && this[1] == 0.toByte() && this[2] == 1.toByte())
}

