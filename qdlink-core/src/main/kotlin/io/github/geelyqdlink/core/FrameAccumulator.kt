package io.github.geelyqdlink.core

class FrameAccumulator {
    companion object {
        const val MAX_FRAME_SIZE = 16 * 1024 * 1024
    }

    private var pending = ByteArray(0)

    val pendingBytes: Int get() = pending.size

    fun reset() {
        pending = ByteArray(0)
    }

    fun offer(bytes: ByteArray, count: Int = bytes.size): List<ByteArray> {
        require(count in 0..bytes.size)
        if (count == 0) return emptyList()
        if (pending.size > MAX_FRAME_SIZE - count) throw ProtocolException("Accumulator exceeds maximum")
        pending += bytes.copyOf(count)
        val frames = mutableListOf<ByteArray>()
        while (true) {
            if (pending.size < 4) break
            val wireLength = when {
                marker(pending, "!BIN") -> {
                    if (pending.size < 12) break
                    checkedLength(pending.u32be(8), alreadyPadded = true)
                }
                marker(pending, "5A5A") -> {
                    if (pending.size < 8) break
                    checkedLength(pending.u32be(4), alreadyPadded = false)
                }
                else -> throw ProtocolException("Unknown top-level marker")
            }
            if (pending.size < wireLength) break
            frames += pending.copyOfRange(0, wireLength)
            pending = pending.copyOfRange(wireLength, pending.size)
        }
        return frames
    }

    private fun checkedLength(value: Long, alreadyPadded: Boolean): Int {
        if (value < 16 || value > MAX_FRAME_SIZE) throw ProtocolException("Declared frame length $value is invalid")
        val exact = value.toInt()
        val wire = if (alreadyPadded) exact else padded512(exact)
        if (wire > MAX_FRAME_SIZE || (alreadyPadded && wire % 512 != 0)) {
            throw ProtocolException("Wire frame length $wire is invalid")
        }
        return wire
    }
}

