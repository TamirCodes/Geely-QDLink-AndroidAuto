package io.github.geelyqdlink.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CodecGoldenTest {
    @Test
    fun `V1 app status matches source-derived golden fields`() {
        val frame = V1Codec.encodeAppStatus()
        assertEquals(512, frame.size)
        assertEquals(
            "2142494E00000000000002000000020000000040000000800000008000000002",
            frame.copyOfRange(0, 32).hex(),
        )
        assertEquals((0x20..0x3f).joinToString("") { "%02X".format(it) }, frame.copyOfRange(32, 64).hex())
        assertEquals("000000000000000100000001000000180000000200000000", frame.copyOfRange(64, 88).hex())
        assertTrue(frame.copyOfRange(88, 512).all { it == 0.toByte() })
    }

    @Test
    fun `A6A6 golden encoding round trips with CRC`() {
        val encoded = HuMessageCodec.encode(
            HuMessage(flowId = 0, appId = "QDRIVE_ASSISTANT", logicId = "phoneinitok"),
        )
        assertEquals("A6A6000000003310QDRIVE_ASSISTANT0Bphoneinitok004186", encoded.toString(Charsets.US_ASCII))
        assertEquals("phoneinitok", HuMessageCodec.decode(encoded).logicId)
    }

    @Test
    fun `V2 car info is padded but declared length excludes padding`() {
        val frame = V2Codec.encodeCarInfo(TargetDimensions(1920, 590))
        assertEquals(512, frame.size)
        val header = V2Codec.parseHeader(frame)
        assertEquals(1, header.source)
        assertEquals(1, header.payloadFormat)
        assertEquals(TargetDimensions(1920, 590), V2Codec.carDimensions(frame))
        assertEquals("CAR_INFO", V2Codec.command(frame))
    }

    @Test
    fun `V2 multitouch golden values preserve ids actions and floats`() {
        val expected = listOf(
            TouchPointer(3, TouchAction.DOWN, 12.5f, 99.25f),
            TouchPointer(7, TouchAction.MOVE, 1000.0f, 500.0f),
        )
        val decoded = V2Codec.touch(V2Codec.encodeTouch(0x12345678, expected))
        assertEquals(0x12345678, decoded.actionId)
        assertEquals(expected, decoded.pointers)
    }

    @Test
    fun `video packetizers require and retain Annex B`() {
        val nal = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)
        val machineV1 = QdlinkSessionMachine()
        machineV1.onFrame(V1Codec.encodeVersionRequest(800, 480))
        val v1 = machineV1.packetizeVideo(nal, 24)
        assertContentEquals(nal, v1.copyOfRange(512, 520))

        val machineV2 = QdlinkSessionMachine()
        machineV2.onFrame(V2Codec.encodeCarInfo(TargetDimensions(800, 480)))
        val v2 = machineV2.packetizeVideo(nal, 24)
        assertContentEquals(nal, v2.copyOfRange(48, 56))

        assertFailsWith<ProtocolException> { machineV2.packetizeVideo(byteArrayOf(1, 2, 3), 24) }
    }

    @Test
    fun `AVCC access units normalize to Annex B`() {
        val avcc = byteArrayOf(0, 0, 0, 3, 0x67, 1, 2, 0, 0, 0, 2, 0x68, 3)
        assertContentEquals(byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 0, 1, 0x68, 3), AnnexB.normalize(avcc))
        assertFailsWith<ProtocolException> { AnnexB.normalize(byteArrayOf(0, 0, 0, 9, 1)) }
    }

    @Test
    fun `V1 known handshake control application video and touch families round trip`() {
        assertEquals(V1Codec.CMD_VERSION, V1Codec.command(V1Codec.encodeVersionRequest(800, 480)))
        assertEquals(V1Codec.CMD_UPGRADE, V1Codec.command(V1Codec.encodeUpgradeResponse()))
        assertEquals(1, V1Codec.landMode(V1Codec.encodeLandMode(1)))
        assertEquals(V1Codec.CMD_HEARTBEAT, V1Codec.command(V1Codec.encodeHeartbeat()))

        listOf("HUINFO", "phoneinitok", "phoneready", "NEEDUPGRADE", "BT_AUTO_CONNECTED").forEach { logic ->
            val message = HuMessage(
                appId = "QDRIVE_ASSISTANT",
                logicId = logic,
                items = listOf("{\"D\":0,\"T\":\"i\",\"V\":1}"),
            )
            assertEquals(logic, V1Codec.huMessage(V1Codec.encodeHuMessage(message)).logicId)
        }

        val touch = V1Codec.touch(V1Codec.encodeTouch(TouchAction.UP, 321, 123))
        assertEquals(TouchAction.UP, touch.pointers.single().action)
        assertEquals(321f, touch.pointers.single().x)
        val video = V1Codec.encodeVideo(TargetDimensions(800, 480), 24, byteArrayOf(0, 0, 0, 1, 0x65))
        assertEquals(V1Codec.DATA_TYPE_SCREEN_CAPTURE, V1Codec.parseHeader(video).dataType)
    }

    @Test
    fun `V2 known control families encode and negotiated video args validate`() {
        val dimensions = TargetDimensions(1280, 720)
        val parameters = VideoParameters(dimensions, 30, 3_000_000, 2)
        val frames = listOf(
            V2Codec.encodeCarInfo(dimensions) to "CAR_INFO",
            V2Codec.encodePhoneInfo(dimensions) to "PHONE_INFO",
            V2Codec.encodePhoneInfoChange(dimensions) to "PHONE_INFO_CHANGE",
            V2Codec.encodeVideoSupportRequest() to "VIDEO_SUP_REQ",
            V2Codec.encodeVideoSupportResponse(true) to "VIDEO_SUP_RSP",
            V2Codec.encodeVideoArgs(parameters) to "VIDEO_ARGS",
            V2Codec.encodeVideoControl(true) to "VIDEO_CTRL",
            V2Codec.encodeKeyFrameRequest() to "KEY_FRAME_REQ",
            V2Codec.encodeLandModeRequest(1) to "LAND_MODE_REQ",
            V2Codec.encodeLandModeResponse(1) to "LAND_MODE_RSP",
            V2Codec.encodeHeartbeat() to "HEARTBEAT",
            V2Codec.encodeBtAddress(1, "00:00:00:00:00:00", 1) to "BT_ADDR",
            V2Codec.encodeDisconnectRequest() to "DISCONNECT_REQ",
            V2Codec.encodeDisconnectResponse() to "DISCONNECT_RSP",
        )
        frames.forEach { (frame, command) -> assertEquals(command, V2Codec.command(frame)) }
        assertEquals(parameters, V2Codec.videoParameters(V2Codec.encodeVideoArgs(parameters)))
    }
}
