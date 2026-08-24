package io.github.geelyqdlink.simulator

import io.github.geelyqdlink.core.FrameAccumulator
import io.github.geelyqdlink.core.HuMessage
import io.github.geelyqdlink.core.ProtocolVersion
import io.github.geelyqdlink.core.StreamByteTransport
import io.github.geelyqdlink.core.TargetDimensions
import io.github.geelyqdlink.core.TouchAction
import io.github.geelyqdlink.core.TouchPointer
import io.github.geelyqdlink.core.V1Codec
import io.github.geelyqdlink.core.V2Codec
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

private data class Config(
    val protocol: ProtocolVersion = ProtocolVersion.V2,
    val port: Int = 45_454,
    val dimensions: TargetDimensions = TargetDimensions(1280, 720),
    val frameRate: Int = 24,
    val bitRate: Int = 2_764_800,
    val iFrameInterval: Int = 4,
    val delayMillis: Long = 250,
    val carType: String = "SIMULATED",
    val projectId: String = "LAB",
    val carFeature: String = "NONE",
    val cycles: Int = 1,
    val fault: String? = null,
)

fun main(args: Array<String>) {
    val config = parseArgs(args)
    ServerSocket(config.port).use { server ->
        println(
            "SIMULATOR_LISTEN protocol=${config.protocol} port=${config.port} " +
                "size=${config.dimensions.width}x${config.dimensions.height} cycles=${config.cycles}",
        )
        repeat(config.cycles) { cycle ->
          val stats = Stats()
          server.accept().use { socket ->
            val started = System.nanoTime()
            socket.tcpNoDelay = true
            println("SIMULATOR_CLIENT_CONNECTED cycle=${cycle + 1}")
            val transport = StreamByteTransport(socket.getInputStream(), socket.getOutputStream())
            val reader = thread(name = "sim-reader", isDaemon = true) {
                runCatching {
                    val accumulator = FrameAccumulator()
                    while (true) {
                        val transfer = transport.readTransfer() ?: break
                        accumulator.offer(transfer).forEach { frame ->
                            val detail = describeAndCount(frame, stats)
                            println("SIMULATOR_RX bytes=${frame.size} $detail")
                        }
                    }
                }.onFailure { error ->
                    if (!socket.isClosed) println("SIMULATOR_READER_ERROR ${error.javaClass.simpleName}:${error.message}")
                }
                println("SIMULATOR_CLIENT_EOF")
            }

            fun send(frame: ByteArray, label: String) {
                // Deliberately fragment writes to exercise the client accumulator.
                var offset = 0
                while (offset < frame.size) {
                    val end = minOf(frame.size, offset + 137)
                    transport.writeFully(frame.copyOfRange(offset, end))
                    offset = end
                }
                println("SIMULATOR_TX $label bytes=${frame.size}")
            }

            if (config.fault != null) {
                send(faultFrame(config), "FAULT_${config.fault.uppercase()}")
            } else {
                when (config.protocol) {
                    ProtocolVersion.V1 -> runV1(config, ::send)
                    ProtocolVersion.V2 -> runV2(config, ::send)
                }
            }
            reader.join(4_000)
            transport.close()
            println(
                "SIMULATOR_STATUS protocol=${config.protocol} state=closed resolution=${config.dimensions.width}x${config.dimensions.height} " +
                    "fps=${config.frameRate} bitrate=${config.bitRate} frames=${stats.video.get()} " +
                    "sps=${stats.sps.get()} pps=${stats.pps.get()} idr=${stats.idr.get()} " +
                    "heartbeat=${stats.heartbeat.get()} touch_sent=3 cycle=${cycle + 1}/${config.cycles} " +
                    "duration_ms=${(System.nanoTime() - started) / 1_000_000}",
            )
          }
        }
    }
}

private class Stats {
    val video = AtomicInteger()
    val sps = AtomicInteger()
    val pps = AtomicInteger()
    val idr = AtomicInteger()
    val heartbeat = AtomicInteger()
}

private fun describeAndCount(frame: ByteArray, stats: Stats): String {
    if (frame.copyOfRange(0, 4).decodeToString() == "!BIN") {
        val header = V1Codec.parseHeader(frame)
        if (header.dataType == V1Codec.DATA_TYPE_SCREEN_CAPTURE) {
            val size = u32(frame, 92).coerceAtMost(frame.size - V1Codec.HEADER_SIZE)
            countNals(frame.copyOfRange(V1Codec.HEADER_SIZE, V1Codec.HEADER_SIZE + size), stats)
            stats.video.incrementAndGet()
        } else if (header.dataType == V1Codec.DATA_TYPE_CMD && V1Codec.command(frame) == V1Codec.CMD_HEARTBEAT) {
            stats.heartbeat.incrementAndGet()
        }
        return "v1 type=${header.dataType}"
    }

    val header = V2Codec.parseHeader(frame)
    if (header.payloadFormat == V2Codec.FORMAT_VIDEO && header.fullLength >= 48) {
        countNals(frame.copyOfRange(48, header.fullLength), stats)
        stats.video.incrementAndGet()
        return "v2 video"
    }
    if (header.payloadFormat == V2Codec.FORMAT_JSON) {
        val command = V2Codec.command(frame)
        if (command == "HEARTBEAT") stats.heartbeat.incrementAndGet()
        return "v2 command=$command"
    }
    return "v2 format=${header.payloadFormat}"
}

private fun countNals(bytes: ByteArray, stats: Stats) {
    var index = 0
    while (index + 4 < bytes.size) {
        val start = when {
            bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() && bytes[index + 2] == 1.toByte() -> index + 3
            index + 4 < bytes.size && bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() &&
                bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte() -> index + 4
            else -> {
                index++
                continue
            }
        }
        when (bytes[start].toInt() and 0x1f) {
            5 -> stats.idr.incrementAndGet()
            7 -> stats.sps.incrementAndGet()
            8 -> stats.pps.incrementAndGet()
        }
        index = start + 1
    }
}

private fun u32(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xff) shl 24) or
        ((bytes[offset + 1].toInt() and 0xff) shl 16) or
        ((bytes[offset + 2].toInt() and 0xff) shl 8) or
        (bytes[offset + 3].toInt() and 0xff)

private fun faultFrame(config: Config): ByteArray = when (config.fault) {
    "invalid-length" -> V2Codec.encodeCarInfo(config.dimensions).also { putU32(it, 4, 17) }
    "invalid-json" -> V2Codec.encodeJson("{not-json}", V2Codec.SOURCE_CAR)
    "invalid-touch-count" -> V2Codec.encodeTouch(
        1,
        listOf(TouchPointer(0, TouchAction.DOWN, 1f, 1f)),
    ).also { it[20] = 10 }
    "invalid-video-args" -> V2Codec.encodeJson(
        "{\"PARA\":{\"VideoWidth\":1,\"VideoHeight\":720,\"EncodeType\":3," +
            "\"FrameRate\":24,\"BitRate\":2764800,\"FrameInterval\":4},\"CMD\":\"VIDEO_ARGS\"}",
        V2Codec.SOURCE_CAR,
    )
    else -> error("Unknown or unsupported fault mode ${config.fault}")
}

private fun putU32(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value ushr 24).toByte()
    bytes[offset + 1] = (value ushr 16).toByte()
    bytes[offset + 2] = (value ushr 8).toByte()
    bytes[offset + 3] = value.toByte()
}

private fun runV1(config: Config, send: (ByteArray, String) -> Unit) {
    send(V1Codec.encodeVersionRequest(config.dimensions.width, config.dimensions.height), "VERSION")
    Thread.sleep(250)
    send(V1Codec.encodeHuMessage(HuMessage(appId = "QDRIVE_ASSISTANT", logicId = "HUINFO")), "HUINFO")
    Thread.sleep(250)
    send(V1Codec.encodeLandMode(1, action = 1), "LAND_MODE")
    send(V1Codec.encodeCommandRequest(V1Codec.CMD_MIRROR_SUPPORT), "MIRROR_SUPPORT")
    send(V1Codec.encodeCommandRequest(V1Codec.CMD_PLAY_STATUS), "PLAY_STATUS")
    Thread.sleep(500)
    send(V1Codec.encodeTouch(TouchAction.DOWN, config.dimensions.width / 2, config.dimensions.height / 2), "TOUCH_DOWN")
    send(V1Codec.encodeTouch(TouchAction.MOVE, config.dimensions.width / 2 + 40, config.dimensions.height / 2), "TOUCH_MOVE")
    send(V1Codec.encodeTouch(TouchAction.UP, config.dimensions.width / 2 + 40, config.dimensions.height / 2), "TOUCH_UP")
    send(V1Codec.encodeCommandRequest(V1Codec.CMD_KEY_FRAME_REQ), "KEY_FRAME_REQ")
    send(V1Codec.encodeHeartbeat(), "HEARTBEAT")
    Thread.sleep(2_000)
}

private fun runV2(config: Config, send: (ByteArray, String) -> Unit) {
    send(V2Codec.encodeCarInfo(config.dimensions, config.carType, config.projectId, config.carFeature), "CAR_INFO")
    Thread.sleep(config.delayMillis)
    send(V2Codec.encodeVideoSupportRequest(), "VIDEO_SUP_REQ")
    send(
        V2Codec.encodeVideoArgs(
            io.github.geelyqdlink.core.VideoParameters(
                config.dimensions,
                config.frameRate,
                config.bitRate,
                config.iFrameInterval,
            ),
        ),
        "VIDEO_ARGS",
    )
    send(V2Codec.encodeLandModeRequest(1), "LAND_MODE_REQ")
    send(V2Codec.encodeVideoControl(true), "VIDEO_CTRL_PLAY")
    Thread.sleep(500)
    send(V2Codec.encodeTouch(1, listOf(TouchPointer(2, TouchAction.DOWN, 0.5f, 0.5f))), "TOUCH_DOWN")
    send(V2Codec.encodeTouch(2, listOf(TouchPointer(2, TouchAction.MOVE, 0.6f, 0.5f))), "TOUCH_MOVE")
    send(V2Codec.encodeTouch(3, listOf(TouchPointer(2, TouchAction.UP, 0.6f, 0.5f))), "TOUCH_UP")
    send(V2Codec.encodeKeyFrameRequest(), "KEY_FRAME_REQ")
    send(V2Codec.encodeHeartbeat(source = V2Codec.SOURCE_CAR), "HEARTBEAT")
    Thread.sleep(2_000)
    send(V2Codec.encodeDisconnectRequest(), "DISCONNECT_REQ")
}

private fun parseArgs(args: Array<String>): Config {
    var protocol = ProtocolVersion.V2
    var port = 45_454
    var width = 1280
    var height = 720
    var frameRate = 24
    var bitRate = 2_764_800
    var iFrameInterval = 4
    var delayMillis = 250L
    var carType = "SIMULATED"
    var projectId = "LAB"
    var carFeature = "NONE"
    var cycles = 1
    var fault: String? = null
    args.forEach { argument ->
        when {
            argument == "--v1" -> protocol = ProtocolVersion.V1
            argument == "--v2" -> protocol = ProtocolVersion.V2
            argument.startsWith("--port=") -> port = argument.substringAfter('=').toInt()
            argument.startsWith("--width=") -> width = argument.substringAfter('=').toInt()
            argument.startsWith("--height=") -> height = argument.substringAfter('=').toInt()
            argument.startsWith("--fps=") -> frameRate = argument.substringAfter('=').toInt()
            argument.startsWith("--bitrate=") -> bitRate = argument.substringAfter('=').toInt()
            argument.startsWith("--iframe=") -> iFrameInterval = argument.substringAfter('=').toInt()
            argument.startsWith("--delay-ms=") -> delayMillis = argument.substringAfter('=').toLong()
            argument.startsWith("--car-type=") -> carType = argument.substringAfter('=')
            argument.startsWith("--project-id=") -> projectId = argument.substringAfter('=')
            argument.startsWith("--car-feature=") -> carFeature = argument.substringAfter('=')
            argument.startsWith("--cycles=") -> cycles = argument.substringAfter('=').toInt()
            argument.startsWith("--fault=") -> fault = argument.substringAfter('=')
            else -> error("Unknown argument: $argument")
        }
    }
    return Config(
        protocol,
        port,
        TargetDimensions(width, height),
        frameRate,
        bitRate,
        iFrameInterval,
        delayMillis,
        carType,
        projectId,
        carFeature,
        cycles.coerceIn(1, 100),
        fault,
    )
}
