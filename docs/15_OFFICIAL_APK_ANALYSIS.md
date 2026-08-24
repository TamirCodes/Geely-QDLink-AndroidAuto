# Official QDLink APK analysis

Analysis date: 2026-08-24  
Package: `com.neusoft.qdrivelink`  
Scope: Phase 0.25; interoperability research only

## Evidence and legal handling

This document uses two additional labels:

- `VERIFIED_FROM_OFFICIAL_APK_STATIC`: established from the manifest, resources, DEX decompilation, or native-library metadata of the official installed APK.
- `VERIFIED_FROM_OFFICIAL_APK_DYNAMIC`: observed while running that APK on the connected stock test phone, without a vehicle.

The package was installed by `com.android.vending`. Its manifest contains the Google Play distribution-stamp metadata and source URL. The base and all installed splits were pulled with ADB from the test phone. No mirror site was used. APKs, decompiled sources, and the proprietary native library were kept in temporary analysis directories and are not stored in this repository.

This is not a source-code audit: JADX produced a useful but imperfect reconstruction and reported 88 decompilation errors among 3,829 classes. Apktool decoded the manifest/resources but warned about unresolved split resources. Findings were cross-checked between manifest/resources, multiple DEX paths, raw strings, and runtime observations where possible. Decompiled control flow that looks inconsistent is not treated as exact behavior without a second signal.

## Provenance and artifact identity

`VERIFIED_FROM_OFFICIAL_APK_STATIC`:

| Property | Value |
|---|---|
| Distribution | Google Play installed package |
| Installer | `com.android.vending` |
| Version | `1.9.7` |
| Version code | `107` |
| Minimum SDK | 21 |
| Target SDK | 35 |
| Installed ABI split | `arm64-v8a` |
| Base size | 11,459,669 bytes |
| Base SHA-256 | `C6F9D75FFB49E95D2B909A828EA475E915C56DCD91C7412ACDE2FF451A200B86` |

All installed artifacts:

| APK | Bytes | SHA-256 |
|---|---:|---|
| `base.apk` | 11,459,669 | `C6F9D75FFB49E95D2B909A828EA475E915C56DCD91C7412ACDE2FF451A200B86` |
| `split_config.arm64_v8a.apk` | 369,061 | `7177A2184E2372720A83BE0A112F4D6F1AC3A69DD365E0DB64EE2209B7EBF270` |
| `split_config.xxhdpi.apk` | 57,754 | `EB1CFBAA11A3B8BEB3084E5D2C1EABE9B1CD8A747122D9E50D3794B80C2CEF7D` |
| `split_config.ar.apk` | 37,273 | `22F7C2936237DA7B9165376A95EA1A7E7256A54C12D74116B214791E2C7B0015` |
| `split_config.en.apk` | 33,177 | `E6DA7A56BF26F14D0C1D8227F0B053FC72339BF545D7241F8E554CF001FD5C06` |
| `split_config.iw.apk` | 33,177 | `B64021738A9237B6C638864127ED5266EABDEDF80249F4F5A0D45B3B6F6E4F10` |
| `split_config.ru.apk` | 37,273 | `634847DBF6F4F8E070D61FE98721A97406C05906A3590F6EECE0692C48DDF986` |

The dynamic test device was a stock, non-root Android 16 / API 36 arm64 phone with `android.hardware.usb.accessory`. No device serial or personal data is recorded here.

## Manifest and application surface

`VERIFIED_FROM_OFFICIAL_APK_STATIC`:

- `SplashActivity` is the launcher and is fixed to landscape.
- `MainActivity` is fullscreen, landscape, resizeable, exported, and `singleInstance`.
- `ConnectActivity` is exported, transparent, landscape, `singleInstance`, and owns the `USB_ACCESSORY_ATTACHED` filter.
- `ScreenCaptureService` is declared with foreground-service type `mediaProjection`.
- `MouseAccessibilityService` requires `BIND_ACCESSIBILITY_SERVICE`.
- Other exported services include `PhoneService`, `DLinkNotifyL`, `RotateScreenService`, and `MusicPlayService`.
- A media-button receiver, non-exported `FileProvider`, and AndroidX Startup provider are present.
- Permissions cover USB-adjacent lifecycle needs, networking/Wi-Fi Direct, Bluetooth, wake lock, overlays/settings/usage access, foreground service/media projection, phone/contacts, notifications, audio, location, and package visibility.
- The package declares `QUERY_ALL_PACKAGES` and allows cleartext network traffic. These are official-app implementation choices, not requirements for the clean-room MVP.

The manifest contains some third-party service credentials. They are intentionally not reproduced because they are irrelevant to interoperability and may be sensitive.

### Accessibility declaration caveat

The active accessibility XML requests all event types, all feedback types, window-content retrieval, and enhanced web accessibility. It does **not** visibly declare `android:canPerformGestures="true"`, even though the service calls `dispatchGesture()`. Android normally requires the corresponding capability. This is a `VERIFIED_FROM_OFFICIAL_APK_STATIC` configuration discrepancy, not proof that reverse control fails: split-resource decoding, OEM behavior, or another route could affect runtime. It must be checked on the target phone after the user enables the service.

## 1–3. Exact AOA filter and public USB path

The manifest-referenced filter is exactly:

```xml
<usb-accessory manufacturer="Neusoft" model="QDriveLink" version="1.0" />
```

`VERIFIED_FROM_OFFICIAL_APK_STATIC`: a second resource contains `AndroidLink / AndroidLink / 1.0`, but it is not referenced by any manifest component in this build. It must not be used as the primary identity without an observed attach route.

The official USB implementation uses only the public phone-side accessory path:

```text
USB_ACCESSORY_ATTACHED → ConnectActivity
    → UsbManager.getAccessoryList()
    → UsbManager.hasPermission()
    → UsbManager.openAccessory()
    → ParcelFileDescriptor
    → FileInputStream + FileOutputStream
    → QDLink parser/writer
```

It retries enumeration up to five times with a 50 ms delay, registers detach and power-disconnect receivers, and closes the streams and descriptor on disconnect. No `requestPermission()` call was found in this transport class; it relies on the system attachment/default-handler route to supply permission. No configfs, FunctionFS, `/dev/usb_accessory`, VID/PID setting, gadget ioctl, or raw endpoint access was found in the application.

This independently confirms that an official QDLink phone client expects Android itself to complete AOA negotiation before application code runs.

## 4–7. Protocol generations, constants, and model routing

### Both V1 and V2 are present

`VERIFIED_FROM_OFFICIAL_APK_STATIC`: the reader starts in a default state, reads an initial 512-byte block on USB, and selects:

- V1 from the ASCII marker `!BIN`.
- V2 from the ASCII marker `5A5A`.

It then retains the selected parser until disconnect/reset. Wi-Fi mode reads the V2 base header first or assembles a complete V1 block, but the same application-protocol parsers are used.

### Independent confirmation of QDPlay constants

The official APK independently contains and uses:

- V1/topology markers: `!BIN`, `A6A6`, `QDRIVE_ASSISTANT`, `HUINFO`, `phoneinitok`, `phoneready`, `phonereadynew`, `NEEDUPGRADE`, and `BT_AUTO_CONNECTED`.
- V2 marker/messages: `5A5A`, `CAR_INFO`, `PHONE_INFO`, `PHONE_INFO_CHANGE`, `VIDEO_SUP_REQ`, `VIDEO_SUP_RSP`, `VIDEO_ARGS`, `VIDEO_CTRL`, `KEY_FRAME_REQ`, `LAND_MODE_REQ`, `LAND_MODE_RSP`, `HEARTBEAT`, `BT_ADDR`, `DISCONNECT_REQ`, and `DISCONNECT_RSP`.

This is strong independent confirmation of the main QDPlay wire vocabulary. Literal `PLAY_STATUS` and `MIRROR_SUPPORT` strings were not found in this build; equivalent state exists as fields/messages rather than those exact names.

### GE13 and exact-target identifiers

`VERIFIED_FROM_OFFICIAL_APK_STATIC`: `GE13-J2` appears in a model-family routing array together with `G836`, `SS11R1`, `SX11RC`, `VX11_R1`, and `X70MC2`. These identifiers influence UI/monitoring/compatibility branches. This proves some GE13-specific application logic exists.

`UNKNOWN`: `FES5PL`, `HU716P`, `HU716P.00-BEH`, `SWGE13A0623H8BEH`, and `Geometry C` do not appear as plain strings. `GE13-J2` is not enough to equate this installed build with the exact target firmware. V2 `CAR_INFO` can provide `CarType`, `ProjectID`, `CarUUID`, `CarFeature`, platform/factory information, and display dimensions, so additional routing can be server/database driven rather than compiled as a literal.

## 8–10. Video transport and codec

### H.264 reaches the selected QDLink transport

`VERIFIED_FROM_OFFICIAL_APK_STATIC`: the encoder callback enters the same link writer used by the protocol. When link mode is USB, bytes are written to the AOA `FileOutputStream`; when link mode is Wi-Fi, they are written to the Wi-Fi stream. Therefore the official client can send H.264 directly over AOA. A Wi-Fi path is also implemented, but is not required by this code path for USB mode.

V1 reserves a 512-byte transport/header area and pads output to a 512-byte boundary. V2 reserves 48 bytes: a 16-byte V2 base header plus a 32-byte video extension header, then similarly pads USB writes. This agrees with QDPlay at the framing level.

### Negotiation and exact encoder configuration

V2 `CAR_INFO` supplies car dimensions and mirror-mode requirements. The app returns `PHONE_INFO`. The HU then supplies `VIDEO_ARGS` with width, height, encoding type, frame rate, bit rate, and frame interval. `VIDEO_SUP_RSP` uses video format `3`, representing H.264 in this implementation. `VIDEO_CTRL` with play state starts capture/encoding.

The real streaming encoder is configured as follows (`VERIFIED_FROM_OFFICIAL_APK_STATIC`):

| Parameter | Official behavior |
|---|---|
| MIME | `video/avc` |
| Input | `COLOR_FormatSurface` |
| Width/height | Dynamic, derived from negotiated car/video values and made even |
| Bit rate | HU-supplied; fallback object default 2,764,800 bit/s |
| Frame rate | HU-supplied when positive; otherwise 24 fps |
| I-frame interval | HU-supplied; fallback object default 4 s |
| AVC profile | Baseline (`1`) on API 23+ |
| AVC level | 3.1 (`512`) on API 23+ |
| Bitrate mode | VBR (`1`) |
| Complexity | `1` |
| Input Surface | Produced by MediaCodec and fed through the app's GL/display path |

A separate 800×480, 1.5 Mbit/s, 24 fps, baseline/level-3.1 encoder configuration is only a capability probe. It must not be mistaken for the live stream.

### SPS/PPS, NAL representation, and key-frame request

The app reads `csd-0` and `csd-1` from the MediaCodec output format, concatenates SPS and PPS, and sends that configuration as a separate QDLink video packet before ordinary access units. Codec-config output buffers are suppressed. Encoder output is otherwise forwarded without an AVCC-to-Annex-B conversion routine. Combined with QDPlay's start-code checks, this strongly supports an Annex-B/start-code-compatible stream, but an on-wire capture is still needed to prove every phone codec's output representation.

On `KEY_FRAME_REQ`, the official implementation only clears its “SPS sent” flag, causing SPS/PPS to be resent. No call to MediaCodec's sync-frame request parameter was found. A periodic encoder IDR may follow because of the I-frame interval, but an immediate IDR is not guaranteed by the visible code. Phase 0.5 should implement both SPS/PPS resend and an explicit MediaCodec IDR request, then test what GE13 accepts.

## 9. USB versus Wi-Fi

`VERIFIED_FROM_OFFICIAL_APK_STATIC`: the application contains a full alternative Wi-Fi Direct transport using UDP discovery/control and a TCP server socket. The common link layer selects USB (`0`) or Wi-Fi (`1`) and then reads/writes the same V1/V2 protocol family over the selected streams.

`VERIFIED_FROM_OFFICIAL_APK_DYNAMIC`: ordinary app launch without a vehicle initialized Wi-Fi Direct and opened a UDP listener. It did not start MediaProjection or MediaCodec.

`UNKNOWN`: no unconditional “USB bootstrap then mandatory switch to Wi-Fi” was found. The code supports separate link modes and can stream over AOA directly. Which mode `HU716P.00-BEH` requests, and whether some GE13 configurations advertise Wi-Fi after USB identification, require a vehicle session capture.

## 11. Touch and reverse control

### Wire representation

`VERIFIED_FROM_OFFICIAL_APK_STATIC`: V2 touch data uses:

```text
u32 action
u8  fingerCount
repeat fingerCount times:
    u8 pointerId
    u8 pointerAction
    f32 x, big-endian
    f32 y, big-endian
```

Per-pointer action values map as `1 → DOWN`, `2 → UP`, `3 → MOVE`. The parser can decode multiple fingers, but several delivery branches only forward pointer ID `0` into the capture/input layer. V1 also maps received numeric coordinates/actions into Android `MotionEvent` values.

### Application control

For its own projected activity/display, the capture service can consume reconstructed `MotionEvent`s directly. For other apps, `MouseAccessibilityService` builds gesture strokes and calls `AccessibilityService.dispatchGesture()`. It also maps HU phone keys to global Back, Home, Recents, and Power-dialog actions. The gesture builder has storage for multiple pointer IDs and coalesces short motion sequences instead of sending every move independently.

The user must enable the accessibility service. Dynamic analysis found it disabled on the test phone and did not change that setting. The missing `canPerformGestures` XML declaration remains a test risk.

## 12. Orientation and landscape

`VERIFIED_FROM_OFFICIAL_APK_STATIC`:

- The launcher, attachment, and main activities are manifest-locked to landscape.
- V2 accepts `LAND_MODE_REQ` and replies with orientation state.
- The screen-capture pipeline tracks sensor/display orientation, angle, source dimensions, and in-app versus out-of-app presentation type in every video header.
- An overlay-based `RotateScreenService` attempts to influence phone orientation on OEM-specific builds when overlay permission permits.
- In-app rendering uses a `DisplayManager` virtual display; out-of-app mirroring uses a `MediaProjection` virtual display.

Thus landscape is both an Android activity policy and a negotiated/video-header property; it is not a single hard-coded transform.

## 13. Disconnect, watchdog, and reconnect

`VERIFIED_FROM_OFFICIAL_APK_STATIC`:

- Detach or power-disconnect closes both streams and the PFD, resets selected protocol/session flags, stops capture, cancels timers, and notifies the connection manager.
- V2 sends a heartbeat every 3 seconds.
- A watchdog runs every 5 seconds and tears down USB or Wi-Fi mode if no V2 read activity has occurred for more than 5 seconds.
- Enumeration has a short retry loop; a new accessory attach creates fresh streams rather than reusing a stale descriptor.
- `DISCONNECT_REQ/RSP` is present at the application layer.

The dynamic no-car launch started three bound services and reached `MainActivity`; after the explicit test force-stop the process was gone. Process-death recovery while a real accessory remains attached is still `UNKNOWN`.

## 14. Bluetooth and audio

`VERIFIED_FROM_OFFICIAL_APK_STATIC`: V1 carries `CARBTADDRESS` and `BT_AUTO_CONNECTED`. V2 carries `BT_ADDR` with status, address, and “need auto-connect” fields. `DLinkNotifyL` uses Android Bluetooth APIs to enable/observe the adapter, pair with the HU address, monitor profile broadcasts, and operate an A2DP proxy. It retries profile connection behavior and reports state back over QDLink.

No media-audio payload path analogous to H.264 was found in the QDLink link layer. Speech control/status messages exist, but early product audio should remain Bluetooth. Pairing, Nearby Devices approval, and any HU prompt are user/system controlled.

## Native library analysis

Exactly one native library was installed:

| File | ABI | Size | Role |
|---|---|---:|---|
| `libsspLib.so` | arm64-v8a | 336,680 bytes extracted | SSP typed-value serialization/deserialization used by nested V1 `A6A6` application messages |

`VERIFIED_FROM_OFFICIAL_APK_STATIC`: ELF inspection identifies a 64-bit little-endian AArch64 shared object. Its needed libraries are standard runtime libraries (`libc`, `libm`, `libdl`). The dynamic table contains 72 undefined imports, all ordinary C/C++/pthread/property/runtime functions, and 589 global/weak definitions dominated by the bundled C++ runtime, cJSON, SSP typed-value helpers, and JNI bridges. `JNI_OnLoad`, a native-method registration table, create/get/add/release/iterate/translate functions, and both pointer-width APIs are visible. No libusb, gadget/configfs, socket, MediaCodec, or Android USB JNI dependency was found. The protocol markers and USB/video paths instead remain visible in DEX.

This is evidence for a narrow native serializer, not evidence that JNI is required for our USB transport. QDPlay already exposes enough SSP framing/typing behavior to implement a bounded clean-room Kotlin serializer if licensing and tests favor that route.

## Non-invasive dynamic-analysis result

`VERIFIED_FROM_OFFICIAL_APK_DYNAMIC` on stock Android 16, without a vehicle:

- A cold launch reached `SplashActivity` and then landscape `MainActivity`.
- The process ran as an ordinary `third_party_app` under the Android sandbox.
- `ScreenCaptureService`, `MusicPlayService`, and `PhoneService` were bound/created.
- No active MediaProjection session and no AVC codec instance were observed.
- Wi-Fi Direct initialization and a UDP listener were observed before vehicle connection.
- The QDLink accessibility service was disabled; it was not enabled by the analysis.
- Several already granted runtime permissions were detected; phone/call permissions remained denied. No permission was silently changed.
- Reading `/proc/<pid>/maps` was denied by the stock sandbox, so native-load observation through that file was unavailable. Static split inspection independently establishes the library.
- The app was force-stopped after analysis and no process remained.

No protocol bytes, accessory strings delivered by the real GE13, Bluetooth pairing, encoder output, or touch events could be observed without the car. Historical USB events visible in the phone's system dump were not attributed to the target and are excluded from target evidence.

## 15. Effect on the Phase-0 gate

**Decision remains `CONDITIONAL GO`; project conclusion remains `LIKELY — missing target validation`.**

The confidence is materially higher:

1. The official APK itself proves that a normal application uses public `UsbAccessory` APIs and receives the complete QDLink byte stream without controlling gadget identity.
2. Its exact manifest filter is now known: `Neusoft / QDriveLink / 1.0`.
3. Both QDPlay protocol generations and the major constants are independently confirmed.
4. Direct H.264-over-AOA and reverse-touch paths are independently confirmed.

It is not promoted to unconditional `GO` because no live attach to the exact `HU716P.00-BEH` was captured. We still need to observe that this target supplies the expected AOA strings, opens a public descriptor, and accepts our clean-room handshake. If the user has already observed the official app working on this exact HU, that is strong product evidence, but it is not a captured protocol trace in this audit.

## 16. Required changes to Phase 0.5

Phase 0.5 should proceed **modified**, not unchanged:

1. Make the simulator expose `Neusoft / QDriveLink / 1.0`; keep the unused `AndroidLink` identity only as an explicit optional test case.
2. Have the probe auto-detect the first 512-byte `!BIN` or `5A5A` block and support both generations.
3. Implement V2 `CAR_INFO → PHONE_INFO → VIDEO_SUP_REQ/RSP → VIDEO_ARGS → VIDEO_CTRL`, not fixed dimensions alone.
4. Treat video format `3` as H.264 and honor HU width, height, fps, bitrate, and I-frame interval.
5. Send SPS+PPS as a distinct video packet before access units and resend them on `KEY_FRAME_REQ`; also request an actual IDR.
6. Parse V2 touch as big-endian floats with pointer/action fields; log all pointers even if the first MVP acts only on pointer 0.
7. Preserve orientation, angle, in-app/out-of-app, and source dimensions in video headers.
8. Implement the 3-second heartbeat and 5-second read watchdog, plus full reset on detach.
9. Treat Wi-Fi Direct as an optional alternative transport. Do not make it a mandatory USB bootstrap stage without target evidence.
10. Log `CarType`, `ProjectID`, `CarFeature`, and whether `GE13-J2` appears; do not hard-code it as the exact FES5PL identity.
11. Keep Bluetooth passive in the first probe: log requests/status but do not initiate pairing.
12. Keep the zero-tap custom-rendered path independent of MediaProjection. External-app pixels remain a separate consent-gated problem.

No Phase 0.5 code was created during this analysis.

## Remaining unknowns requiring the car

- Exact six AOA strings emitted by `HU716P.00-BEH`, and whether they remain stable.
- Whether this target begins with V1 or V2 and its complete byte-for-byte handshake order.
- Whether `CAR_INFO.CarType` is `GE13-J2`, another identifier, or server-configured.
- Actual car/projection/touch dimensions, crop/letterbox rules, and orientation values.
- Exact encoder argument values, acceptable NAL representation, and key-frame recovery timing.
- Whether the GE13 uses USB for the full stream or requests Wi-Fi Direct.
- Whether its touch path includes multiple pointers and whether the official accessibility configuration performs gestures on the selected phone build.
- Bluetooth pairing/profile sequence and audio ownership.
- Default-handler conflict behavior when the official and clean-room apps both match the same exact filter.
