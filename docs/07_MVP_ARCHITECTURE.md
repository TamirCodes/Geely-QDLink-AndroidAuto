# MVP and plug-and-play architecture

## Objective

Build only after the Phase-0 USB gate is promoted to GO:

```text
Stock Android APK
  → standard AOA attachment
  → QDLink V1/V2 handshake
  → generated hardware-encoded H.264 frame
  → visible GE13 projection
  → touch events returned and drawn as markers
```

The MVP does not mirror the phone, inject touch into other apps, send QDLink audio, or provide a polished launcher.

## Architecture choice

Use Kotlin/Java only for the first proof, with interfaces that allow a later native packet core if profiling justifies it. Android owns AOA and USB gadget behavior. Reimplement QDPlay's post-AOA protocol cleanly; do not port its configfs/gadget layer.

```text
┌──────────────── Android APK ─────────────────────────────┐
│                                                         │
│ UsbAttachActivity                                       │
│   └─ receives UsbAccessory + starts/resumes session      │
│                         │                               │
│ ConnectedDeviceService ─┼─ owns session lifetime         │
│                         ▼                               │
│ QdlinkTransport                                         │
│   ├─ ParcelFileDescriptor / exact-length streams         │
│   ├─ write queue and 512-byte padding                    │
│   └─ detach/error notifications                          │
│                         │                               │
│ QdlinkSessionMachine                                    │
│   ├─ probe V1/V2                                        │
│   ├─ handshake/capabilities                             │
│   ├─ heartbeat/watchdog                                 │
│   └─ video/touch state                                  │
│              │                         │                │
│              ▼                         ▼                │
│ ProjectionEngine                 VehicleInputReceiver    │
│   ├─ TestPatternRenderer           ├─ raw touch log      │
│   ├─ MediaCodec AVC encoder        ├─ coordinate map     │
│   └─ AnnexBAdapter/packetizer       └─ on-frame marker    │
│                                                         │
│ Optional later                                          │
│   ├─ CustomDrivingUiRenderer (zero-capture-consent path) │
│   ├─ AccessibilityInputBridge                            │
│   └─ MediaProjectionMirror (consent-gated path)          │
└─────────────────────────────────────────────────────────┘
```

## Responsibilities and interfaces

### `UsbAttachActivity`

- Initially declares the official-app filter `Neusoft / QDriveLink / 1.0`; confirms it against target-captured strings before release.
- Is the system entry point for attachment/default routing.
- Records all non-sensitive `UsbAccessory` fields.
- If permission is already present, transfers the request immediately to the service.
- Otherwise invokes the Android-owned permission path.
- Requests landscape for any visible phone-side diagnostic screen.
- Contains no protocol or long-running descriptor ownership.

### `ConnectedDeviceService`

- Foreground service type `connectedDevice`.
- Sole owner of the current `ParcelFileDescriptor` and session job.
- Starts automatically from the attach activity; there is no Connect button.
- Enumerates the current accessory on restart/process recovery.
- Exposes read-only session state to UI/diagnostics.
- Stops or becomes idle after detach, according to measured reconnect behavior.

### `QdlinkTransport`

Minimum interface:

```text
open(accessory)
readExactly(byteCount)
readFrameHeader()
writeFully(buffers)
close(cause)
events: Attached, Opened, EndOfStream, Detached, IoError
```

Requirements:

- One serialized writer to prevent heartbeat/video/control interleaving.
- Separate reader coroutine/thread.
- Exact-length accumulation across short reads.
- Full write loops across short writes.
- Bounded frame sizes and deadlines.
- Monotonic timestamps.
- Never reuse the descriptor after detach.

### `QdlinkSessionMachine`

States:

```text
IDLE
→ ACCESSORY_OPEN
→ PROBING
→ HANDSHAKING_V1 | HANDSHAKING_V2
→ WAITING_VIDEO_REQUEST
→ STREAMING
→ DISCONNECTING
→ IDLE/RETRY
```

State transitions are triggered by validated protocol messages, not UI actions. The state machine owns:

- V1/V2 probe outcome.
- HU/phone dimension record.
- capability replies.
- video activation/deactivation.
- heartbeat and inbound watchdog.
- IDR requests.
- clean reset after any parse/I/O failure.

It must not accept impossible messages silently. Unknown valid messages are logged as type/length only, without personal payloads.

### Protocol codecs

Use independent, deterministic codecs:

- `V1Codec`: `!BIN`, known structures, 512-byte padding.
- `HuMessageCodec`: `A6A6`, strict length and CRC16/XMODEM.
- `V2Codec`: `5A5A`, bounded JSON/binary/video frames.
- `ProtocolProbe`: selects a codec from the first response.

Each codec must support golden vectors and fuzz/property tests without Android dependencies.

### `ProjectionEngine`

M1 uses a generated scene:

```text
QDLinkBridge
GE13 TEST
protocol: V1/V2
encoder: WIDTH×HEIGHT @ FPS
session elapsed / frame counter
touch marker(s)
```

Pipeline:

```text
TestPatternRenderer
  → MediaCodec input Surface
  → AVC output access units
  → AnnexBAdapter + SPS/PPS cache
  → V1/V2 packetizer
  → QdlinkTransport
```

Configure width/height from the negotiated target profile. If the protocol value cannot be trusted, stop with an explicit configuration-required state rather than hard-code 1920x590 or 1760x720 as GE13 truth.

Initial conservative encoder policy, subject to gate capture:

- hardware AVC encoder with Surface input.
- AVC Baseline/level 3.1, matching the official app's first known configuration.
- honor HU `VIDEO_ARGS`; use 24 fps only as a fallback.
- short GOP/regular IDRs.
- send cached `csd-0 + csd-1` as a distinct initial/configuration packet and on HU key-frame request.
- on key-frame request, also issue MediaCodec's explicit sync-frame request.
- use negotiated bitrate; the official fallback value is diagnostic evidence, not a GE13 requirement.

No profile/bitrate value is locked as an exact GE13 requirement because the official configuration has not been observed with `HU716P.00-BEH`.

### `VehicleInputReceiver`

- Preserve V1 DOWN/MOVE/UP and V2 action ID, finger ID, and every finger.
- Log monotonic timestamp, protocol, action, raw coordinates, and pointer ID.
- Maintain a raw HU coordinate space and a mapped viewport space.
- Draw a marker in the generated scene for calibration.
- Do not inject Android gestures in M1-M3.

Coordinate transform:

```text
HU touch space
  → remove HU/projection viewport offset
  → undo crop/letterbox scaling
  → apply orientation transform
  → renderer/Android target space
```

Reject out-of-bounds points and log aggregate counters, not private screen contents.

## Plug-and-play product path

The technically smallest MVP could require manual launch, but the product architecture must exercise auto-attachment from the start:

```text
USB connected
→ Android resolves saved default
→ attach activity starts
→ connected-device service opens accessory
→ protocol probe/handshake runs
→ encoder already primed
→ HU requests play
→ first IDR and custom UI appear
```

Target timing budget, to be measured rather than assumed:

| Stage | Candidate budget |
|---|---:|
| AOA enumeration + Android routing | 1.5 s |
| Open/probe/handshake | 1.5 s |
| encoder first IDR | 1.0 s |
| HU decode/display | 1.0 s |
| Total | preferably <5 s |

Do not delay USB establishment on Bluetooth or Accessibility readiness. Audio and external-app input are optional capabilities.

## Best plug-and-play rendering architecture

The best daily UX is the custom-rendered driving UI, not full screen mirroring:

- It can start immediately after AOA attachment.
- It has no recurring MediaProjection dialog.
- It can be rendered at the native QDLink viewport rather than phone-screen aspect ratio.
- Touch can operate internal controls directly without AccessibilityService.
- Media/navigation integrations can expose safe, purpose-built controls.

True third-party UI rendering is separate. Launching Waze or dispatching gestures to it does not make its pixels available to our encoder. MediaProjection can capture it only after system consent for each new projection session on current Android. Protected surfaces may remain unavailable. Accordingly:

- `CustomDrivingUiRenderer`: primary zero-tap mode.
- `MediaProjectionMirror`: optional explicit “start mirroring” mode with recurring consent.
- `AppIntegration`: deep links/intents/metadata APIs where available, without claiming to render the third-party UI.

## First-time setup experience

One setup wizard should explain and lead the user through, in order:

1. Connect to GE13 and choose this app as the default USB accessory handler.
2. Allow notifications where desired; the foreground-service notification still exists under system policy.
3. Pair/authorize Bluetooth and enable the HU's documented auto-connect setting.
4. Optionally enable AccessibilityService for controlling other apps.
5. Explain that MediaProjection approval is per mirror session and is not needed for the custom UI.

The app must show setup completeness without collecting contacts, messages, or navigation history.

## Reconnect policy

- Cable detach/EOF: cancel session, flush queues, release encoder or reset it to primed state.
- Reattach: create a new session generation; late events from old generation are ignored.
- HU reboot: same path as transport loss; no manual button.
- Temporary read timeout: close and await actual accessory enumeration; avoid infinite writes.
- App process death: service restart enumerates attached accessory and resumes if permitted; otherwise wait for next attach/unlock.
- Phone lock: continue an established FGS/session when OS allows; never bypass keyguard.
- Bluetooth loss: keep video/control alive and report audio unavailable.

Use bounded retries and structured failure reasons. Repeated protocol rejection should stop retrying until a physical reconnect to avoid flooding the HU.

## Structured logging

Required event names:

```text
USB_ACCESSORY_ATTACHED
USB_ACCESSORY_IDENTITY
USB_PERMISSION_REQUIRED / USB_PERMISSION_GRANTED
USB_ACCESSORY_OPENED
QDLINK_PROBE_TX / QDLINK_PROTOCOL_SELECTED
QDLINK_HANDSHAKE_TX / QDLINK_HANDSHAKE_RX
VIDEO_CAPABILITY / VIDEO_SESSION_START / VIDEO_FIRST_IDR
VIDEO_FRAME_SENT / VIDEO_IDR_REQUEST
TOUCH_DOWN / TOUCH_MOVE / TOUCH_UP
QDLINK_HEARTBEAT_TX / QDLINK_HEARTBEAT_RX
QDLINK_DISCONNECTED / QDLINK_RETRY
BLUETOOTH_STATE
```

Every entry includes monotonic timestamp, session generation, protocol version, lengths/counts, and sanitized reason. Never log contacts, messages, media titles by default, destinations, navigation history, raw screen captures, or unredacted nested unknown payloads.

## MVP acceptance

- The target gate is GO.
- The app is chosen as default and later launches without a phone tap.
- It identifies V1/V2 and completes required handshake.
- A generated frame is visible at correct aspect ratio.
- The first visible frame timing is recorded; target is under five seconds.
- Touch DOWN/MOVE/UP is logged and accurately marks the generated UI.
- Cable interruption and HU reboot recover automatically.
- No MediaProjection, Accessibility injection, firmware modification, root, ADB, or HU installation is involved.
