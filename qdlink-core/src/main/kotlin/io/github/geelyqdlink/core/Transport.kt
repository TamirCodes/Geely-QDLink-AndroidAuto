package io.github.geelyqdlink.core

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.concurrent.LinkedBlockingQueue

interface ByteTransport : Closeable {
    fun readTransfer(): ByteArray?
    fun writeFully(bytes: ByteArray)
}

/**
 * Stream adapter sized for AOA's documented 16 KiB maximum USB packet buffer.
 * The parser, not this transport, reconstructs QDLink frames across transfers.
 */
class StreamByteTransport(
    input: InputStream,
    output: OutputStream,
    private val transferBufferSize: Int = 16_384,
) : ByteTransport {
    private val input = input
    private val outputChannel = Channels.newChannel(output)

    init {
        require(transferBufferSize >= 16_384)
    }

    override fun readTransfer(): ByteArray? {
        val buffer = ByteArray(transferBufferSize)
        val count = input.read(buffer)
        return if (count < 0) null else buffer.copyOf(count)
    }

    @Synchronized
    override fun writeFully(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) outputChannel.write(buffer)
    }

    override fun close() {
        runCatching { input.close() }
        runCatching { outputChannel.close() }
    }
}

class InMemoryTransport private constructor(
    private val incoming: LinkedBlockingQueue<ByteArray>,
    private val outgoing: LinkedBlockingQueue<ByteArray>,
) : ByteTransport {
    companion object {
        private val END = ByteArray(0)

        fun pair(): Pair<InMemoryTransport, InMemoryTransport> {
            val a = LinkedBlockingQueue<ByteArray>()
            val b = LinkedBlockingQueue<ByteArray>()
            return InMemoryTransport(a, b) to InMemoryTransport(b, a)
        }
    }

    @Volatile private var closed = false

    override fun readTransfer(): ByteArray? = incoming.take().takeUnless { it === END }

    override fun writeFully(bytes: ByteArray) {
        check(!closed)
        outgoing.put(bytes.copyOf())
    }

    override fun close() {
        if (!closed) {
            closed = true
            outgoing.put(END)
        }
    }
}
