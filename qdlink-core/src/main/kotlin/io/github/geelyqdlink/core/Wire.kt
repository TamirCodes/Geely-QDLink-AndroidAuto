package io.github.geelyqdlink.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xff

internal fun ByteArray.u16be(offset: Int): Int =
    (u8(offset) shl 8) or u8(offset + 1)

internal fun ByteArray.i16be(offset: Int): Int = u16be(offset).toShort().toInt()

internal fun ByteArray.u32be(offset: Int): Long =
    ((u8(offset).toLong() shl 24) or
        (u8(offset + 1).toLong() shl 16) or
        (u8(offset + 2).toLong() shl 8) or
        u8(offset + 3).toLong())

internal fun ByteArray.f32be(offset: Int): Float =
    Float.fromBits(u32be(offset).toInt())

internal fun ByteArray.putU16be(offset: Int, value: Int) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

internal fun ByteArray.putU32be(offset: Int, value: Long) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}

internal fun ByteArray.putF32be(offset: Int, value: Float) =
    putU32be(offset, value.toRawBits().toLong() and 0xffff_ffffL)

internal fun padded512(length: Int): Int = (length + 511) and 511.inv()

internal fun marker(bytes: ByteArray, value: String): Boolean =
    bytes.size >= value.length && value.indices.all { bytes[it] == value[it].code.toByte() }

internal fun ByteBuffer.bigEndian(): ByteBuffer = order(ByteOrder.BIG_ENDIAN)

fun ByteArray.hex(): String = joinToString("") { "%02X".format(it) }

