package io.github.geelyqdlink.lab

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.geelyqdlink.core.TargetDimensions
import io.github.geelyqdlink.core.VideoParameters
import java.security.MessageDigest

class MainActivity : Activity() {
    companion object {
        private const val ACTION_USB_PERMISSION = "io.github.geelyqdlink.lab.USB_PERMISSION"
        private const val EXPORT_DIAGNOSTICS = 71
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var diagnostics: TextView
    private var standaloneRunner: CodecVirtualDisplayRunner? = null
    private var selectedVehicleStage = 3

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val accessory = intent.parcelable(UsbManager.EXTRA_ACCESSORY)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            LabLog.event(if (granted) "USB_PERMISSION_GRANTED" else "USB_PERMISSION_DENIED")
            if (granted && accessory != null) startUsbService(accessory)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(permissionReceiver, IntentFilter(ACTION_USB_PERMISSION))
        }
        LabLog.event("LAB_ACTIVITY_CREATED", "api=${Build.VERSION.SDK_INT}")
        LabLog.event(
            "USB_ACCESSORY_FEATURE",
            packageManager.hasSystemFeature("android.hardware.usb.accessory").toString(),
        )
        handleUsbIntent(intent)
        handleLabMode(intent)
        refreshLog()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        LabLog.event("LAB_ACTIVITY_NEW_INTENT", "action=${intent.action} mode=${intent.getStringExtra("mode")}")
        setIntent(intent)
        handleUsbIntent(intent)
        handleLabMode(intent)
    }

    private fun handleLabMode(intent: Intent) {
        when (intent.getStringExtra("mode")) {
            "own" -> runVirtualDisplay(null)
            "waze" -> runVirtualDisplay("com.waze")
            "simulator" -> startSimulator()
        }
    }

    private fun buildUi() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        status = TextView(this).apply {
            text = "READY FOR VEHICLE"
            textSize = 20f
        }
        column.addView(status, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        column.addView(TextView(this).apply {
            text = "1. Keep phone unlocked.  2. Connect the driver-side QDLink USB port.  " +
                "3. Select this probe if Android asks.  4. Wait, then export diagnostics.\n" +
                "Default: Stage C listens and identifies QDLink without transmitting a handshake or video."
            textSize = 13f
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        fun button(label: String, action: () -> Unit) {
            column.addView(Button(this).apply {
                text = label
                setOnClickListener { action() }
            }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        button("Stage A — enumerate only") { selectStage(1) }
        button("Stage B — open descriptor only") { selectStage(2) }
        button("Stage C — listen / identify (safe default)") { selectStage(3) }
        button("Stage D — handshake, no video") { selectStage(4) }
        button("Stage E — generated test video") { selectStage(5) }
        button("Scan / run selected vehicle stage") { enumerateAccessories() }
        button("Copy diagnostics") { copyDiagnostics() }
        button("Export diagnostics") { exportDiagnostics() }
        button("Run own UI VirtualDisplay (5 s)") { runVirtualDisplay(null) }
        button("Try Waze on VirtualDisplay (5 s)") { runVirtualDisplay("com.waze") }
        button("Connect desktop GE13 simulator") { startSimulator() }
        diagnostics = TextView(this).apply {
            setTextIsSelectable(true)
            textSize = 12f
        }
        column.addView(diagnostics, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun selectStage(stage: Int) {
        selectedVehicleStage = stage
        LabLog.event("VEHICLE_STAGE_SELECTED", stage.toString())
    }

    private fun enumerateAccessories() {
        val manager = getSystemService(UsbManager::class.java)
        val accessories = manager.accessoryList.orEmpty()
        LabLog.event("USB_ACCESSORY_FEATURE", packageManager.hasSystemFeature("android.hardware.usb.accessory").toString())
        LabLog.event("USB_ACCESSORY_ENUMERATION", "count=${accessories.size} stage=$selectedVehicleStage")
        accessories.forEach(::handleAccessory)
    }

    private fun copyDiagnostics() {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("GE13 QDLink diagnostics", LabLog.snapshot()))
        LabLog.event("DIAGNOSTICS_COPIED")
    }

    private fun exportDiagnostics() {
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE, "ge13-qdlink-diagnostics.txt"),
            EXPORT_DIAGNOSTICS,
        )
    }

    @Deprecated("Activity result API retained to avoid an AndroidX dependency in the lab probe")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != EXPORT_DIAGNOSTICS || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(LabLog.snapshot()) }
        }.onSuccess { LabLog.event("DIAGNOSTICS_EXPORTED") }
            .onFailure { LabLog.event("DIAGNOSTICS_EXPORT_ERROR", it.javaClass.simpleName) }
    }

    private fun runVirtualDisplay(targetPackage: String?) {
        standaloneRunner?.close()
        standaloneRunner = CodecVirtualDisplayRunner(
            this,
            VideoParameters(TargetDimensions(1280, 720), frameRate = 24, bitRate = 2_500_000, iFrameIntervalSeconds = 1),
            targetPackage,
            durationMillis = 5_000,
        ) { _, _ -> Unit }.also { runner ->
            runCatching { runner.start() }
                .onFailure {
                    runner.close()
                    LabLog.event("VIRTUAL_DISPLAY_ERROR", it.javaClass.simpleName + ":" + (it.message ?: ""))
                }
        }
    }

    private fun startSimulator() {
        startForegroundService(Intent(this, QdlinkLabService::class.java).setAction(QdlinkLabService.ACTION_SIMULATOR))
    }

    private fun handleUsbIntent(intent: Intent) {
        if (intent.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED) return
        val accessory = intent.parcelable(UsbManager.EXTRA_ACCESSORY) ?: return
        handleAccessory(accessory)
    }

    private fun handleAccessory(accessory: UsbAccessory) {
        logAccessory(accessory)
        if (selectedVehicleStage == 1) return
        val manager = getSystemService(UsbManager::class.java)
        val hasPermission = manager.hasPermission(accessory)
        LabLog.event("USB_ACCESSORY_PERMISSION", "hasPermission=$hasPermission stage=$selectedVehicleStage")
        if (hasPermission) {
            startUsbService(accessory)
        } else {
            LabLog.event("USB_PERMISSION_REQUIRED")
            val permissionIntent = PendingIntent.getBroadcast(
                this,
                0,
                Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            manager.requestPermission(accessory, permissionIntent)
        }
    }

    private fun logAccessory(accessory: UsbAccessory) {
        LabLog.event("USB_ACCESSORY_ATTACHED")
        LabLog.event(
            "USB_ACCESSORY_IDENTITY",
            "manufacturer=${accessory.manufacturer} model=${accessory.model} version=${accessory.version} " +
                "description=${accessory.description} uri=${accessory.uri} " +
                "serial=${fingerprint(runCatching { accessory.serial }.getOrNull())}",
        )
    }

    private fun startUsbService(accessory: UsbAccessory) {
        val service = Intent(this, QdlinkLabService::class.java)
            .setAction(QdlinkLabService.ACTION_USB)
            .putExtra(QdlinkLabService.EXTRA_ACCESSORY, accessory)
            .putExtra(QdlinkLabService.EXTRA_MAX_STAGE, selectedVehicleStage)
        startForegroundService(service)
    }

    private fun refreshLog() {
        val snapshot = LabLog.snapshot()
        diagnostics.text = snapshot
        val result = when {
            "QDLINK_PROTOCOL_SELECTED" in snapshot -> "GE13 QDLINK CONFIRMED" to Color.rgb(0, 190, 80)
            "USB_SESSION_ERROR" in snapshot || "QDLINK_WATCHDOG" in snapshot ->
                "NO USABLE QDLINK ACCESSORY SESSION" to Color.RED
            "USB_ACCESSORY_OPENED" in snapshot || "USB_ACCESSORY_ATTACHED" in snapshot ->
                "ANDROID ACCESSORY CONFIRMED — handshake incomplete" to Color.rgb(255, 193, 7)
            else -> "READY FOR VEHICLE — Stage $selectedVehicleStage selected" to Color.WHITE
        }
        status.text = result.first
        status.setTextColor(result.second)
        handler.postDelayed(::refreshLog, 500)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(permissionReceiver) }
        standaloneRunner?.close()
        super.onDestroy()
    }
}

private fun fingerprint(value: String?): String {
    if (value.isNullOrEmpty()) return "<empty>"
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return "sha256:" + digest.take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun Intent.parcelable(key: String): UsbAccessory? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, UsbAccessory::class.java) else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }
