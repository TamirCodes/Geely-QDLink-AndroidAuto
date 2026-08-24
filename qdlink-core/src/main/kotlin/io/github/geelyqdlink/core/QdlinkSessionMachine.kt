package io.github.geelyqdlink.core

class QdlinkSessionMachine {
    enum class State {
        WAITING_HU_FIRST_FRAME,
        HANDSHAKING,
        WAITING_VIDEO,
        STREAMING,
        CLOSED,
    }

    var state: State = State.WAITING_HU_FIRST_FRAME
        private set
    var protocol: ProtocolVersion? = null
        private set
    var dimensions: TargetDimensions? = null
        private set
    var videoParameters: VideoParameters? = null
        private set

    fun onFrame(frame: ByteArray): List<SessionAction> {
        check(state != State.CLOSED) { "Session is closed" }
        return when (protocol) {
            null -> selectProtocol(frame)
            ProtocolVersion.V1 -> handleV1(frame)
            ProtocolVersion.V2 -> handleV2(frame)
        }
    }

    fun heartbeat(): ByteArray = when (protocol) {
        ProtocolVersion.V1 -> V1Codec.encodeHeartbeat()
        ProtocolVersion.V2 -> V2Codec.encodeHeartbeat()
        null -> throw IllegalStateException("Protocol not selected")
    }

    fun packetizeVideo(annexB: ByteArray, frameRate: Int): ByteArray {
        val target = dimensions ?: throw IllegalStateException("Target dimensions unknown")
        if (!annexB.hasAnnexBStartCode()) throw ProtocolException("Video access unit is not Annex-B")
        return when (protocol) {
            ProtocolVersion.V1 -> V1Codec.encodeVideo(target, frameRate, annexB)
            ProtocolVersion.V2 -> V2Codec.encodeVideo(target, frameRate, annexB)
            null -> throw IllegalStateException("Protocol not selected")
        }
    }

    fun close() {
        state = State.CLOSED
    }

    private fun selectProtocol(frame: ByteArray): List<SessionAction> = when {
        marker(frame, "!BIN") -> {
            if (V1Codec.command(frame) != V1Codec.CMD_VERSION) throw ProtocolException("First V1 frame is not VERSION")
            protocol = ProtocolVersion.V1
            val target = V1Codec.versionDimensions(frame)
            dimensions = target
            videoParameters = VideoParameters(target)
            state = State.HANDSHAKING
            listOf(
                SessionAction.Diagnostic("QDLINK_PROTOCOL_SELECTED", "V1"),
                SessionAction.Dimensions(target),
                SessionAction.Send(V1Codec.encodeVersionResponse(target)),
                SessionAction.Send(V1Codec.encodeUpgradeResponse()),
            )
        }
        marker(frame, "5A5A") -> {
            if (V2Codec.command(frame) != "CAR_INFO") throw ProtocolException("First V2 frame is not CAR_INFO")
            protocol = ProtocolVersion.V2
            val target = V2Codec.carDimensions(frame)
            dimensions = target
            videoParameters = VideoParameters(target)
            state = State.WAITING_VIDEO
            listOf(
                SessionAction.Diagnostic("QDLINK_PROTOCOL_SELECTED", "V2"),
                SessionAction.Dimensions(target),
                SessionAction.CarInformation(V2Codec.carIdentity(frame)),
                SessionAction.Send(V2Codec.encodePhoneInfo(target)),
                SessionAction.Send(V2Codec.encodeUpdateNotify()),
            )
        }
        else -> throw ProtocolException("First HU frame has no known marker")
    }

    private fun handleV1(frame: ByteArray): List<SessionAction> {
        val header = V1Codec.parseHeader(frame)
        return when (header.dataType) {
            V1Codec.DATA_TYPE_CMD -> when (V1Codec.command(frame)) {
                V1Codec.CMD_PLAY_STATUS -> {
                    state = State.STREAMING
                    listOf(SessionAction.StartVideo, SessionAction.RequestIdr)
                }
                V1Codec.CMD_KEY_FRAME_REQ -> listOf(SessionAction.RequestIdr)
                V1Codec.CMD_HEARTBEAT -> listOf(SessionAction.Diagnostic("QDLINK_HEARTBEAT_RX"))
                V1Codec.CMD_LAND_MODE -> listOf(SessionAction.Send(V1Codec.encodeLandMode(V1Codec.landMode(frame))))
                V1Codec.CMD_MIRROR_SUPPORT -> {
                    state = State.WAITING_VIDEO
                    listOf(
                        SessionAction.Send(V1Codec.encodeHuMessage(HuMessage(appId = "QDRIVE_ASSISTANT", logicId = "phoneready"), action = 2)),
                        SessionAction.Send(V1Codec.encodeMirrorSupportResponse()),
                        SessionAction.Diagnostic("V1_MIRROR_SUPPORT_RX"),
                    )
                }
                else -> listOf(SessionAction.Diagnostic("V1_COMMAND_UNKNOWN", V1Codec.command(frame).toString()))
            }
            V1Codec.DATA_TYPE_HU_MSG -> {
                val message = V1Codec.huMessage(frame)
                when (message.logicId) {
                    "HUINFO" -> {
                        state = State.HANDSHAKING
                        listOf(
                            SessionAction.Send(V1Codec.encodeHuMessage(HuMessage(appId = "QDRIVE_ASSISTANT", logicId = "phoneinitok"), action = 2)),
                            SessionAction.Send(V1Codec.encodeHuMessage(HuMessage(appId = "QDRIVE_ASSISTANT", logicId = "NEEDUPGRADE", items = listOf("{\"D\":0,\"T\":\"i\",\"V\":1}")), action = 2)),
                        )
                    }
                    "BT_AUTO_CONNECTED" -> listOf(
                        SessionAction.Send(
                            V1Codec.encodeHuMessage(
                                HuMessage(
                                    appId = "QDRIVE_ASSISTANT",
                                    logicId = "BT_AUTO_CONNECTED",
                                    items = listOf("{\"D\":0,\"T\":\"i\",\"V\":1}"),
                                ),
                                action = 2,
                            ),
                        ),
                    )
                    else -> listOf(SessionAction.Diagnostic("V1_HU_LOGIC", message.logicId))
                }
            }
            V1Codec.DATA_TYPE_IN_APP_CONTROL -> listOf(SessionAction.Touch(V1Codec.touch(frame)))
            else -> listOf(SessionAction.Diagnostic("V1_DATA_TYPE_UNKNOWN", header.dataType.toString()))
        }
    }

    private fun handleV2(frame: ByteArray): List<SessionAction> {
        val header = V2Codec.parseHeader(frame)
        if (header.source != V2Codec.SOURCE_CAR) throw ProtocolException("V2 frame did not originate from car")
        if (header.payloadFormat == V2Codec.FORMAT_BINARY && header.messageType == V2Codec.TYPE_TOUCH) {
            return listOf(SessionAction.Touch(V2Codec.touch(frame)))
        }
        if (header.payloadFormat != V2Codec.FORMAT_JSON) {
            return listOf(SessionAction.Diagnostic("V2_PAYLOAD_UNKNOWN", header.payloadFormat.toString()))
        }
        return when (V2Codec.command(frame)) {
            "VIDEO_SUP_REQ" -> listOf(SessionAction.Send(V2Codec.encodeVideoSupportResponse(true)))
            "VIDEO_ARGS" -> {
                val parameters = V2Codec.videoParameters(frame)
                dimensions = parameters.dimensions
                videoParameters = parameters
                listOf(SessionAction.Dimensions(parameters.dimensions), SessionAction.VideoConfiguration(parameters))
            }
            "VIDEO_CTRL" -> {
                val play = V2Codec.jsonInt(V2Codec.json(frame), "PlayStatus") != 0
                state = if (play) State.STREAMING else State.WAITING_VIDEO
                listOf(if (play) SessionAction.StartVideo else SessionAction.StopVideo)
            }
            "KEY_FRAME_REQ" -> listOf(SessionAction.RequestIdr)
            "LAND_MODE_REQ" -> listOf(
                SessionAction.Send(V2Codec.encodeLandModeResponse(V2Codec.jsonInt(V2Codec.json(frame), "LandMode"))),
            )
            "BT_ADDR" -> listOf(SessionAction.Diagnostic("QDLINK_BT_ADDR_RX", "redacted"))
            "DISCONNECT_REQ" -> {
                state = State.CLOSED
                listOf(
                    SessionAction.Send(V2Codec.encodeDisconnectResponse()),
                    SessionAction.StopVideo,
                    SessionAction.Diagnostic("QDLINK_DISCONNECT_RX"),
                    SessionAction.EndSession,
                )
            }
            "HEARTBEAT" -> listOf(SessionAction.Diagnostic("QDLINK_HEARTBEAT_RX"))
            else -> listOf(SessionAction.Diagnostic("V2_COMMAND_UNKNOWN", V2Codec.command(frame)))
        }
    }

    private fun ByteArray.hasAnnexBStartCode(): Boolean =
        (size >= 4 && this[0] == 0.toByte() && this[1] == 0.toByte() && this[2] == 0.toByte() && this[3] == 1.toByte()) ||
            (size >= 3 && this[0] == 0.toByte() && this[1] == 0.toByte() && this[2] == 1.toByte())
}
