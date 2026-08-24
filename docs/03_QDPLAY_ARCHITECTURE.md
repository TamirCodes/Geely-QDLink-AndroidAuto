# QDPlay architecture and Android portability

Source baseline: QDPlay `develop`, commit `b6464e9c05fe690a83b1d6c1d66227059da30ee1`. Statements about QDPlay are `VERIFIED_FROM_SOURCE`; Android replacements are `INFERRED` until target-tested.

## Process overview

```text
main
 ├─ start VideoReceiver thread (/tmp/qdplay.video.socket)
 ├─ start MessagingService (/tmp/qdplay.messaging.socket)
 ├─ create initial Linux USB gadget (Samsung-like)
 └─ UsbAccessoryWorker loop
      ├─ wait for a local H.264 producer
      ├─ expose/reset initial gadget
      ├─ wait for HU AOA START
      ├─ reconfigure gadget to 18d1:2d00
      ├─ open /dev/usb_accessory
      ├─ probe V1/V2
      ├─ run protocol processor + read/write watchdogs
      └─ teardown and retry
```

QDPlay deliberately waits for a local video producer before starting the USB accessory session. Its README says frames must begin immediately once connected. An Android MVP should instead start a test-pattern producer before or concurrently with the handshake so the encoder is ready when the HU requests play.

## Startup and Linux USB emulation

`main.c` starts the local services and calls `usb_accessory_create_initial_gadget()`. `usb_accessory.c` uses libusbgx/configfs to create gadget `g1`, function `accessory.usb0`, and an `ACCESSORY` configuration.

The first identity is `04e8:6860`, Samsung manufacturer/product/serial. The worker opens `/dev/usb_accessory` and polls the kernel accessory function's `ACCESSORY_IS_START_REQUESTED` ioctl. This indicates that the USB host has sent the standard AOA start request. QDPlay then disables the gadget, changes its identity to `18d1:2d00`, re-enables it, and opens `/dev/usb_accessory` for application traffic.

### Why this exists

This layer is required because an Orange Pi/Linux SBC is replacing an Android phone. Linux does not automatically present the Android USB functions or route AOA host requests to an Android application framework.

On a stock Android phone:

- The phone is already the USB device.
- Android's kernel/framework receives the HU's AOA control requests.
- Android owns the re-enumeration into AOA mode.
- Android exposes the host-provided identity as `UsbAccessory`.
- The chosen application opens the framework-provided `ParcelFileDescriptor`.

A normal APK cannot configure gadget VID/PID or configfs. That is acceptable only if the target follows standard AOA with real phones. This remains the Phase-0 `CONDITIONAL GO` gate.

## Protocol probing

`usb_active_accessory_create_and_wait()` opens the active descriptor, starts a five-second inbound watchdog, and calls `usb_active_accessory_probe()`.

The probe always sends a 512-byte V1 `APP_STATUS`. The next 512 bytes are offered to the V1 and then V2 processors:

- V1 accepts only a big-endian `!BIN` command with action 1 and command `VERSION`.
- V2 accepts a car-sourced `5A5A` JSON `CAR_INFO` and parses `CarWidth`/`CarHeight`.

The selected processor is stored behind a common interface:

```text
probe(buffer)
run()
write_hb()
write_h264(video_parameters, frame)
```

This interface is a useful architecture seam, but the implementation is stateful, global, and unsafe for direct inclusion in an Android process.

## V1 state and services

The V1 processor parses one 512-byte header per `run()` invocation and may synchronously read a remaining padded payload. Its handlers are fixed by data type and command rather than registered services.

Important state transitions:

1. `VERSION`: reply using the HU dimensions; send `UPGRADE`.
2. nested `HUINFO`: reply `phoneinitok` and `NEEDUPGRADE`.
3. `LAND_MODE`: acknowledge.
4. `MIRROR_SUPPORT`: emit `phoneready` and mirror support.
5. `PLAY_STATUS`: activate the video sink and ask the local encoder for a key frame.
6. `KEY_FRAME_REQ`: ask the local encoder for a key frame.
7. `IN_APP_CONTROL`: convert touch to local IPC.
8. heartbeat: note receipt; the independent transmitter sends one after idle.

No explicit enum-driven session state prevents out-of-order commands. A port should implement one.

## V2 state and services

The V2 probe responds to `CAR_INFO` with `PHONE_INFO` and `UPDATE_NOTIFY(5)`. Later JSON handlers implement:

- `VIDEO_SUP_REQ`: report support only for format 3/H.264.
- `VIDEO_CTRL`: activate/deactivate video based on `PlayStatus`.
- `KEY_FRAME_REQ`: notify the producer.
- `HEARTBEAT`: generated for idle output.

Binary message type 2 carries touch. Video is type 1 with payload format 2. The V2 wire can represent multiple fingers, but QDPlay drops every finger except the first when converting to local IPC.

## Video producer path

`video-sender/qd_video_sender.c` connects to `/tmp/qdplay.video.socket`. Every local parcel contains:

- marker `FAFAFAFA`
- event 3
- full/payload lengths
- width, height, frame rate, frame size
- one Annex-B H.264 frame/access unit

`VideoReceiver` reads the complete local parcel. It forwards frames only after the selected processor activates `video_sink_active`. V1 constructs a 512-byte screen header; V2 constructs a 48-byte common+extended header. Both send raw H.264 and pad to 512-byte boundaries where applicable.

### Resolution flaw

V1 `VERSION` and V2 `CAR_INFO` reveal HU dimensions, and QDPlay returns them in its phone response. However, those dimensions are not sent back to the external producer. The producer supplies its own configured width/height on every frame. QDPlay's README therefore correctly says automatic resolution selection is not implemented and ships images configured for known 1920x590 or 1760x720 receivers.

The Android architecture must make negotiated HU dimensions first-class session data shared with renderer, encoder, packetizer, and touch transformer.

## Input path

The processor converts incoming touch to a common local parcel and `MessagingService` broadcasts it to clients on `/tmp/qdplay.messaging.socket`. The included control receiver maps DOWN/MOVE to `pressed=true` and UP to false. This convenience API loses pointer identity, distinct MOVE semantics in the callback, pressure, and all V2 fingers except the first.

An Android implementation should retain the full normalized model:

```text
TouchFrame(actionId, timestamp, pointers[])
Pointer(id, action, huX, huY, optionalPressure)
```

The raw event should first update the internal test marker. Accessibility injection belongs in a later, separately permissioned component.

## Heartbeat, errors, and reconnect

QDPlay tracks the last read/write timestamps globally:

- After three seconds without output, send a protocol heartbeat.
- After five seconds without input, declare the session lost.
- Loss of the local video producer also ends the active session.

Teardown deactivates video, disables the gadget, closes the descriptor, resets the processor, joins threads, and returns to the worker loop.

Risks in the existing lifecycle:

- Worker threads consult unsynchronized or partially synchronized global state.
- A failed probe still leaves the watchdog as the mechanism that unwinds the session.
- Reads and writes assume stronger transfer boundaries than portable streams guarantee.
- There is no exponential backoff or structured reason code.
- Encoder state is external, so IDR/session reset coordination is implicit.

## Component classification

| QDPlay component | Classification for Android | Decision |
|---|---|---|
| libusbgx/configfs gadget creation | Linux emulation only | Do not port |
| Initial Samsung VID/PID/descriptors | Linux emulation hypothesis | Do not reproduce unless target gate proves it is mandatory; if mandatory, normal APK is blocked |
| Reconfiguration to `18d1:2d00` | Android framework responsibility | Do not port |
| `/dev/usb_accessory` and ioctls | Linux kernel interface | Replace with `UsbManager`/`UsbAccessory`/`openAccessory()` |
| AOA strings received from HU | Android framework exposes them | Log from `UsbAccessory`; build exact filter after capture |
| 512-byte stream accumulator | Portable behavior | Reimplement safely in Kotlin |
| V1/V2 probe | Portable protocol logic | Clean-room reimplementation |
| V1/V2 serializers/parsers | Portable protocol logic | Clean-room reimplementation with bounds/CRC tests |
| Nested `A6A6` | Portable protocol logic | Clean-room reimplementation; add CRC verification |
| H.264 packetizers | Portable logic | Reimplement around MediaCodec output |
| Unix video/control sockets | SBC process architecture | Replace with in-process typed interfaces/flows |
| Touch normalization | Portable concept | Reimplement without losing pointer/action data |
| Heartbeat/watchdog | Portable concept | Reimplement with coroutine state machine and monotonic time |
| Bluetooth audio setup | Not implemented | Use Android/vehicle pairing behavior; target-test |

## Code reuse decision

Directly copying QDPlay C would import GPL-3.0 obligations and unsafe assumptions. The protocol surface is small enough for a clean-room Kotlin implementation derived from documented wire facts and verified by golden byte vectors. JNI adds lifecycle complexity without removing the fundamental need to use Android framework AOA APIs.

Recommended Phase-1 baseline: Kotlin protocol/transport plus MediaCodec. Keep a narrow option to add C/C++ only if measured throughput or parsing cost demands it. This is a final recommendation from the completed USB feasibility analysis, not an assumption that Kotlin alone can control USB gadget identity.

## What QDPlay does not establish

- Exact GE13/HU716P AOA strings.
- Whether GE13 filters the phone's pre-AOA VID/PID.
- Which protocol generation the target uses.
- Exact connection-progress completion message.
- H.264 profile, level, SPS/PPS cadence, bitrate, maximum access-unit size, or tolerated frame rates.
- Physical panel size versus video/touch viewport.
- Stock-phone process-death recovery behavior.
- Audio transport through QDLink.

Those questions are reserved for the non-invasive target test plan.
