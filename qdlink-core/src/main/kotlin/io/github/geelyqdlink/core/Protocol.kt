package io.github.geelyqdlink.core

enum class ProtocolVersion { V1, V2 }

enum class TouchAction { DOWN, UP, MOVE }

data class TouchPointer(
    val id: Int,
    val action: TouchAction,
    val x: Float,
    val y: Float,
)

data class TouchFrame(
    val actionId: Long?,
    val pointers: List<TouchPointer>,
)

data class TargetDimensions(val width: Int, val height: Int) {
    init {
        require(width in 1..16_384 && height in 1..16_384)
    }
}

data class VideoParameters(
    val dimensions: TargetDimensions,
    val frameRate: Int = 24,
    val bitRate: Int = 2_764_800,
    val iFrameIntervalSeconds: Int = 4,
) {
    init {
        require(frameRate in 1..120)
        require(bitRate in 64_000..100_000_000)
        require(iFrameIntervalSeconds in 0..60)
    }
}

data class CarIdentity(
    val carType: String?,
    val projectId: String?,
    val carFeature: String?,
)

sealed interface SessionAction {
    data class Send(val bytes: ByteArray) : SessionAction
    data class Dimensions(val value: TargetDimensions) : SessionAction
    data class VideoConfiguration(val value: VideoParameters) : SessionAction
    data class CarInformation(val value: CarIdentity) : SessionAction
    data object StartVideo : SessionAction
    data object StopVideo : SessionAction
    data object RequestIdr : SessionAction
    data object EndSession : SessionAction
    data class Touch(val value: TouchFrame) : SessionAction
    data class Diagnostic(val event: String, val detail: String = "") : SessionAction
}

class ProtocolException(message: String) : IllegalArgumentException(message)
