package io.github.geelyqdlink.lab

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import io.github.geelyqdlink.core.AnnexB
import io.github.geelyqdlink.core.TargetDimensions
import io.github.geelyqdlink.core.VideoParameters
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class CodecVirtualDisplayRunner(
    private val context: Context,
    private val videoParameters: VideoParameters,
    private val targetPackage: String?,
    private val durationMillis: Long,
    private val onAccessUnit: (ByteArray, Boolean) -> Unit,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: LabPresentation? = null
    private var drainThread: Thread? = null
    private var outputFile: File? = null
    private val latestCsd = AtomicReference<ByteArray?>()
    private val resendCsdRequested = AtomicBoolean(false)

    private val dimensions: TargetDimensions get() = videoParameters.dimensions

    val displayId: Int? get() = virtualDisplay?.display?.displayId
    val captureFile: File? get() = outputFile

    fun start() {
        check(running.compareAndSet(false, true))
        try {
            startConfigured()
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun startConfigured() {
        val packageManager = context.packageManager
        val supportsSecondary = packageManager.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS)
        LabLog.event("SECONDARY_DISPLAY_FEATURE", supportsSecondary.toString())
        check(supportsSecondary) { "Device does not advertise activities on secondary displays" }

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, dimensions.width, dimensions.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, videoParameters.bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, videoParameters.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, videoParameters.iFrameIntervalSeconds)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
        }
        val selected = MediaCodecListCompat.findEncoder(format)
        codec = MediaCodec.createByCodecName(selected).also { encoder ->
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            encoder.start()
        }
        LabLog.event(
            "ENCODER_STARTED",
            "name=$selected size=${dimensions.width}x${dimensions.height} fps=${videoParameters.frameRate} " +
                "bitrate=${videoParameters.bitRate} iframe=${videoParameters.iFrameIntervalSeconds}",
        )

        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            if (targetPackage != null) DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC else 0
        val displayManager = context.getSystemService(DisplayManager::class.java)
        virtualDisplay = displayManager.createVirtualDisplay(
            "QDLink-GE13-Lab",
            dimensions.width,
            dimensions.height,
            context.resources.displayMetrics.densityDpi,
            inputSurface,
            flags,
        ) ?: error("createVirtualDisplay returned null")
        val id = virtualDisplay!!.display.displayId
        LabLog.event(
            "VIRTUAL_DISPLAY_CREATED",
            "id=$id flags=${if (targetPackage == null) "PRIVATE" else "PUBLIC"}|OWN_CONTENT_ONLY|PRESENTATION",
        )

        launchTarget(virtualDisplay!!.display)
        val captures = File(context.getExternalFilesDir(null), "captures").apply { mkdirs() }
        outputFile = File(captures, "${targetPackage ?: "own"}-${System.currentTimeMillis()}.h264")
        drainThread = thread(name = "codec-drain", isDaemon = true) { drainEncoder() }
        if (durationMillis > 0) {
            Thread {
                Thread.sleep(durationMillis)
                close()
            }.start()
        }
    }

    fun requestIdr() {
        resendCsdRequested.set(true)
        latestCsd.get()?.let {
            onAccessUnit(it.copyOf(), true)
            resendCsdRequested.set(false)
            LabLog.event("ENCODER_CSD_RESENT", "bytes=${it.size}")
        }
        codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        LabLog.event("ENCODER_IDR_REQUESTED")
    }

    private fun launchTarget(display: android.view.Display) {
        val id = display.displayId
        val intent = if (targetPackage == null) {
            Intent(context, SecondaryLabActivity::class.java)
        } else {
            context.packageManager.getLaunchIntentForPackage(targetPackage)
                ?: throw IllegalStateException("Package $targetPackage is not installed or has no launcher activity")
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

        val manager = context.getSystemService(ActivityManager::class.java)
        val allowed = manager.isActivityStartAllowedOnDisplay(context, id, intent)
        LabLog.event("ACTIVITY_START_ALLOWED", "display=$id package=${targetPackage ?: context.packageName} allowed=$allowed")
        if (!allowed && targetPackage == null) {
            showPresentation(display)
            LabLog.event("PRESENTATION_FALLBACK_SHOWN", "display=$id")
            return
        }
        check(allowed) { "Activity start is not allowed on virtual display $id" }
        context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle())
        LabLog.event("ACTIVITY_LAUNCH_REQUESTED", "display=$id package=${targetPackage ?: context.packageName}")
    }

    private fun showPresentation(display: android.view.Display) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            presentation = LabPresentation(context, display).also { it.show() }
            return
        }
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        Handler(Looper.getMainLooper()).post {
            runCatching { LabPresentation(context, display).also { it.show() } }
                .onSuccess { presentation = it }
                .onFailure { failure.set(it) }
            completed.countDown()
        }
        check(completed.await(3, TimeUnit.SECONDS)) { "Timed out showing Presentation" }
        failure.get()?.let { throw it }
    }

    private fun drainEncoder() {
        val encoder = codec ?: return
        val info = MediaCodec.BufferInfo()
        var frames = 0
        var bytes = 0L
        var sentCsd = false
        FileOutputStream(outputFile).use { output ->
            while (running.get()) {
                when (val index = encoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val csd = listOfNotNull(
                            encoder.outputFormat.getByteBuffer("csd-0")?.copyBytes(),
                            encoder.outputFormat.getByteBuffer("csd-1")?.copyBytes(),
                        )
                        if (csd.isNotEmpty()) {
                            val annexB = AnnexB.combineCodecSpecificData(csd)
                            latestCsd.set(annexB.copyOf())
                            output.write(annexB)
                            onAccessUnit(annexB, true)
                            sentCsd = true
                            LabLog.event("ENCODER_CSD", "bytes=${annexB.size}")
                            if (resendCsdRequested.getAndSet(false)) {
                                onAccessUnit(annexB.copyOf(), true)
                                LabLog.event("ENCODER_CSD_RESENT", "bytes=${annexB.size} pending=true")
                            }
                        }
                    }
                    else -> if (index >= 0) {
                        val buffer = encoder.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val encoded = ByteArray(info.size)
                            buffer.get(encoded)
                            val annexB = AnnexB.normalize(encoded)
                            val config = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            if (!config || !sentCsd) {
                                output.write(annexB)
                                onAccessUnit(annexB, config)
                                sentCsd = sentCsd || config
                            }
                            if (!config) frames++
                            bytes += annexB.size
                        }
                        encoder.releaseOutputBuffer(index, false)
                    }
                }
            }
        }
        LabLog.event("ENCODER_CAPTURE_COMPLETE", "frames=$frames bytes=$bytes file=${outputFile?.name}")
    }

    @Synchronized
    override fun close() {
        if (!running.compareAndSet(true, false)) return
        drainThread?.join(1_500)
        presentation?.dismiss()
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
        inputSurface?.release()
        inputSurface = null
        codec?.let { encoder ->
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
        codec = null
        LabLog.event("VIRTUAL_DISPLAY_RELEASED")
    }
}

private object MediaCodecListCompat {
    fun findEncoder(format: MediaFormat): String {
        val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
        return list.findEncoderForFormat(format) ?: throw IllegalStateException("No AVC Surface encoder for requested format")
    }
}

private fun java.nio.ByteBuffer.copyBytes(): ByteArray {
    val duplicate = duplicate()
    val bytes = ByteArray(duplicate.remaining())
    duplicate.get(bytes)
    return bytes
}
