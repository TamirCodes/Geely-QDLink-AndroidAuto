# Safe GE13 in-car test plan

All tests must be performed while parked. They are limited to the infotainment USB/QDLink interface. Do not enable engineering menus, ADB on the HU, root, CAN access, firmware update, or unknown binaries.

## Required test equipment

- Exact target vehicle/HU identified in `05_GE13_TARGET_PROFILE.md`.
- Stock, non-root Android test phone with screen lock known to the tester.
- High-quality USB data cable.
- Official QDLink installed for baseline/conflict tests.
- Later Phase-1 diagnostic APK signed consistently across runs.
- Stopwatch or synchronized monotonic timestamps in application logs.
- Optional second Android phone to distinguish phone-specific behavior.

No test in this plan requires implementation during Phase 0. The procedures define the evidence Phase 1 must collect.

## Logging baseline

Every run records:

```text
test ID and run number
vehicle parked state
HU identifiers and boot state
phone make/model, Android build, target SDK
official QDLink installed/enabled/version
cable/USB port
wall clock and monotonic timestamps
UsbAccessory fields (when exposed)
permission/default state
protocol RX/TX message type and size
encoder configuration and frame counters
disconnect/reconnect cause
```

Do not log contacts, messages, destinations, navigation history, screen images, or complete unknown payloads that might contain personal data. Unknown packets should be represented by marker, length, direction, and a short capped hexadecimal prefix only when necessary.

## Test 1 — Official baseline and port confirmation

- **Connect:** Phone with official QDLink to the driver-side USB port; repeat with passenger-side port.
- **Observe:** HU progress bar, Android accessory/default dialog, MediaProjection dialog, successful official projection, Bluetooth behavior.
- **Logs:** Manual timestamps and phone USB/system logs available without root; record every prompt verbatim.
- **Pass:** Driver port completes official QDLink; passenger port does not, matching the manual.
- **Fail:** Official QDLink fails on the driver port.
- **Next diagnostic:** Replace cable, reboot phone/HU normally, verify official app version/permissions, retry. Do not continue protocol testing until the official baseline works.

## Test 2 — Mandatory stock-Android AOA gate

- **Connect:** Stock non-root phone with the diagnostic APK registered for accessory attachment; driver-side port.
- **Observe:** Whether Android creates a `UsbAccessory`, routes an attach intent, and reports `FEATURE_USB_ACCESSORY`.
- **Logs:** All six accessory fields; presence of attach intent; `accessoryList`; `hasPermission`; time from physical attach.
- **Pass / `CONDITIONAL GO` → gate step passed:** A non-null accessory appears through public APIs.
- **Fail / potential `NO-GO`:** No accessory appears while official QDLink succeeds on the same phone/cable, across three clean retries.
- **Next diagnostic:** Compare with a second stock phone; inspect official-app launch behavior; confirm filter does not suppress enumeration; record ordinary USB mode without attempting raw gadget changes. If GE13 requires a specific unavailable pre-AOA identity, declare Phase-1 blocked.

## Test 3 — AOA identity stability

- **Connect:** Disconnect/reconnect three times, then repeat after HU reboot and phone reboot.
- **Observe:** Manufacturer, model, version, description, URI, and serial.
- **Logs:** Exact strings with null/empty distinctions and run conditions.
- **Pass:** Manufacturer/model are stable enough for an exact filter; optional fields' variability is understood.
- **Fail:** Identity changes unpredictably or is absent in a way that prevents reliable matching.
- **Next diagnostic:** Match only stable manufacturer/model, omitting version; test collision with other accessories. If no precise safe filter is possible, keep manual enumeration fallback and mark zero-tap routing at risk.

## Test 4 — Permission and descriptor gate

- **Connect:** Attach with no persisted default and request handling through the system UI.
- **Observe:** Confirmation/chooser, permission result, `hasPermission`, and `openAccessory()`.
- **Logs:** Prompt type, selected options, PFD open result, read/write exceptions.
- **Pass:** User approval yields a usable PFD through public APIs.
- **Fail:** Permission is granted but PFD remains null/unusable on repeated runs.
- **Next diagnostic:** Close other candidate apps, ensure only one owner, retry after detach; compare official app. If a private API is required, mark NO-GO.

## Test 5 — First QDLink byte exchange

- **Connect:** Open the publicly supplied PFD and send exactly the QDPlay-compatible 512-byte `APP_STATUS` probe.
- **Observe:** First inbound response and HU progress-bar change.
- **Logs:** Exact byte count, marker (`!BIN`, `5A5A`, or other), validated declared length, latency, timeouts. Retain a privacy-reviewed binary capture for engineering only.
- **Pass / formal USB `GO`:** A valid QDLink response arrives through `UsbAccessory` with no gadget manipulation.
- **Fail / `NO-GO` candidate:** The official app works but the PFD cannot produce a response despite verified bytes and three sessions.
- **Next diagnostic:** Compare first probe timing/content, partial writes, 512-byte padding, and whether official app must be absent. If evidence shows required pre-AOA VID/PID/gadget control, stop Phase 1.

## Test 6 — Protocol generation and handshake transcript

- **Connect:** Continue from a passing Test 5 with protocol diagnostics enabled and video disabled.
- **Observe:** V1 `VERSION` or V2 `CAR_INFO`, all subsequent commands, progress states, and disconnect deadline.
- **Logs:** Sanitized ordered transcript with message type, command/logic ID, declared size, response, and timestamp.
- **Pass:** One known processor can complete the non-video handshake without protocol rejection for at least 30 seconds.
- **Fail:** Unknown marker, missing mandatory message, repeated reconnect, or HU progress stalls.
- **Next diagnostic:** Compare against both QDPlay generations; record unknown fields without guessing; update the protocol map before adding video.

## Test 7 — Display and coordinate-space discovery

- **Connect:** Complete the handshake and read all HU dimension messages; do not yet stream arbitrary resolution.
- **Observe:** Reported car width/height, any screen/capture/viewport values, and HU chrome around the projection area.
- **Logs:** Physical panel information available in HU UI only if documented; protocol dimensions; configured encoder dimensions; touch raw bounds from edge taps later.
- **Pass:** A candidate projection viewport and encoder size are identified without relying on QDPlay image presets.
- **Fail:** No dimensions are communicated or values are inconsistent.
- **Next diagnostic:** Test a controlled configuration list starting with the reported dimensions. Keep physical panel, viewport, encoder, and touch spaces separate.

## Test 8 — Generated H.264 first frame

- **Connect:** Start a hardware AVC encoder before or during handshake; send a generated test scene only after the HU play request.
- **Observe:** HU enters projection, first image appears, aspect ratio, color, corruption, and progress-bar completion.
- **Logs:** codec name/profile/level, width/height/fps/bitrate, SPS/PPS bytes and placement, first IDR time, packet sizes, HU requests.
- **Pass:** Stable recognizable test image appears for 60 seconds.
- **Fail:** Black screen, decode error, stretched image, or session disconnect.
- **Next diagnostic:** Request/send a fresh IDR; resend SPS/PPS; test Annex-B conversion; reduce bitrate/frame rate; validate frame and 512-byte lengths. Change one variable per run.

## Test 9 — H.264 compatibility matrix

- **Connect:** Repeat the generated stream using a bounded matrix of supported phone encoder settings.
- **Observe:** Startup success, artifacts, drops, latency, HU key-frame requests.
- **Logs:** profile, level, resolution, fps, bitrate mode/value, GOP, SPS/PPS cadence, access-unit maximum, USB throughput.
- **Pass:** Select the lowest-complexity stable configuration meeting visual and latency needs.
- **Fail:** No hardware configuration is accepted.
- **Next diagnostic:** Compare software-encoded known-good Annex-B samples for diagnosis only; determine whether the fault is encoder output or packetization before considering architecture changes.

## Test 10 — Touch wire behavior and calibration

- **Connect:** Stream a grid/test pattern with numbered calibration targets.
- **Observe:** Tap, hold, horizontal/vertical/diagonal swipe, slow drag, edge/corner taps, and two-finger contact if safe.
- **Logs:** timestamp, protocol, action ID, DOWN/MOVE/UP, every pointer ID, raw x/y, optional pressure, mapped x/y.
- **Pass:** DOWN/MOVE/UP are reliable and the marker follows within the correct target cell across the viewport.
- **Fail:** Missing release/move, mirrored axes, offset, nonlinear space, or repeated invalid values.
- **Next diagnostic:** Determine origin, rotation, crop/insets, integer vs normalized coordinates, and event ordering. Do not enable Accessibility injection until internal mapping passes.

## Test 11 — USB default handler and official-app conflict

- **Connect:** Keep official QDLink installed. Clear only our app's prior USB selection through normal settings/system UI, then attach.
- **Observe:** Resolver/chooser entries, “always/default” option, which app launches, and whether the choice persists.
- **Logs:** installed package versions, prompt screen, selected option, launch package, permission state.
- **Pass:** Our app can be selected persistently; official QDLink need not be removed.
- **Fail:** Official app always wins, chooser recurs, or persisted default cannot be changed.
- **Next diagnostic:** Inspect phone OEM default-app/USB settings, official app privilege, and exact filters where legally available. Test disabling—not uninstalling—the official app only as a controlled diagnostic. Record any required removal as a product UX defect.

## Test 12 — Zero-tap subsequent connection and startup time

- **Connect:** With first-time setup complete and phone in a normal unlocked idle state, attach USB ten times.
- **Observe:** No phone taps, automatic launch, handshake, and visible custom test UI.
- **Logs:** timestamps for cable detection, accessory attach, PFD open, probe, protocol-ready, first IDR, first-visible confirmation.
- **Pass:** At least 9/10 successful zero-tap sessions; median visible projection under five seconds and no unexplained prompt.
- **Fail:** Recurring chooser/permission, manual Connect action, frequent timeout, or median over target.
- **Next diagnostic:** Attribute time to AOA, resolver, handshake, encoder, or HU decode; optimize only the measured stage.

## Test 13 — Cable interruption and temporary USB loss

- **Connect:** During stable test video, unplug for 1, 3, and 10 seconds, then reconnect.
- **Observe:** Teardown, stale-frame behavior, new attachment, fresh IDR, restored touch.
- **Logs:** EOF/detach reason, session generation, retry count, reopened descriptor, time to recovery.
- **Pass:** No crash or stale descriptor; projection resumes automatically after each physical reattach.
- **Fail:** Hung service, interleaved old/new writes, black image without IDR, or manual app action.
- **Next diagnostic:** Verify generation cancellation, queue flush, descriptor close, and encoder reset.

## Test 14 — HU reboot/restart

- **Connect:** Stable session; restart the HU using only normal vehicle controls/power cycle while parked.
- **Observe:** USB disappearance/re-enumeration and app recovery.
- **Logs:** detach/timeout, new identity, attach intent, handshake, time to first frame.
- **Pass:** Automatic recovery without phone tap after the HU returns.
- **Fail:** Session remains dead or requires app force-stop/cable cycle.
- **Next diagnostic:** Determine whether Android receives a detach/attach; add bounded enumeration retry only if platform evidence supports it.

## Test 15 — Phone lock/unlock

- **Connect:** Test three cases: attach while unlocked, lock during streaming, attach while locked.
- **Observe:** Activity launch visibility, service continuity, prompts deferred to unlock, video and touch behavior.
- **Logs:** keyguard state category only, lifecycle callbacks, permission/PFD/session state.
- **Pass:** Existing sessions survive lock where OS permits; subsequent locked attach either starts zero-tap or resumes immediately after legitimate unlock.
- **Fail:** Unrecoverable session or repeated prompts after unlock.
- **Next diagnostic:** Separate keyguard/background-start policy from QDLink failure. Do not bypass the lock screen.

## Test 16 — App process death

- **Connect:** Stable session, then use normal developer test tooling on the phone to terminate only our process; do not use/enable anything on the HU.
- **Observe:** FGS/process restart, `accessoryList` enumeration, retained permission, descriptor reopen, or need for physical reattach.
- **Logs:** restart cause, accessory availability, `hasPermission`, session recovery time.
- **Pass:** Best case: service recovers automatically; acceptable early result: explicit documented need to replug while preserving data and avoiding a crash loop.
- **Fail:** Infinite restart loop, leaked exclusive access, or corrupted HU state.
- **Next diagnostic:** Test `START_STICKY`/enumeration strategy in Phase 1 and gate retries by current accessory presence.

## Test 17 — Phone reboot and app update

- **Connect:** With defaults configured, reboot the phone and reconnect; later install a same-signature update and repeat.
- **Observe:** Default routing, USB permission, Accessibility enablement, Bluetooth permission/pairing, first launch.
- **Logs:** state before/after and all prompts.
- **Pass:** Default/setup persists or the product clearly identifies the minimal one-time repair.
- **Fail:** Every reboot/update recreates the full setup flow.
- **Next diagnostic:** Determine whether failure is OEM USB-default storage, signature/package change, or permission revocation.

## Test 18 — Bluetooth automatic audio connection

- **Connect:** Pair once, grant Nearby Devices, enable the HU manual's Bluetooth auto-connect setting, then attach USB in repeated sessions.
- **Observe:** profile connection, HU conflict prompt if another phone is active, media/navigation audio route.
- **Logs:** sanitized Bluetooth state/profile transitions and QDLink `BT_AUTO_CONNECTED` messages; no device address in exported logs.
- **Pass:** After first setup, the intended phone reconnects automatically when no competing phone is active; video does not wait on audio.
- **Fail:** Pairing/prompt every session, wrong phone selected, or video handshake blocked.
- **Next diagnostic:** Compare behavior with/without the QDLink acknowledgment and with official QDLink. Keep Bluetooth failure non-blocking for M1.

## Test 19 — Custom UI without MediaProjection

- **Connect:** Clear any active MediaProjection token/session, then attach and stream the custom test renderer.
- **Observe:** Whether any screen-capture dialog appears.
- **Logs:** encoder surface creation and explicit assertion that MediaProjection API was not invoked.
- **Pass:** Custom UI projects with zero MediaProjection prompts across five sessions.
- **Fail:** Product path accidentally depends on screen capture.
- **Next diagnostic:** Remove capture dependencies; ensure rendering targets MediaCodec's input Surface directly.

## Test 20 — Optional third-party mirroring consent

- **Connect:** From a user-selected optional mirror mode, request MediaProjection and choose Waze or full display where Android permits.
- **Observe:** first consent, cable reconnect/new capture, process death, protected-content behavior, orientation.
- **Logs:** consent requested/granted/stopped and capture surface lifecycle; never record screen pixels in debug logs.
- **Pass:** Mirroring works after explicit consent and the UI clearly communicates that consent recurs.
- **Fail:** Consent cannot be obtained, app capture is blank/protected, or UX claims zero taps.
- **Next diagnostic:** Retain custom UI as primary; treat third-party mirroring as unsupported on affected versions/apps rather than bypassing platform security.

## Exit criteria for beginning broader Phase 1

- Tests 1-6 establish formal USB GO and a known handshake.
- Test 8 shows one generated frame.
- Test 10 proves touch mapping without injection.
- Tests 11-12 show a credible default-handler/zero-tap flow.
- Test 19 confirms the primary renderer has no MediaProjection dependency.

Failure of the formal USB gate ends the normal-APK architecture. Video, touch, launcher, and Waze work must not proceed around an unresolved gate.
