package io.github.geelyqdlink.core

import java.nio.charset.StandardCharsets

object V2Codec {
    const val HEADER_SIZE = 16
    const val SOURCE_PHONE = 0
    const val SOURCE_CAR = 1
    const val FORMAT_BINARY = 0
    const val FORMAT_JSON = 1
    const val FORMAT_VIDEO = 2
    const val TYPE_VIDEO = 1
    const val TYPE_TOUCH = 2

    data class Header(
        val fullLength: Int,
        val extendedHeaderSize: Int,
        val messageType: Int,
        val source: Int,
        val destination: Int,
        val payloadFormat: Int,
    )

    fun parseHeader(frame: ByteArray): Header {
        if (frame.size < HEADER_SIZE || !marker(frame, "5A5A")) throw ProtocolException("V2 marker missing")
        val fullLong = frame.u32be(4)
        if (fullLong > Int.MAX_VALUE) throw ProtocolException("V2 length overflow")
        val full = fullLong.toInt()
        if (full !in HEADER_SIZE..frame.size || frame.size != padded512(full)) {
            throw ProtocolException("V2 full length $full is invalid for padded size ${frame.size}")
        }
        if ((full until frame.size).any { frame[it] != 0.toByte() }) throw ProtocolException("V2 padding is non-zero")
        return Header(full, frame.u16be(8), frame.u8(10), frame.u8(11), frame.u8(12), frame.u8(13))
    }

    fun json(frame: ByteArray): String {
        val header = parseHeader(frame)
        if (header.payloadFormat != FORMAT_JSON) throw ProtocolException("V2 payload is not JSON")
        return frame.copyOfRange(HEADER_SIZE, header.fullLength).toString(StandardCharsets.UTF_8)
    }

    fun command(frame: ByteArray): String = jsonString(json(frame), "CMD")
        ?: throw ProtocolException("V2 JSON command missing")

    fun carDimensions(frame: ByteArray): TargetDimensions {
        val body = json(frame)
        if (jsonString(body, "CMD") != "CAR_INFO") throw ProtocolException("Not CAR_INFO")
        return TargetDimensions(jsonInt(body, "CarWidth"), jsonInt(body, "CarHeight"))
    }

    fun carIdentity(frame: ByteArray): CarIdentity {
        val body = json(frame)
        if (jsonString(body, "CMD") != "CAR_INFO") throw ProtocolException("Not CAR_INFO")
        return CarIdentity(
            jsonString(body, "CarType"),
            jsonString(body, "ProjectID"),
            jsonString(body, "CarFeature"),
        )
    }

    fun touch(frame: ByteArray): TouchFrame {
        val header = parseHeader(frame)
        if (header.messageType != TYPE_TOUCH || header.source != SOURCE_CAR || header.payloadFormat != FORMAT_BINARY) {
            throw ProtocolException("Not a car V2 touch frame")
        }
        if (header.extendedHeaderSize != 0) throw ProtocolException("Unsupported V2 touch extended header")
        if (header.fullLength < 21) throw ProtocolException("Truncated V2 touch header")
        val actionId = frame.u32be(16)
        val count = frame.u8(20)
        val expected = 21L + count.toLong() * 10L
        if (expected != header.fullLength.toLong()) throw ProtocolException("V2 touch count/length mismatch")
        val pointers = ArrayList<TouchPointer>(count)
        var offset = 21
        repeat(count) {
            val action = when (frame.u8(offset + 1)) {
                1 -> TouchAction.DOWN
                2 -> TouchAction.UP
                3 -> TouchAction.MOVE
                else -> throw ProtocolException("Unknown V2 finger action")
            }
            val x = frame.f32be(offset + 2)
            val y = frame.f32be(offset + 6)
            if (!x.isFinite() || !y.isFinite()) throw ProtocolException("Non-finite V2 coordinate")
            pointers += TouchPointer(frame.u8(offset), action, x, y)
            offset += 10
        }
        return TouchFrame(actionId, pointers)
    }

    fun encodeJson(json: String, source: Int = SOURCE_PHONE): ByteArray {
        val payload = json.toByteArray(StandardCharsets.UTF_8)
        val full = HEADER_SIZE + payload.size
        return commonFrame(full, padded512(full), 0, source, FORMAT_JSON).apply {
            payload.copyInto(this, HEADER_SIZE)
        }
    }

    fun encodeCarInfo(
        dimensions: TargetDimensions,
        carType: String = "SIMULATED",
        projectId: String = "LAB",
        carFeature: String = "NONE",
    ): ByteArray = encodeJson(
        "{\"PARA\":{\"CarWidth\":${dimensions.width},\"CarHeight\":${dimensions.height}," +
            "\"CarType\":\"${safeJsonValue(carType)}\",\"ProjectID\":\"${safeJsonValue(projectId)}\"," +
            "\"CarFeature\":\"${safeJsonValue(carFeature)}\"},\"CMD\":\"CAR_INFO\"}",
        SOURCE_CAR,
    )

    fun encodePhoneInfo(dimensions: TargetDimensions): ByteArray = encodeJson(
        "{\"PARA\":{\"PhoneFeature\":{\"PassistMobileNum\":\"\"},\"PhoneName\":\"QDLink Lab\",\"PlatformVersion\":\"36\",\"PhoneModel\":\"Android\",\"PhoneSystemTime\":0,\"PhoneHeightInApp\":${dimensions.height},\"PhoneWidthInApp\":${dimensions.width},\"MirrorHeightInApp\":${dimensions.height},\"MirrorWidthInApp\":${dimensions.width},\"MirrorTypeSupport\":2,\"PhoneUUID\":\"\",\"Version\":\"0.5\",\"MirrorHeight\":${dimensions.height},\"MirrorWidth\":${dimensions.width},\"PhoneHeight\":${dimensions.height},\"PhoneWidth\":${dimensions.width},\"Platform\":0,\"PhoneBrand\":\"Android\"},\"CMD\":\"PHONE_INFO\"}",
    )

    fun encodeUpdateNotify(): ByteArray = encodeJson("{\"PARA\":{\"UpdateStatus\":5},\"CMD\":\"UPDATE_NOTIFY\"}")
    fun encodePhoneInfoChange(dimensions: TargetDimensions): ByteArray = encodeJson(
        "{\"PARA\":{\"PhoneWidthInApp\":${dimensions.width},\"PhoneHeightInApp\":${dimensions.height}},\"CMD\":\"PHONE_INFO_CHANGE\"}",
    )
    fun encodeHeartbeat(source: Int = SOURCE_PHONE): ByteArray = encodeJson("{\"CMD\":\"HEARTBEAT\"}", source)
    fun encodeVideoSupportRequest(): ByteArray = encodeJson("{\"PARA\":{\"VideoFormat\":3},\"CMD\":\"VIDEO_SUP_REQ\"}", SOURCE_CAR)
    fun encodeVideoSupportResponse(supported: Boolean): ByteArray = encodeJson("{\"PARA\":{\"VideoFormat\":3,\"VideoSupport\":${if (supported) 1 else 0}},\"CMD\":\"VIDEO_SUP_RSP\"}")
    fun encodeVideoControl(play: Boolean): ByteArray = encodeJson("{\"PARA\":{\"PlayStatus\":${if (play) 1 else 0}},\"CMD\":\"VIDEO_CTRL\"}", SOURCE_CAR)
    fun encodeKeyFrameRequest(): ByteArray = encodeJson("{\"CMD\":\"KEY_FRAME_REQ\"}", SOURCE_CAR)
    fun encodeVideoArgs(parameters: VideoParameters): ByteArray = encodeJson(
        "{\"PARA\":{\"VideoWidth\":${parameters.dimensions.width},\"VideoHeight\":${parameters.dimensions.height}," +
            "\"EncodeType\":3,\"FrameRate\":${parameters.frameRate},\"BitRate\":${parameters.bitRate}," +
            "\"FrameInterval\":${parameters.iFrameIntervalSeconds}},\"CMD\":\"VIDEO_ARGS\"}",
        SOURCE_CAR,
    )
    fun encodeLandModeRequest(mode: Int): ByteArray = encodeJson(
        "{\"PARA\":{\"LandMode\":$mode},\"CMD\":\"LAND_MODE_REQ\"}", SOURCE_CAR,
    )
    fun encodeLandModeResponse(mode: Int): ByteArray = encodeJson(
        "{\"PARA\":{\"LandMode\":$mode},\"CMD\":\"LAND_MODE_RSP\"}",
    )
    fun encodeBtAddress(status: Int, address: String, needAutoConnect: Int): ByteArray = encodeJson(
        "{\"PARA\":{\"Status\":$status,\"Address\":\"${safeJsonValue(address)}\"," +
            "\"NeedAutoConnect\":$needAutoConnect},\"CMD\":\"BT_ADDR\"}", SOURCE_CAR,
    )
    fun encodeDisconnectRequest(): ByteArray = encodeJson("{\"CMD\":\"DISCONNECT_REQ\"}", SOURCE_CAR)
    fun encodeDisconnectResponse(): ByteArray = encodeJson("{\"CMD\":\"DISCONNECT_RSP\"}")

    fun videoParameters(frame: ByteArray): VideoParameters {
        val body = json(frame)
        if (jsonString(body, "CMD") != "VIDEO_ARGS") throw ProtocolException("Not VIDEO_ARGS")
        val encodeType = jsonInt(body, "EncodeType")
        if (encodeType != 3) throw ProtocolException("Unsupported video encoding $encodeType")
        val width = jsonInt(body, "VideoWidth")
        val height = jsonInt(body, "VideoHeight")
        if (width < 2 || height < 2) throw ProtocolException("Video dimensions are too small")
        return VideoParameters(
            TargetDimensions(width and -2, height and -2),
            jsonInt(body, "FrameRate"),
            jsonInt(body, "BitRate"),
            jsonInt(body, "FrameInterval"),
        )
    }

    fun encodeTouch(actionId: Long, pointers: List<TouchPointer>): ByteArray {
        require(pointers.size <= 0xff)
        val full = 21 + pointers.size * 10
        return commonFrame(full, padded512(full), TYPE_TOUCH, SOURCE_CAR, FORMAT_BINARY).apply {
            putU32be(16, actionId)
            this[20] = pointers.size.toByte()
            var offset = 21
            pointers.forEach { pointer ->
                this[offset] = pointer.id.toByte()
                this[offset + 1] = when (pointer.action) {
                    TouchAction.DOWN -> 1
                    TouchAction.UP -> 2
                    TouchAction.MOVE -> 3
                }.toByte()
                putF32be(offset + 2, pointer.x)
                putF32be(offset + 6, pointer.y)
                offset += 10
            }
        }
    }

    fun encodeVideo(dimensions: TargetDimensions, frameRate: Int, annexB: ByteArray): ByteArray {
        require(annexB.isNotEmpty())
        val full = 48 + annexB.size
        return commonFrame(full, padded512(full), TYPE_VIDEO, SOURCE_PHONE, FORMAT_VIDEO, extended = 32).apply {
            putU16be(16, 32)
            this[18] = 1
            putU32be(20, dimensions.width.toLong())
            putU32be(24, dimensions.height.toLong())
            putU16be(28, 90)
            this[30] = 1
            this[31] = 3
            putU32be(32, frameRate.toLong())
            putU32be(36, 0)
            putU32be(40, 0)
            this[44] = 1
            annexB.copyInto(this, 48)
        }
    }

    fun jsonInt(json: String, key: String): Int {
        val regex = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*(-?\\d+)")
        return regex.find(json)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw ProtocolException("JSON integer $key missing")
    }

    fun jsonString(json: String, key: String): String? {
        val regex = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
        return regex.find(json)?.groupValues?.get(1)
    }

    private fun commonFrame(
        fullLength: Int,
        paddedLength: Int,
        messageType: Int,
        source: Int,
        payloadFormat: Int,
        extended: Int = 0,
    ): ByteArray {
        require(fullLength in HEADER_SIZE..FrameAccumulator.MAX_FRAME_SIZE)
        return ByteArray(paddedLength).apply {
            "5A5A".encodeToByteArray().copyInto(this)
            putU32be(4, fullLength.toLong())
            putU16be(8, extended)
            this[10] = messageType.toByte()
            this[11] = source.toByte()
            this[12] = 0
            this[13] = payloadFormat.toByte()
        }
    }

    private fun safeJsonValue(value: String): String {
        require(value.length <= 256 && value.all { it.code in 0x20..0x7e && it != '"' && it != '\\' })
        return value
    }
}
