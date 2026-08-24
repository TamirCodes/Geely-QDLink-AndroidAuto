# Phase 0.5 validation results

Date: 2026-08-24  
Workspace: `Geely-QDLink-AndroidAuto`

## Result summary

| Area | Result | Evidence label |
|---|---|---|
| Clean-room Kotlin core compiles | PASS | `OBSERVED_IN_LAB` |
| V1 `!BIN` framing/state path | PASS in JVM and Android end-to-end tests | `OBSERVED_IN_LAB` |
| V2 `5A5A` framing/state path | PASS in JVM and Android end-to-end tests | `OBSERVED_IN_LAB` |
| Nested `A6A6` + CRC16/XMODEM | PASS golden/negative tests | `OBSERVED_IN_LAB` |
| Fragmentation/coalescing | PASS at every V2 split point and with 137-byte simulator writes | `OBSERVED_IN_LAB` |
| Malformed/property cases | PASS, 2,000 deterministic mutations | `OBSERVED_IN_LAB` |
| Desktop GE13 simulator | PASS for V1 and V2 scripted sessions | `OBSERVED_IN_LAB` |
| Android debug APK build/install | PASS on stock Android 16 / API 36 | `OBSERVED_IN_LAB` |
| Android V1 simulator session | PASS: handshake, encoder, video, touch, IDR, heartbeat, watchdog | `OBSERVED_IN_LAB` |
| Android V2 simulator session | PASS: handshake, VIDEO_ARGS, encoder, video, touch, SPS/PPS resend, IDR, heartbeat, clean disconnect | `OBSERVED_IN_LAB` |
| Android simulator reconnect | PASS: two consecutive V2 connect/stream/disconnect cycles in one app process | `OBSERVED_IN_LAB` |
| Hardware AVC encoder | PASS: `c2.mtk.avc.encoder`, 1280x720 | `OBSERVED_IN_LAB` |
| App-owned rendering without MediaProjection | PASS using private VirtualDisplay + `Presentation` | `OBSERVED_IN_LAB` |
| Own Activity placement on VirtualDisplay | DENIED by tested OEM policy on public and private displays | `OBSERVED_IN_LAB` |
| Waze placement on app-owned public display | DENIED before launch; no navigation started | `OBSERVED_IN_LAB` |
| Encoding while display 0 sleeps briefly | PASS in the tested two-second sleep/wake run | `OBSERVED_IN_LAB` |
| Display-targeted shell touch delivery | Not received by the `Presentation` on the tested phone | `OBSERVED_IN_LAB` |
| Exact GE13 AOA/PFD/protocol test | Not run; vehicle unavailable | `UNKNOWN` |

## Build and unit-test validation

Command:

```text
gradlew :qdlink-core:test :tools:ge13-simulator:installDist :lab-app:assembleDebug
```

Result:

```text
BUILD SUCCESSFUL
16 tests completed, 16 passed
```

Coverage includes:

- exact V1 APP_STATUS header bytes;
- exact A6A6 `phoneinitok` vector and CRC;
- V2 JSON declared versus padded lengths;
- V2 multi-pointer IDs/actions/big-endian floats;
- V1/V2 Annex-B video payload placement;
- AVCC-to-Annex-B conversion and malformed lengths;
- A6A6 truncation, CRC, hex, and length failures;
- V1 declared-length failures;
- V2 padding, touch-count, and non-finite-coordinate failures;
- all fragmentation points and coalesced frames;
- V1 and V2 handshake-to-streaming state transitions;
- 2,000 deterministic malformed-frame mutations without uncaught index errors.

The installed desktop simulator launchers were executed independently. A TCP client received a complete V1 script beginning with `!BIN` (4,608 bytes) and a complete V2 script beginning with `5A5A` (4,096 bytes). Simulator writes were deliberately fragmented into 137-byte chunks.

## Android device validation

Test environment:

```text
Android 16 / API 36
stock, non-root
android.hardware.usb.accessory = present
android.software.activities_on_secondary_displays = present
official QDLink installed
Waze installed
```

The device identifier is intentionally omitted. After the user enabled the normal phone setting permitting USB installation, the debug APK installed without bypassing Android security.

### Custom renderer and real H.264 output

The lab selected the hardware encoder `c2.mtk.avc.encoder` at 1280x720. The tested phone rejected Activity placement on both public and private app-created VirtualDisplays, including the lab's own Activity. The app therefore used an app-owned `Presentation` on a private `OWN_CONTENT_ONLY | PRESENTATION` VirtualDisplay.

That path produced real AVC output without requesting MediaProjection:

```text
5-second run: 89 frames, 140,043 bytes
codec configuration: 30 bytes
diagnostic capture: 166,030 bytes
NAL type 7 (SPS): 1
NAL type 8 (PPS): 1
NAL type 5 (IDR): 6
NAL type 1 (non-IDR): 106
```

The diagnostic capture is Annex-B and is stored only as ignored lab output under `captures/`; it is not a product asset.

After the final rebuild and reinstall, the same five-second path was repeated successfully: 88 frames / 139,677 bytes, with the same hardware encoder, denied Activity placement, and working `Presentation` fallback.

During a separate run, display 0 was slept for about two seconds and woken normally. Encoding continued and produced 86 frames / 138,001 bytes. This is useful evidence for short screen-off continuity, not proof of behavior while securely locked for long periods.

### Third-party Activity result

`ActivityManager.isActivityStartAllowedOnDisplay()` returned `false` for Waze on the app-owned public VirtualDisplay. The lab honored that result: Waze was not launched, no destination was supplied, and no navigation occurred.

Therefore:

- custom app-owned UI without MediaProjection: **feasible through `Presentation`/direct rendering**;
- portable secondary-display Activity hosting: **not established and denied on this phone**;
- Waze/third-party pixels without MediaProjection: **not feasible through this tested VirtualDisplay path**.

### V2 simulator end-to-end

The Android app connected to the forwarded desktop simulator and:

1. accumulated fragmented V2 transfers;
2. selected V2 and accepted 1280x720 dimensions;
3. sent phone/update/video-support responses and parsed 1280x720, 24 fps, 2,764,800 bit/s, 4-second `VIDEO_ARGS`;
4. configured hardware AVC from those negotiated parameters and started the `Presentation` renderer;
5. received DOWN/MOVE/UP touch frames with pointer ID 2 and normalized coordinates;
6. handled a key-frame request by resending 30-byte SPS/PPS and requesting an actual encoder IDR;
7. exchanged heartbeat traffic;
8. sent codec configuration and multiple 512-byte-padded video access units;
9. answered `DISCONNECT_REQ`, stopped video, released the display, and closed cleanly.

One final simulator status counted 43 video packets, two SPS NALs, two PPS NALs, and two IDRs. The duplicate SPS/PPS pair is the expected explicit resend after `KEY_FRAME_REQ`.

A separate `--cycles=2` run completed two consecutive sessions in the same Android app process. The app received a new intent, reopened a fresh simulator transport, selected V2 twice, streamed 41 and 44 video packets respectively, returned `DISCONNECT_RSP` twice, and released both sessions without `SIMULATOR_ERROR`.

### V1 simulator end-to-end

The Android app separately:

1. selected V1 and accepted 1280x720 dimensions;
2. sent version/upgrade/application and mirror-support replies;
3. started hardware AVC and video packetization;
4. received integer DOWN/MOVE/UP touch frames;
5. handled IDR requests;
6. sent codec configuration and multiple padded V1 video frames.

The final V1 status counted 121 video packets, two SPS NALs, two PPS NALs, two IDRs, and two phone heartbeats before watchdog/EOF teardown.

These tests validate our implementation and simulator against one another plus fixed golden vectors. They do not independently prove that every reconstructed field matches `HU716P.00-BEH`.

### Input limitation

ADB shell input targeted at the logical display did not reach the `Presentation` touch handler on this phone. This does not test GE13 input: simulated GE13 touch travels inside QDLink and was decoded correctly by both V1 and V2 state machines. Future Android gesture injection still requires the user-enabled AccessibilityService and a real multi-display gesture test.

## Gate status

The formal Phase 0 USB result remains:

**`CONDITIONAL GO` — project conclusion `LIKELY — missing target validation`.**

Phase 0.5 removed the lab software blockers for framing, strict parsing, fragmentation, session state, hardware encoding, app-owned rendering, simulator-driven touch, heartbeat, IDR, and watchdog behavior. It also disproved a portable assumption: ordinary Activity placement on an app-created VirtualDisplay cannot be relied upon, even for our own package. `Presentation`/direct app rendering is the verified custom-UI route.

The result cannot become `GO` until the exact target demonstrates all of the following through public stock-Android APIs:

1. `HU716P.00-BEH` causes Android to expose a `UsbAccessory`;
2. the APK can open its `ParcelFileDescriptor`;
3. a valid QDLink frame is received and answered through that descriptor;
4. no app-controlled VID/PID, configfs, FunctionFS, kernel function, or private API is required.

No current evidence requires `NO-GO`.

## Phase boundary

The next authorized activity is the non-invasive in-car vehicle probe in `docs/14_TARGET_PROBE_INSTRUCTIONS.md`, governed by the complete matrix in `docs/08_TEST_PLAN_GE13.md`. No polished launcher, third-party mirroring feature, or other Phase-1 product implementation has been started.

**Status: `READY_FOR_VEHICLE_PROBE`.**
