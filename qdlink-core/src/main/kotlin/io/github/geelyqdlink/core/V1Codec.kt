package io.github.geelyqdlink.core

object V1Codec {
    const val HEADER_SIZE = 512
    const val DATA_TYPE_CMD = 0
    const val DATA_TYPE_SCREEN_CAPTURE = 1
    const val DATA_TYPE_HU_MSG = 3
    const val DATA_TYPE_IN_APP_CONTROL = 10

    const val CMD_APP_STATUS = 1
    const val CMD_VERSION = 3
    const val CMD_LAND_MODE = 5
    const val CMD_HEARTBEAT = 10
    const val CMD_PLAY_STATUS = 12
    const val CMD_UPGRADE = 13
    const val CMD_MIRROR_SUPPORT = 16
    const val CMD_KEY_FRAME_REQ = 17

    data class Header(
        val dataType: Int,
        val totalSize: Int,
        val headerSize: Int,
        val action: Int,
    )

    fun parseHeader(frame: ByteArray): Header {
        if (frame.size < 64 || !marker(frame, "!BIN")) throw ProtocolException("V1 marker missing")
        val totalLong = frame.u32be(8)
        val headerLong = frame.u32be(12)
        if (totalLong > Int.MAX_VALUE || headerLong > Int.MAX_VALUE) throw ProtocolException("V1 length overflow")
        val total = totalLong.toInt()
        val header = headerLong.toInt()
        if (total < 64 || total > FrameAccumulator.MAX_FRAME_SIZE || total != frame.size) {
            throw ProtocolException("V1 total length $total does not match ${frame.size}")
        }
        if (header !in 64..total) throw ProtocolException("V1 header length $header is invalid")
        if (frame.u32be(16) != 64L) throw ProtocolException("V1 common header size is not 64")
        return Header(frame.u32be(4).toInt(), total, header, frame.u32be(28).toInt())
    }

    fun command(frame: ByteArray): Int {
        val header = parseHeader(frame)
        if (header.dataType != DATA_TYPE_CMD || frame.size < 72) throw ProtocolException("V1 command header missing")
        return frame.u32be(68).toInt()
    }

    fun versionDimensions(frame: ByteArray): TargetDimensions {
        if (command(frame) != CMD_VERSION || frame.size < 136) throw ProtocolException("Not a complete V1 VERSION")
        return TargetDimensions(frame.u32be(128).toInt(), frame.u32be(132).toInt())
    }

    fun touch(frame: ByteArray): TouchFrame {
        val header = parseHeader(frame)
        if (header.dataType != DATA_TYPE_IN_APP_CONTROL || frame.size < 86) {
            throw ProtocolException("Not a complete V1 touch frame")
        }
        val action = when (frame.i16be(66)) {
            -32768 -> TouchAction.DOWN
            -32767 -> TouchAction.MOVE
            -32766 -> TouchAction.UP
            else -> throw ProtocolException("Unknown V1 touch action")
        }
        return TouchFrame(
            actionId = null,
            pointers = listOf(TouchPointer(0, action, frame.i16be(68).toFloat(), frame.i16be(70).toFloat())),
        )
    }

    fun huMessage(frame: ByteArray): HuMessage {
        val header = parseHeader(frame)
        if (header.dataType != DATA_TYPE_HU_MSG || header.headerSize != HEADER_SIZE || frame.size <= HEADER_SIZE) {
            throw ProtocolException("Not a complete V1 HU_MSG")
        }
        val dataSize = frame.u32be(64).toInt()
        if (dataSize <= 0 || dataSize > frame.size - HEADER_SIZE) throw ProtocolException("Invalid HU_MSG data size")
        return HuMessageCodec.decode(frame.copyOfRange(HEADER_SIZE, HEADER_SIZE + dataSize))
    }

    fun encodeAppStatus(): ByteArray = commandFrame(CMD_APP_STATUS, action = 2).apply {
        putU32be(20, 128)
        putU32be(24, 128)
        putU32be(72, 1)
        putU32be(76, 24)
        putU32be(80, 2)
    }

    fun encodeVersionRequest(width: Int, height: Int): ByteArray = commandFrame(CMD_VERSION, action = 1).apply {
        putU32be(128, width.toLong())
        putU32be(132, height.toLong())
    }

    fun encodeVersionResponse(dimensions: TargetDimensions): ByteArray = commandFrame(CMD_VERSION, action = 1).apply {
        putU32be(192, 24)
        putU32be(196, dimensions.width.toLong())
        putU32be(200, dimensions.height.toLong())
        putU32be(204, dimensions.width.toLong())
        putU32be(208, dimensions.height.toLong())
    }

    fun encodeUpgradeResponse(): ByteArray = commandFrame(CMD_UPGRADE, action = 2).apply {
        putU32be(72, 5)
    }

    fun encodeMirrorSupportResponse(): ByteArray = commandFrame(CMD_MIRROR_SUPPORT, action = 1).apply {
        putU32be(72, 0)
        putU32be(192, 1)
    }

    fun encodeCommandRequest(command: Int): ByteArray = commandFrame(command, action = 1)

    fun landMode(frame: ByteArray): Int {
        if (command(frame) != CMD_LAND_MODE || frame.size < 76) throw ProtocolException("Not LAND_MODE")
        return frame.u32be(72).toInt()
    }

    fun encodeLandMode(value: Int, action: Int = 2): ByteArray = commandFrame(CMD_LAND_MODE, action).apply {
        putU32be(72, value.toLong())
    }

    fun encodeHeartbeat(): ByteArray = commandFrame(CMD_HEARTBEAT, action = 1).apply {
        putU32be(72, 1)
        putU32be(76, 1)
    }

    fun encodeTouch(action: TouchAction, x: Int, y: Int): ByteArray = commonFrame(DATA_TYPE_IN_APP_CONTROL, action = 1).apply {
        putU16be(66, when (action) {
            TouchAction.DOWN -> 0x8000
            TouchAction.MOVE -> 0x8001
            TouchAction.UP -> 0x8002
        })
        putU16be(68, x and 0xffff)
        putU16be(70, y and 0xffff)
    }

    fun encodeHuMessage(message: HuMessage, action: Int = 1): ByteArray {
        val nested = HuMessageCodec.encode(message)
        val total = HEADER_SIZE + padded512(nested.size)
        return commonFrame(DATA_TYPE_HU_MSG, action, total).apply {
            putU32be(20, 64)
            putU32be(24, 0)
            putU32be(64, nested.size.toLong())
            nested.copyInto(this, HEADER_SIZE)
        }
    }

    fun encodeVideo(dimensions: TargetDimensions, frameRate: Int, annexB: ByteArray): ByteArray {
        require(annexB.isNotEmpty())
        val total = HEADER_SIZE + padded512(annexB.size)
        return commonFrame(DATA_TYPE_SCREEN_CAPTURE, action = 1, total = total).apply {
            putU32be(64, dimensions.width.toLong())
            putU32be(68, dimensions.height.toLong())
            putU32be(72, 4)
            putU32be(76, 2)
            putU32be(80, 3)
            putU32be(84, 0)
            putU32be(88, 1)
            putU32be(92, annexB.size.toLong())
            putU32be(100, dimensions.width.toLong())
            putU32be(104, dimensions.height.toLong())
            putU32be(116, dimensions.width.toLong())
            putU32be(120, dimensions.height.toLong())
            putU32be(132, 3)
            putU32be(136, 90)
            putU32be(140, 90)
            putU32be(148, dimensions.width.toLong())
            putU32be(152, dimensions.height.toLong())
            putU32be(180, frameRate.toLong())
            putU32be(188, 1)
            putU32be(192, frameRate.toLong())
            putU32be(200, 1)
            putU32be(204, 1)
            annexB.copyInto(this, HEADER_SIZE)
        }
    }

    private fun commandFrame(command: Int, action: Int): ByteArray = commonFrame(DATA_TYPE_CMD, action).apply {
        putU32be(68, command.toLong())
    }

    private fun commonFrame(dataType: Int, action: Int, total: Int = HEADER_SIZE): ByteArray {
        require(total >= HEADER_SIZE && total % HEADER_SIZE == 0)
        return ByteArray(total).apply {
            "!BIN".encodeToByteArray().copyInto(this)
            putU32be(4, dataType.toLong())
            putU32be(8, total.toLong())
            putU32be(12, HEADER_SIZE.toLong())
            putU32be(16, 64)
            putU32be(20, 128)
            putU32be(24, 128)
            putU32be(28, action.toLong())
            repeat(32) { this[32 + it] = (32 + it).toByte() }
        }
    }
}
