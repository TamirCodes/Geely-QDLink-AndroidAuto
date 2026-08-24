package io.github.geelyqdlink.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AccumulatorAndSessionTest {
    @Test
    fun `accumulator handles every fragmentation point and coalescing`() {
        val first = V2Codec.encodeCarInfo(TargetDimensions(800, 480))
        val second = V2Codec.encodeVideoSupportRequest()
        for (split in 1 until first.size) {
            val accumulator = FrameAccumulator()
            assertTrue(accumulator.offer(first.copyOfRange(0, split)).isEmpty())
            assertEquals(1, accumulator.offer(first.copyOfRange(split, first.size)).size)
        }
        val accumulator = FrameAccumulator()
        val together = first + second
        val frames = accumulator.offer(together)
        assertEquals(2, frames.size)
        assertEquals("CAR_INFO", V2Codec.command(frames[0]))
        assertEquals("VIDEO_SUP_REQ", V2Codec.command(frames[1]))
    }

    @Test
    fun `V1 state machine waits for HU and reaches streaming`() {
        val machine = QdlinkSessionMachine()
        assertEquals(QdlinkSessionMachine.State.WAITING_HU_FIRST_FRAME, machine.state)
        val initial = machine.onFrame(V1Codec.encodeVersionRequest(1920, 590))
        assertEquals(ProtocolVersion.V1, machine.protocol)
        assertTrue(initial.count { it is SessionAction.Send } == 2)

        val huInfo = V1Codec.encodeHuMessage(HuMessage(appId = "QDRIVE_ASSISTANT", logicId = "HUINFO"))
        assertTrue(machine.onFrame(huInfo).count { it is SessionAction.Send } == 2)
        val play = machine.onFrame(V1Codec.encodeCommandRequest(V1Codec.CMD_PLAY_STATUS))
        assertIs<SessionAction.StartVideo>(play[0])
        assertEquals(QdlinkSessionMachine.State.STREAMING, machine.state)
    }

    @Test
    fun `V2 state machine negotiates video and preserves touch`() {
        val machine = QdlinkSessionMachine()
        val initial = machine.onFrame(V2Codec.encodeCarInfo(TargetDimensions(1760, 720)))
        assertEquals(ProtocolVersion.V2, machine.protocol)
        assertTrue(initial.count { it is SessionAction.Send } == 2)
        assertIs<SessionAction.Send>(machine.onFrame(V2Codec.encodeVideoSupportRequest()).single())
        assertIs<SessionAction.StartVideo>(machine.onFrame(V2Codec.encodeVideoControl(true)).single())
        val touch = machine.onFrame(
            V2Codec.encodeTouch(4, listOf(TouchPointer(2, TouchAction.DOWN, 33.5f, 44.5f))),
        ).single()
        assertIs<SessionAction.Touch>(touch)
        assertEquals(2, touch.value.pointers.single().id)
    }

    @Test
    fun `V2 session applies video args handles orientation Bluetooth and disconnect`() {
        val machine = QdlinkSessionMachine()
        machine.onFrame(V2Codec.encodeCarInfo(TargetDimensions(1280, 720)))
        val parameters = VideoParameters(TargetDimensions(1024, 600), 30, 4_000_000, 2)
        val configuration = machine.onFrame(V2Codec.encodeVideoArgs(parameters))
        assertEquals(parameters, machine.videoParameters)
        assertTrue(configuration.any { it is SessionAction.VideoConfiguration })
        assertIs<SessionAction.Send>(machine.onFrame(V2Codec.encodeLandModeRequest(1)).single())
        assertIs<SessionAction.Diagnostic>(machine.onFrame(V2Codec.encodeBtAddress(1, "00:00:00:00:00:00", 1)).single())
        val disconnect = machine.onFrame(V2Codec.encodeDisconnectRequest())
        assertTrue(disconnect.any { it is SessionAction.Send })
        assertEquals(QdlinkSessionMachine.State.CLOSED, machine.state)
    }
}
