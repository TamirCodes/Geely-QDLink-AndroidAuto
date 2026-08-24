# Phase 0.5 lab app and GE13 simulator

## Scope

These artifacts are diagnostic scaffolding, not a product application:

- `qdlink-core/`: pure Kotlin/JVM wire codecs and session state machine;
- `tools/ge13-simulator/`: pure Kotlin TCP simulator for V1 or V2 HU behavior;
- `lab-app/`: minimal Android USB/VirtualDisplay/MediaCodec probe.

No QDPlay source file was copied. The implementation is clean-room against the protocol map and source-verified field layouts. QDPlay remains GPL evidence only.

## Build

Requirements:

- JDK 17;
- Android SDK Platform 36;
- Android Build Tools 35/36;
- Gradle wrapper 8.13.

```powershell
.\gradlew.bat :qdlink-core:test :tools:ge13-simulator:installDist :lab-app:assembleDebug
```

Outputs:

```text
tools/ge13-simulator/build/install/ge13-simulator/
lab-app/build/outputs/apk/debug/lab-app-debug.apk
qdlink-core/build/reports/tests/test/index.html
```

## Simulator

V2 default:

```powershell
.\tools\ge13-simulator\build\install\ge13-simulator\bin\ge13-simulator.bat --v2 --width=1280 --height=720
```

V1:

```powershell
.\tools\ge13-simulator\build\install\ge13-simulator\bin\ge13-simulator.bat --v1 --width=1920 --height=590
```

The simulator:

- listens on TCP port 45454;
- sends the HU's first `VERSION` or `CAR_INFO` frame;
- deliberately fragments each write into 137-byte chunks;
- drives video capability/play, touch DOWN/MOVE/UP, key-frame request, and heartbeat paths;
- accepts/logs phone replies and video frames without decoding personal data.
- accepts fake lab parameters such as `--fps`, `--bitrate`, `--iframe`, `--delay-ms`, `--car-type`, `--project-id`, and `--car-feature`;
- supports `--cycles=N` for reconnect sessions and bounded V2 fault modes `invalid-length`, `invalid-json`, `invalid-touch-count`, and `invalid-video-args`;
- reports received video count and observed SPS, PPS, IDR, heartbeat, touch, and session duration.

Connect an Android device over ADB only in the lab:

```powershell
adb reverse tcp:45454 tcp:45454
adb shell am start -n io.github.geelyqdlink.lab/.MainActivity --es mode simulator
```

ADB is a lab convenience and is not part of the vehicle/product architecture.

## Android modes

### USB/AOA probe

The activity declares the official static filter discovered in Phase 0.25:

```text
manufacturer=Neusoft
model=QDriveLink
version=1.0
```

On attachment it logs all six `UsbAccessory` fields (serial as a one-way fingerprint), requests Android-owned permission when needed, and starts a `connectedDevice` foreground service. A foreground scan button also enumerates `accessoryList`, so a first-run filter mismatch remains diagnosable.

The vehicle path exposes progressive Stage A–E controls. Stage C is the default: open, read and identify the HU's first complete frame while suppressing all generated QDLink replies. Stage D enables the clean-room handshake without video; Stage E enables generated AVC only after the HU play command. The service listens first and sends no blind APP_STATUS probe.

The screen includes color-coded result status plus **Copy diagnostics** and **Export diagnostics**. Export uses Android's document picker and no network upload.

### Own UI VirtualDisplay

```powershell
adb shell am start -n io.github.geelyqdlink.lab/.MainActivity --es mode own
```

Creates a 1280x720 own-content-only VirtualDisplay, checks Activity launch eligibility, and falls back to an app-owned `Presentation` when the OEM denies Activity placement. It encodes five seconds of AVC and writes a raw Annex-B diagnostic capture under the app's external files `captures` directory.

### Waze eligibility experiment

```powershell
adb shell am start -n io.github.geelyqdlink.lab/.MainActivity --es mode waze
```

This only asks Android whether Waze may launch on a public display and, if allowed, launches its normal entry Activity for five seconds. It does not start navigation, enter a destination, or collect Waze data. On the tested Android 16 phone the preflight returned `false`, so Waze was not launched.

## Structured logs

Filter:

```powershell
adb logcat -s QDLinkLab:I '*:S'
```

Important events include:

```text
USB_ACCESSORY_ATTACHED / USB_ACCESSORY_IDENTITY
USB_PERMISSION_REQUIRED / USB_PERMISSION_GRANTED
USB_TRANSFER_RX / QDLINK_PROTOCOL_SELECTED
QDLINK_DIMENSIONS / QDLINK_TX
VIDEO_SESSION_START / ENCODER_STARTED / ENCODER_CSD
VIDEO_FRAME_SENT / ENCODER_CSD_RESENT / ENCODER_IDR_REQUESTED
TOUCH_FRAME / SECONDARY_TOUCH
QDLINK_HEARTBEAT_TX / QDLINK_WATCHDOG / QDLINK_DISCONNECTED
```

Unknown protocol payload bodies and third-party screen pixels are not logged.

## Safety

- Never connect the simulator transport to vehicle networks; it is localhost/TCP only.
- The real USB mode uses only public Android accessory APIs.
- The app contains no gadget/configfs, root, ADB-on-HU, CAN, firmware, or ECU code.
- The vehicle probe does not fuzz or send unknown commands. Simulator fault modes are localhost-only parser tests.
- Vehicle testing must be parked and follow `08_TEST_PLAN_GE13.md`.
