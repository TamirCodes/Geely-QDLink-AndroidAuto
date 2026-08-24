# GE13 target probe instructions

This is the non-invasive first-vehicle procedure for the exact target:

```text
Geely Geometry C 480 2024 / FES5PL
GE13
SWGE13A0623H8BEH.00019
HWGE131128M0191
HU716P.00-BEH
```

The probe uses only Android's public `UsbAccessory` API and QDLink infotainment traffic. It contains no HU firmware operation, CAN/ECU access, root, ADB-on-HU, gadget configuration, or engineering-menu dependency. Run it while parked.

## Build and install status

`OBSERVED_IN_LAB`: `lab-app-debug.apk` builds, installs, and runs on the connected stock Android 16 / API 36 phone. The installed lab version is `0.5-lab`, target SDK 36. The exact vehicle path remains `UNKNOWN` until physical testing.

Build:

```powershell
.\gradlew.bat :qdlink-core:test :lab-app:assembleDebug
```

APK:

```text
lab-app/build/outputs/apk/debug/lab-app-debug.apk
```

ADB was used only for lab installation and simulator validation. The user-facing vehicle procedure does not require ADB or a laptop after the APK is installed.

## Safety stages

The screen exposes explicit progressive stages:

| Stage | Operation | Transmits QDLink data? |
|---|---|---:|
| A | Enumerate and record the public `UsbAccessory` only | No |
| B | Request permission and prove `openAccessory()` succeeds, then close | No |
| C | Open, read, accumulate, validate, and identify the first HU frame | No; generated replies are suppressed |
| D | Perform the known clean-room handshake | Yes; no video renderer |
| E | Start the generated 1280x720-or-negotiated AVC test pattern after HU play control | Yes |

Stage C is the safe default for the first connection. Do not select Stage D or E until the exported Stage C diagnostics have been reviewed.

The probe listens first. It does not send QDPlay's historical blind APP_STATUS probe. It waits for a complete valid HU `VERSION` (`!BIN`, V1) or `CAR_INFO` (`5A5A`, V2) block.

## Exact 60-second first-car procedure

1. Park the vehicle, switch the GE13 on normally, and keep the phone unlocked.
2. Open **QDLink GE13 Lab** once. Confirm the header says `READY FOR VEHICLE` and Stage C is selected.
3. Connect the phone with a known-good data cable to the driver-side QDLink USB port.
4. If Android shows both **QDLink** and **QDLink GE13 Lab**, select the lab app. Select the persistent/default option if Android offers it. Do not uninstall or disable official QDLink for this first run.
5. Approve the Android USB-accessory prompt if shown. Do not approve MediaProjection; the probe does not request it.
6. Wait up to 15 seconds without pressing Stage D/E.
7. Read the status color/text and press **Copy diagnostics** or **Export diagnostics**.
8. Disconnect the cable normally. Share only the exported sanitized text for analysis.

If the manifest chooser/attach route does not expose the accessory, leave the cable attached, open the app in the foreground, keep Stage C selected, and press **Scan / run selected vehicle stage**. This manual enumeration path prevents a filter mismatch from hiding public accessories.

## Result interpretation

### Green — GE13 QDLINK CONFIRMED

Expected evidence:

```text
USB_ACCESSORY_ATTACHED
USB_ACCESSORY_IDENTITY
USB_ACCESSORY_OPENED
USB_TRANSFER_RX
QDLINK_PROTOCOL_SELECTED V1 or V2
```

This proves public AOA/PFD access and recognition of a known initial QDLink frame. It does not by itself prove the complete handshake or video compatibility. Review the diagnostic trace before selecting Stage D.

### Yellow — ANDROID ACCESSORY CONFIRMED

Android exposed/opened the accessory but no known complete QDLink frame was identified. Likely categories include official-app ownership conflict, timeout, an unexpected transfer boundary, or a target protocol variant. Export diagnostics. Do not infer architectural NO-GO from one run.

### Red — NO USABLE QDLINK ACCESSORY SESSION

The run reached a permission/open/read/parse/timeout failure. Repeat with the official QDLink baseline, the same cable and port, then repeat the probe twice. A `NO-GO` decision requires reproducible evidence that official QDLink works while a normal APK cannot receive/service the session through public APIs.

## Data collected and privacy

The probe records:

- accessory feature, attach/enumeration timing, permission, and descriptor result;
- manufacturer, model, version, description, URI, and a one-way short fingerprint of the accessory serial rather than the raw value;
- transport read sizes, first validated marker, protocol generation, sanitized command/type and dimensions;
- stage, heartbeat/watchdog, video start/IDR, touch event type/coordinates, and disconnect cause.

It does not automatically upload anything. It does not log contacts, messages, destinations, Waze content, full unknown payloads, raw Bluetooth addresses, CarUUID, or a device serial. Export uses Android's document picker; copy uses the system clipboard.

## Official QDLink conflict

Both applications match `Neusoft / QDriveLink / 1.0`. Android may show a chooser on the first attach. Record whether the default persists. Do not uninstall or modify official QDLink automatically. If the official app always takes ownership, test disabling it only after the user explicitly chooses that diagnostic step.

## Gate decision after the probe

- Promote to `GO` only after a public accessory appears, its PFD opens, and a valid QDLink exchange occurs without gadget control.
- Keep `CONDITIONAL GO` when AOA works but protocol evidence is incomplete.
- Consider `NO-GO` only after repeatable controlled evidence proves a required VID/PID, configfs, FunctionFS, private endpoint, or other capability unavailable to a normal APK.

Until the vehicle test, the project remains `LIKELY — missing target validation`.
