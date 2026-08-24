package io.github.geelyqdlink.lab

import android.os.ParcelFileDescriptor
import io.github.geelyqdlink.core.ByteTransport
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

class UsbAccessoryTransport(
    private val descriptor: ParcelFileDescriptor,
) : ByteTransport {
    companion object {
        // Android's AOA guide documents packet buffers up to 16,384 bytes.
        const val AOA_TRANSFER_BUFFER_SIZE = 16_384
    }

    private val input = FileInputStream(descriptor.fileDescriptor)
    private val output = FileOutputStream(descriptor.fileDescriptor)
    private val outputChannel = output.channel

    override fun readTransfer(): ByteArray? {
        val transfer = ByteArray(AOA_TRANSFER_BUFFER_SIZE)
        val count = input.read(transfer)
        return if (count < 0) null else transfer.copyOf(count)
    }

    @Synchronized
    override fun writeFully(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) outputChannel.write(buffer)
    }

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { descriptor.close() }
    }
}

