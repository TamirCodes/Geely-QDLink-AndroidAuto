package io.github.geelyqdlink.core

import java.nio.charset.StandardCharsets

data class HuMessage(
    val flowId: Int = 0,
    val appId: String,
    val logicId: String,
    val items: List<String> = emptyList(),
)

object HuMessageCodec {
    private const val MAX_DECLARED = 1 shl 20
    private val ascii = StandardCharsets.US_ASCII

    fun encode(message: HuMessage): ByteArray {
        require(message.flowId in 0..0xff)
        require(message.appId.toByteArray(ascii).size <= 0xff)
        require(message.logicId.toByteArray(ascii).size <= 0xff)
        require(message.items.size <= 0xff)

        val body = buildString {
            append("A6A6")
            appendHex(message.flowId.toLong(), 2)
            append("00000000")
            appendSized(message.appId, 2)
            appendSized(message.logicId, 2)
            appendHex(message.items.size.toLong(), 2)
            message.items.forEach { appendSized(it, 8) }
        }
        val total = body.length + 4
        val withLength = body.replaceRange(6, 14, total.toString(16).uppercase().padStart(8, '0'))
        val crc = Crc16Xmodem.calculate(withLength.toByteArray(ascii))
        return (withLength + crc.toString(16).uppercase().padStart(4, '0')).toByteArray(ascii)
    }

    fun decode(input: ByteArray): HuMessage {
        if (input.size < 22 || !marker(input, "A6A6")) throw ProtocolException("A6A6 marker missing")
        val cursor = HexCursor(input)
        cursor.expect("A6A6")
        val flowId = cursor.hexInt(2).toInt()
        val declared = cursor.hexInt(8).toInt()
        if (declared !in 22..minOf(input.size, MAX_DECLARED)) {
            throw ProtocolException("A6A6 declared length $declared is invalid")
        }
        val appId = cursor.sizedAscii(2, declared)
        val logicId = cursor.sizedAscii(2, declared)
        val count = cursor.hexInt(2).toInt()
        val items = ArrayList<String>(count)
        repeat(count) { items += cursor.sizedAscii(8, declared) }
        val crcOffset = cursor.position
        val receivedCrc = cursor.hexInt(4).toInt()
        if (cursor.position > declared) throw ProtocolException("A6A6 fields exceed declared length")
        if ((crcOffset + 4 until declared).any { input[it] != 0.toByte() }) {
            throw ProtocolException("A6A6 non-zero bytes after CRC")
        }
        val actualCrc = Crc16Xmodem.calculate(input, 0, crcOffset)
        if (receivedCrc != actualCrc) {
            throw ProtocolException("A6A6 CRC mismatch: received=%04X actual=%04X".format(receivedCrc, actualCrc))
        }
        return HuMessage(flowId, appId, logicId, items)
    }

    private fun StringBuilder.appendHex(value: Long, width: Int) {
        val encoded = value.toString(16).uppercase()
        require(encoded.length <= width)
        append(encoded.padStart(width, '0'))
    }

    private fun StringBuilder.appendSized(value: String, lengthWidth: Int) {
        val bytes = value.toByteArray(ascii)
        appendHex(bytes.size.toLong(), lengthWidth)
        append(value)
    }

    private class HexCursor(private val input: ByteArray) {
        var position: Int = 0
            private set

        fun expect(text: String) {
            val bytes = text.toByteArray(ascii)
            requireAvailable(bytes.size, input.size)
            if (!input.copyOfRange(position, position + bytes.size).contentEquals(bytes)) {
                throw ProtocolException("Expected $text")
            }
            position += bytes.size
        }

        fun hexInt(width: Int): Long {
            requireAvailable(width, input.size)
            var result = 0L
            repeat(width) {
                val ch = input[position++].toInt().toChar()
                val digit = ch.digitToIntOrNull(16) ?: throw ProtocolException("Invalid hex character")
                result = (result shl 4) or digit.toLong()
            }
            return result
        }

        fun sizedAscii(lengthWidth: Int, limit: Int): String {
            val lengthLong = hexInt(lengthWidth)
            if (lengthLong > Int.MAX_VALUE) throw ProtocolException("Length overflow")
            val length = lengthLong.toInt()
            requireAvailable(length, limit)
            val result = input.copyOfRange(position, position + length).toString(ascii)
            position += length
            return result
        }

        private fun requireAvailable(count: Int, limit: Int) {
            if (count < 0 || position > limit - count || position > input.size - count) {
                throw ProtocolException("Truncated A6A6 field")
            }
        }
    }
}

