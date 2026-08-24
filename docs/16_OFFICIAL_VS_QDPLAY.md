# Official QDLink vs QDPlay vs clean-room plan

Analysis date: 2026-08-24  
Official APK: `com.neusoft.qdrivelink` 1.9.7 (107)  
QDPlay: `develop` at `b6464e9c05fe690a83b1d6c1d66227059da30ee1`

## Comparison

| Area | Official QDLink | QDPlay | Current clean-room implementation plan |
|---|---|---|---|
| Evidence label | `VERIFIED_FROM_OFFICIAL_APK_STATIC` unless noted | `VERIFIED_FROM_SOURCE` | Proposed; target validation pending |
| AOA identity/filter | Manifest filter: `Neusoft / QDriveLink / 1.0`; unused resource also has `AndroidLink / AndroidLink / 1.0` | Does not read HU AOA strings; Linux gadget starts Samsung-like and changes to Google AOA VID/PID | Declare exact official filter initially; capture all six strings on GE13 before freezing it |
| USB attach | Android system launches `ConnectActivity`; app enumerates `UsbAccessory` | Linux configfs/gadget worker receives AOA requests and opens `/dev/usb_accessory` | Android attachment activity receives system intent; no gadget/configfs code |
| USB API | Public `UsbManager`, `hasPermission`, `openAccessory`, PFD streams | Kernel/configfs plus `/dev/usb_accessory` | Public `UsbAccessory` and framework-owned PFD only |
| Protocol detection | Reads initial 512 bytes and detects `!BIN` or `5A5A` | Separate V1/V2 processors selected by first marker | Auto-detect both, with bounded exact-length accumulator |
| V1/V2 support | Both | Both | Both in probe; avoid assuming GE13 generation |
| V1 nested service | Confirms `A6A6`, `QDRIVE_ASSISTANT`, `HUINFO`, readiness/upgrade/BT logic; typed SSP values partly use native library | Implements `A6A6`, IDs, data items, XMODEM CRC, major service messages | Clean-room bounded Kotlin framing/SSP codec; golden vectors from independent observations |
| V2 handshake | `CAR_INFO`, returns `PHONE_INFO`; handles support, args, control, land mode, BT, heartbeat, disconnect | Implements corresponding JSON/control messages | Follow official ordering and fields; log unknown fields verbatim/safely |
| Service discovery | V1 app/logic IDs; V2 JSON commands | Same high-level families | State-machine events, not UI-driven calls |
| Video support | Replies with format `3` and starts only after negotiated args/control | H.264 support response and video start | Advertise format `3` after encoder capability check |
| H.264 transport | Directly writes encoder packets to AOA in USB mode; alternate Wi-Fi writer exists | Sends external Annex-B producer data over AOA | MediaCodec surface encoder → QDLink packetizer → AOA PFD |
| V1 video framing | 512-byte header region and 512-byte padding | 512-byte V1 packets/padding | Match observed header and full-write semantics; never depend on write atomicity |
| V2 video framing | 16-byte base + 32-byte extension, payload at byte 48, USB padding to 512 | V2 base/extended video framing | Match official field layout; validate total/extended lengths before allocation |
| Codec format | AVC Baseline, level 3.1, surface input, VBR; negotiated bitrate/fps/I-frame interval | Does not encode; expects Annex-B H.264 from local producer | Hardware MediaCodec, Baseline/3.1 first; negotiate and validate HU values |
| Default codec values | 24 fps fallback, 2,764,800 bit/s and 4 s interval in parameter object; HU values take precedence | Example producer/settings vary | Defaults are fallback only; record actual GE13 `VIDEO_ARGS` |
| Dimensions | V2 consumes HU car/video dimensions and reports phone/mirror dimensions; adjusts to even encoder size | Receives dimensions but external producer propagation is incomplete | Separate HU, viewport, encoder, and touch spaces; dynamic configuration mandatory |
| SPS/PPS | Sends `csd-0 + csd-1` separately before frames; resends on key-frame request | Requires/handles SPS/PPS and HU key-frame request | Send config separately and on request; cache safely across format changes |
| IDR request | Visible key-frame path resets SPS flag; no explicit MediaCodec sync-frame request found | Relays request to external producer | Reset SPS/PPS **and** call MediaCodec sync-frame request; verify on wire |
| NAL layout | Forwards MediaCodec buffers without visible AVCC conversion | Producer validates Annex-B start codes | Normalize and test; accept codec-config and output-format variations |
| Touch V1 | Numeric action/coordinates converted to MotionEvent | Decodes key/touch and forwards to control socket | Decode/log first; apply calibrated mapping later |
| Touch V2 | `u32 action`, finger count, then pointer/action plus big-endian float x/y | Same conceptual multi-touch structure, but primarily forwards first pointer | Parse every pointer; MVP renders marker/log, no Android injection |
| Multitouch | Parser supports multiple records; delivery branches often limit to pointer ID 0 | Only first event effectively forwarded | Preserve all records in API; mark target support unknown |
| Reverse control | Own-display MotionEvents; `AccessibilityService.dispatchGesture()` for other apps | Sends decoded events to external consumer | Custom UI direct input; optional user-enabled Accessibility gesture reconstruction |
| Accessibility declaration | Service exists, but active XML lacks visible `canPerformGestures=true` | Not applicable | Declare capability correctly; never automate enablement/security prompts |
| Orientation | Activities landscape; land-mode messages; sensor/display angle in video header; overlay service for some phones | V1/V2 orientation fields exist but incomplete | Off-screen landscape renderer; explicit transform matrix; preserve negotiated fields |
| Bluetooth/audio | Receives HU address/auto-connect policy; pairs/monitors A2DP; no comparable media payload stream found | V1 BT auto-connect exchange; README uses Bluetooth audio | Early milestones log BT control only; use normal paired Bluetooth for audio |
| Wi-Fi | Full Wi-Fi Direct UDP/TCP alternative; common protocol over selected USB/Wi-Fi streams | AOA only for HU side | USB first; isolate optional Wi-Fi transport until GE13 proves it is required |
| Heartbeat/watchdog | V2 heartbeat 3 s; read watchdog 5 s | Heartbeat/watchdog/session reset present | Match timing initially and make it observable/configurable |
| Disconnect | Detach closes PFD/streams, cancels capture/timers, clears parser and notifies manager | Worker teardown/retry resets services | Structured cancel scope; close PFD to unblock reads; fresh session on attach |
| Reconnect | Short enumeration retry plus full state reset; actual live target behavior untested | Recreates accessory/gadget workers | Attachment-driven reopen, clean state, new encoder IDR/config; test process death/HU reboot |
| MediaProjection | Required for out-of-app/whole-display path and asks Android for capture consent | Not an Android app; accepts an external stream | Optional external-app mode only; cannot meet guaranteed zero-tap reconnect |
| Custom UI capture | In-app path uses app-owned/DisplayManager virtual display without MediaProjection | External producer determines pixels | Primary product path: render owned UI directly to encoder Surface, zero capture prompt |
| Lifecycle | Attach activity → services/manager → selected transport → video service; landscape main UI | Native daemon processes/workers and local sockets | Thin attachment Activity + connected-device FGS + test renderer + isolated protocol state machine |
| Native code | One arm64 `libsspLib.so` for SSP typed-value handling; USB/video are Java framework paths | C implementation throughout | Kotlin-first clean-room; JNI only if measured need, never for USB gadget emulation |
| Exact GE13 evidence | Contains `GE13-J2` routing literal; no FES5PL/HU716P/Geometry C literals | No exact target identifiers | Log target `CAR_INFO`; retain `CONDITIONAL GO` until HU716P capture |

## Agreements that materially reduce risk

1. Both independent implementations recognize V1 `!BIN` and V2 `5A5A`.
2. The official APK independently confirms QDPlay's principal V1 nested constants and V2 message names.
3. Both send H.264 over the established QDLink stream and receive touch in the reverse direction.
4. Both separate transport/session control from video/control payloads.
5. Both use heartbeat/watchdog teardown rather than attempting to continue on a stale stream.
6. Official code proves the Android equivalent begins at public `UsbAccessory`; QDPlay's configfs layer is Linux-phone emulation only.

## Discrepancies and risks

### QDPlay hard-coding versus official negotiation

The official app consumes HU-provided `VIDEO_ARGS` and phone/car dimension fields. QDPlay learns some HU sizes but does not fully propagate them to its external producer. A GE13 probe must prefer negotiated values and reject invalid sizes instead of copying QDPlay's fixed examples.

### Key-frame behavior

QDPlay relays the HU request to its producer. The official app visibly resends SPS/PPS but does not visibly request an immediate encoder IDR. The clean-room client should do both; the target trace will determine whether the HU's label means “repeat decoder configuration,” “send IDR,” or both.

### Wi-Fi topology

QDPlay is AOA-only. Official QDLink contains a complete alternative Wi-Fi Direct transport and initializes discovery support on ordinary launch. No evidence proves a mandatory USB-to-Wi-Fi transition on this GE13. Simulator/probe architecture should keep a transport interface but implement USB first.

### Multitouch

The official V2 parser represents multiple pointers but frequently forwards only pointer ID 0. QDPlay similarly collapses practical output. The wire format should be preserved losslessly even though the initial product can deliberately support one pointer.

### Accessibility metadata

The official service calls `dispatchGesture()` but its decoded configuration lacks the normal gesture capability declaration. The clean-room app must declare and test this correctly rather than reproducing the discrepancy.

### Native SSP library

Official QDLink uses a proprietary native typed-value serializer for nested V1 services. QDPlay implements compatible framing in GPL C. Neither should be copied into a differently licensed app without permission/license alignment. A clean-room Kotlin implementation based on observed wire formats and independently written tests remains the recommended Phase-1 path.

## Decision

**Phase-0 USB gate: `CONDITIONAL GO`.** The official APK removes the principal architectural doubt that QDLink requires privileged Android gadget control: it does not. It uses the same public framework AOA stream available to a normal APK. The remaining condition is the exact `HU716P.00-BEH` attachment and handshake.

**Phase 0.5: proceed only with the modifications listed in `15_OFFICIAL_APK_ANALYSIS.md`.** Do not build a fixed-resolution, V1-only, USB-identity-emulating, or MediaProjection-dependent probe.

