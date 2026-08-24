package io.github.geelyqdlink.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

class MalformedInputTest {
    @Test
    fun `A6A6 rejects bad CRC truncation invalid hex and oversized lengths`() {
        val valid = HuMessageCodec.encode(HuMessage(appId = "QDRIVE_ASSISTANT", logicId = "HUINFO"))

        assertFailsWith<ProtocolException> { HuMessageCodec.decode(valid.copyOf(valid.size - 1)) }

        val badCrc = valid.copyOf().also { it[it.lastIndex] = if (it.last() == '0'.code.toByte()) '1'.code.toByte() else '0'.code.toByte() }
        assertFailsWith<ProtocolException> { HuMessageCodec.decode(badCrc) }

        val invalidHex = valid.copyOf().also { it[4] = 'Z'.code.toByte() }
        assertFailsWith<ProtocolException> { HuMessageCodec.decode(invalidHex) }

        val oversized = valid.copyOf().also { "7FFFFFFF".encodeToByteArray().copyInto(it, 6) }
        assertFailsWith<ProtocolException> { HuMessageCodec.decode(oversized) }
    }

    @Test
    fun `V1 rejects mismatched and overflowing declarations`() {
        val frame = V1Codec.encodeVersionRequest(800, 480)
        assertFailsWith<ProtocolException> { V1Codec.parseHeader(frame.copyOf(511)) }
        assertFailsWith<ProtocolException> {
            V1Codec.parseHeader(frame.copyOf().also { it.putU32be(8, 0xffff_ffffL) })
        }
    }

    @Test
    fun `V2 rejects malformed length padding touch count and non-finite coordinates`() {
        val valid = V2Codec.encodeTouch(1, listOf(TouchPointer(0, TouchAction.DOWN, 1f, 2f)))
        assertFailsWith<ProtocolException> { V2Codec.parseHeader(valid.copyOf().also { it.putU32be(4, 15) }) }
        assertFailsWith<ProtocolException> { V2Codec.parseHeader(valid.copyOf().also { it[it.lastIndex] = 1 }) }
        assertFailsWith<ProtocolException> { V2Codec.touch(valid.copyOf().also { it[20] = 2 }) }
        assertFailsWith<ProtocolException> { V2Codec.touch(valid.copyOf().also { it.putU32be(23, 0x7fc0_0000) }) }

        assertFails {
            V2Codec.videoParameters(
                V2Codec.encodeJson(
                    "{\"PARA\":{\"VideoWidth\":1,\"VideoHeight\":720,\"EncodeType\":3," +
                        "\"FrameRate\":24,\"BitRate\":2764800,\"FrameInterval\":4},\"CMD\":\"VIDEO_ARGS\"}",
                    V2Codec.SOURCE_CAR,
                ),
            )
        }
    }

    @Test
    fun `bounded mutation property never escapes as unsafe runtime failure`() {
        val seeds = listOf(
            V1Codec.encodeVersionRequest(1920, 590),
            V2Codec.encodeCarInfo(TargetDimensions(1760, 720)),
            V2Codec.encodeTouch(9, listOf(TouchPointer(1, TouchAction.MOVE, 5f, 6f))),
        )
        val random = Random(0x51444c)
        repeat(2_000) {
            val seed = seeds[it % seeds.size]
            val mutated = seed.copyOf(random.nextInt(0, seed.size + 1))
            repeat(random.nextInt(1, 5)) {
                if (mutated.isNotEmpty()) {
                    val at = random.nextInt(mutated.size)
                    mutated[at] = random.nextInt(256).toByte()
                }
            }
            runCatching {
                when {
                    marker(mutated, "!BIN") -> V1Codec.parseHeader(mutated)
                    marker(mutated, "5A5A") -> V2Codec.parseHeader(mutated)
                    else -> throw ProtocolException("unknown")
                }
            }.exceptionOrNull()?.let { error ->
                if (error !is ProtocolException && error !is IllegalArgumentException) {
                    assertFails("Unexpected exception type ${error::class.qualifiedName}") { throw error }
                }
            }
        }
    }
}
