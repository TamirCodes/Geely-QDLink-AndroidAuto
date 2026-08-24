package io.github.geelyqdlink.lab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import io.github.geelyqdlink.core.ByteTransport
import io.github.geelyqdlink.core.FrameAccumulator
import io.github.geelyqdlink.core.QdlinkSessionMachine
import io.github.geelyqdlink.core.SessionAction
import io.github.geelyqdlink.core.StreamByteTransport
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class QdlinkLabService : Service() {
    companion object {
        const val ACTION_USB = "io.github.geelyqdlink.lab.USB"
        const val ACTION_SIMULATOR = "io.github.geelyqdlink.lab.SIMULATOR"
        const val EXTRA_ACCESSORY = "accessory"
        const val EXTRA_MAX_STAGE = "max_stage"
        private const val CHANNEL = "qdlink_lab"
        private const val NOTIFICATION_ID = 51
    }

    @Volatile private var transport: ByteTransport? = null
    @Volatile private var renderer: CodecVirtualDisplayRunner? = null
    @Volatile private var sessionGeneration = 0L

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "QDLink lab connection", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val generation = ++sessionGeneration
        closeSession("replacement")
        when (intent?.action) {
            ACTION_SIMULATOR -> {
                startForType("Desktop simulator", ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                thread(name = "qdlink-simulator-client") {
                    try {
                        runCatching {
                            val socket = Socket("127.0.0.1", 45_454).apply { tcpNoDelay = true }
                            LabLog.event("SIMULATOR_CONNECTED", "generation=$generation")
                            runSession(StreamByteTransport(socket.getInputStream(), socket.getOutputStream()), generation, 5)
                            socket.close()
                        }.onFailure {
                            val event = if (it is SocketException) "SIMULATOR_SESSION_ENDED" else "SIMULATOR_ERROR"
                            LabLog.event(event, it.javaClass.simpleName + ":" + (it.message ?: ""))
                        }
                    } finally {
                        stopSelf(startId)
                    }
                }
            }
            ACTION_USB -> {
                startForType("GE13 USB accessory", ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                val accessory = intent.parcelableAccessory(EXTRA_ACCESSORY)
                val maxStage = intent.getIntExtra(EXTRA_MAX_STAGE, 3).coerceIn(2, 5)
                thread(name = "qdlink-usb-session") {
                    try {
                        runCatching { openUsb(accessory, generation, maxStage) }
                            .onFailure { LabLog.event("USB_SESSION_ERROR", it.javaClass.simpleName + ":" + (it.message ?: "")) }
                    } finally {
                        stopSelf(startId)
                    }
                }
            }
            else -> {
                startForType("Waiting for lab transport", ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                LabLog.event("SERVICE_NO_ACTION")
            }
        }
        return START_NOT_STICKY
    }

    private fun openUsb(accessory: UsbAccessory?, generation: Long, maxStage: Int) {
        requireNotNull(accessory) { "Missing UsbAccessory" }
        val manager = getSystemService(UsbManager::class.java)
        check(manager.hasPermission(accessory)) { "USB accessory permission missing" }
        val pfd = manager.openAccessory(accessory) ?: error("openAccessory returned null")
        LabLog.event("USB_ACCESSORY_OPENED", "generation=$generation stage=$maxStage")
        if (maxStage == 2) {
            pfd.close()
            LabLog.event("USB_DESCRIPTOR_PROBE_COMPLETE", "generation=$generation")
            return
        }
        runSession(UsbAccessoryTransport(pfd), generation, maxStage)
    }

    private fun runSession(activeTransport: ByteTransport, generation: Long, maxStage: Int) {
        if (generation != sessionGeneration) {
            activeTransport.close()
            return
        }
        transport = activeTransport
        val accumulator = FrameAccumulator()
        val machine = QdlinkSessionMachine()
        val lastRx = AtomicLong(android.os.SystemClock.elapsedRealtime())
        val lastTx = AtomicLong(android.os.SystemClock.elapsedRealtime())
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        scheduler.scheduleAtFixedRate({
            if (generation != sessionGeneration) return@scheduleAtFixedRate
            val now = android.os.SystemClock.elapsedRealtime()
            if (maxStage >= 4 && machine.protocol != null && now - lastTx.get() >= 3_000) {
                runCatching {
                    activeTransport.writeFully(machine.heartbeat())
                    lastTx.set(now)
                    LabLog.event("QDLINK_HEARTBEAT_TX", "generation=$generation")
                }
            }
            if (now - lastRx.get() >= 5_000) {
                LabLog.event("QDLINK_WATCHDOG", "generation=$generation")
                activeTransport.close()
            }
        }, 1, 1, TimeUnit.SECONDS)

        try {
            while (generation == sessionGeneration) {
                val transfer = activeTransport.readTransfer() ?: break
                lastRx.set(android.os.SystemClock.elapsedRealtime())
                LabLog.event("USB_TRANSFER_RX", "bytes=${transfer.size}")
                accumulator.offer(transfer).forEach { frame ->
                    machine.onFrame(frame).forEach { action ->
                        handleAction(action, machine, activeTransport, generation, maxStage)
                        if (action is SessionAction.Send) lastTx.set(android.os.SystemClock.elapsedRealtime())
                    }
                }
            }
        } finally {
            scheduler.shutdownNow()
            renderer?.close()
            renderer = null
            activeTransport.close()
            if (transport === activeTransport) transport = null
            LabLog.event("QDLINK_DISCONNECTED", "generation=$generation pending=${accumulator.pendingBytes}")
        }
    }

    private fun handleAction(
        action: SessionAction,
        machine: QdlinkSessionMachine,
        activeTransport: ByteTransport,
        generation: Long,
        maxStage: Int,
    ) {
        when (action) {
            is SessionAction.Send -> {
                if (maxStage >= 4) {
                    activeTransport.writeFully(action.bytes)
                    LabLog.event("QDLINK_TX", "bytes=${action.bytes.size}")
                } else {
                    LabLog.event("QDLINK_TX_SUPPRESSED", "stage=$maxStage bytes=${action.bytes.size}")
                }
            }
            is SessionAction.Dimensions -> LabLog.event("QDLINK_DIMENSIONS", "${action.value.width}x${action.value.height}")
            is SessionAction.VideoConfiguration -> LabLog.event(
                "QDLINK_VIDEO_ARGS",
                "${action.value.dimensions.width}x${action.value.dimensions.height} fps=${action.value.frameRate} " +
                    "bitrate=${action.value.bitRate} iframe=${action.value.iFrameIntervalSeconds}",
            )
            is SessionAction.CarInformation -> LabLog.event(
                "QDLINK_CAR_INFO",
                "CarType=${action.value.carType ?: "<missing>"} ProjectID=${action.value.projectId ?: "<missing>"} " +
                    "CarFeature=${action.value.carFeature ?: "<missing>"}",
            )
            SessionAction.StartVideo -> {
                if (maxStage >= 5) {
                    LabLog.event("VIDEO_SESSION_START", "generation=$generation")
                    startRenderer(machine, activeTransport, actionParameters(machine))
                } else {
                    LabLog.event("VIDEO_SESSION_SUPPRESSED", "stage=$maxStage")
                }
            }
            SessionAction.StopVideo -> {
                renderer?.close()
                renderer = null
                LabLog.event("VIDEO_SESSION_STOP")
            }
            SessionAction.RequestIdr -> renderer?.requestIdr()
            SessionAction.EndSession -> activeTransport.close()
            is SessionAction.Touch -> {
                val pointers = action.value.pointers.joinToString { "id=${it.id},${it.action},x=${it.x},y=${it.y}" }
                LabLog.event("TOUCH_FRAME", pointers)
            }
            is SessionAction.Diagnostic -> LabLog.event(action.event, action.detail)
        }
    }

    private fun startRenderer(machine: QdlinkSessionMachine, activeTransport: ByteTransport, parameters: io.github.geelyqdlink.core.VideoParameters) {
        if (renderer != null) return
        renderer = CodecVirtualDisplayRunner(this, parameters, targetPackage = null, durationMillis = 0) { accessUnit, config ->
            runCatching {
                val packet = machine.packetizeVideo(accessUnit, 24)
                activeTransport.writeFully(packet)
                LabLog.event(if (config) "VIDEO_CONFIG_SENT" else "VIDEO_FRAME_SENT", "bytes=${packet.size}")
            }.onFailure { LabLog.event("VIDEO_SEND_ERROR", it.javaClass.simpleName + ":" + (it.message ?: "")) }
        }.also { it.start() }
    }

    private fun actionParameters(machine: QdlinkSessionMachine): io.github.geelyqdlink.core.VideoParameters =
        machine.videoParameters ?: error("Video started before parameters")

    private fun notification(text: String): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle("QDLink GE13 Lab")
        .setContentText(text)
        .setOngoing(true)
        .build()

    private fun startForType(text: String, type: Int) {
        startForeground(NOTIFICATION_ID, notification(text), type)
    }

    @Synchronized
    private fun closeSession(reason: String) {
        renderer?.close()
        renderer = null
        transport?.close()
        transport = null
        LabLog.event("SESSION_RESET", reason)
    }

    override fun onDestroy() {
        sessionGeneration++
        closeSession("service_destroy")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

private fun Intent.parcelableAccessory(key: String): UsbAccessory? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, UsbAccessory::class.java) else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }
