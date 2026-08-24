# Official QDLink and Geely behavior analysis

## Sources and scope

- [QDLink Google Play listing](https://play.google.com/store/apps/details?id=com.neusoft.qdrivelink&hl=en-US), package `com.neusoft.qdrivelink`, Neusoft.
- [Official Geely Israel GE13 multimedia connection manual](https://geely.co.il/wp-content/uploads/2025/01/Multimedia-GE13_final_2-connections.pdf), visually inspected PDF pages 33-36 (printed pages 30-33).
- Official Android USB, MediaProjection, Accessibility, foreground-service, and Bluetooth documentation.

These are behavior and platform sources. They do not reveal the private QDLink wire protocol.

## Officially documented user flow

The Geely manual describes this Android flow (`VERIFIED_FROM_OFFICIAL_DOC`):

```text
Install QDLink and grant relevant app permissions
    ↓
Connect phone to driver-side USB port
    ↓
HU recognizes Android phone; progress bar begins
    ↓
Android accessory dialog appears
    ↓
User checks "Use by default for this USB accessory" and presses OK
    ↓
Android screen-capture dialog appears
    ↓
User checks the offered option and presses START NOW
    ↓
Optional HU Bluetooth auto-connect after successful phone identification
    ↓
Mirroring/reverse control session
```

The manual explicitly distinguishes ports: the driver-side port supports QDLink, while the front passenger-side port does not. It recommends the phone's proper data cable and reconnecting it if the session fails.

The manual belongs to the GE13 family and predates the exact 2024 target software. It is therefore not `OBSERVED_ON_TARGET` for `HU716P.00-BEH`.

## Play listing behavior

The current listing (`VERIFIED_FROM_OFFICIAL_DOC`) says:

- The phone connects to and mirrors onto the car display.
- The user can operate through the phone or car display.
- The application uses Android `AccessibilityService` for reverse operation.
- It exposes multiple phone applications through the mirrored experience.

The listing refers specifically to AccessibilityService use “when using WiFi screen projection”; it does not establish whether the USB transport changes to Wi-Fi after initial negotiation, whether every vehicle uses that topology, or whether GE13 does. QDPlay itself uses H.264 on the USB AOA stream. The conflict is recorded as `UNKNOWN`, not reconciled by assumption.

## Android platform meaning of the accessory dialog

In Android Open Accessory mode, the external accessory is USB host and sends up to six strings: manufacturer, model, description, version, URI, and serial. Android creates a `UsbAccessory` from these values. An application declares an attachment filter with manufacturer/model/version; omitting version is preferable where possible because the accessory may not provide it.

When a matching accessory attaches:

- Android can launch a matching activity through `USB_ACCESSORY_ATTACHED`.
- If the user allows that application to handle the attachment, it receives accessory permission.
- `requestPermission()` alone grants temporary access until disconnect.
- `hasPermission()` can also be true because the user selected the application as the default for that accessory.
- AOSP persists accessory defaults keyed by an accessory filter.

The exact OEM UI differs, but the manual's screenshot shows the target-family version of “Use by default for this USB accessory.” This is strong evidence that repeat attachment can avoid the USB permission dialog after the first selection.

### Coexistence with official QDLink

`INFERRED` from AOSP behavior:

- If official QDLink and our APK both match the same accessory, the first attachment may produce a chooser.
- Selecting our app with the persistent/default option should route later attachments directly to it and grant permission.
- A normal application cannot programmatically make itself the USB default; the user/system chooser owns that decision.
- The official application should not normally need removal once our app is default.

`UNKNOWN`: a preinstalled/privileged official app, vendor-modified resolver, overly broad filter, or an already persisted default could change this. The exact phone model and Android build also matter. This must be tested without uninstalling anything first.

## MediaProjection and plug-and-play

The manual's `START NOW` dialog is a screen-capture consent dialog. On current Android, MediaProjection requires explicit user consent before every new projection session. For apps targeting Android 14+, a consent intent/token cannot be reused to create multiple sessions, and one `MediaProjection` instance may create the virtual display only once.

Consequences:

- First-time configuration cannot permanently authorize unrestricted future screen capture.
- Cable reconnect, app process death, phone reboot, or starting a genuinely new capture session may require another prompt.
- A new zero-tap whole-phone mirror session cannot be guaranteed by a normal stock APK.
- Rotation within an existing capture should resize the existing `VirtualDisplay` and replace its Surface rather than create a new session, but the system can still stop projection.

### Custom UI versus third-party apps

A custom driving UI owned by our APK can render into a Surface supplied to a hardware H.264 encoder. This uses MediaCodec, not MediaProjection, and therefore avoids recurring capture consent.

That path only contains pixels our application renders. Starting Waze via an intent and injecting gestures into Waze does **not** give our app Waze's framebuffer. Displaying Waze itself would require a separate legitimate integration:

- MediaProjection, with its recurring consent and protected-content limitations.
- Supported deep links/intents while the phone remains the primary display, with only our own navigation summary rendered.
- An SDK/API contract that legally supplies map/navigation content to our UI.
- A future architecture outside this Phase-0 MVP.

The plug-and-play product should therefore make the native/custom driving UI the default and make third-party full-screen mirroring an explicitly consent-gated optional mode.

## Reverse control through AccessibilityService

Android's `dispatchGesture()` is available from API 24 and can synthesize taps, paths/swipes, long presses, continued strokes, and multiple simultaneous strokes. The service must declare `canPerformGestures=true`, and the user must enable it in Android Accessibility settings.

Limitations:

- Enabling the service is an unavoidable first-time user action; a normal app cannot silently enable it.
- Dispatching a gesture cancels any gesture already in progress from the user or another service.
- Gesture duration and path must be reconstructed from QDLink DOWN/MOVE/UP; naive one-event-per-call dispatch will not preserve drags.
- Coordinate transforms must account for video viewport, letterboxing/cropping, orientation, insets, and the actual Android target display.
- Multitouch is possible in the Android API and in QDPlay V2's wire parser, but target GE13 generation and practical synchronization remain unknown.
- Accessibility should be limited to user-enabled reverse control and never used to bypass security dialogs.

## Automatic orientation and launch

The attachment activity can request landscape and move immediately into the projection session. A custom UI can start rendering automatically without a Connect button once the session state reaches video-ready. Android may still show the launched activity, lock-screen, USB resolver, or foreground-service notification according to platform policy.

Launching a third-party activity such as Waze may be done through supported package intents/deep links, subject to package visibility and foreground/background activity rules. Launch success does not solve video capture.

## Bluetooth/audio behavior

The GE13-family manual says an HU setting can automatically connect Bluetooth after successful USB identification. If another phone is connected, the HU asks which phone should be used. QDPlay contains a V1 `BT_AUTO_CONNECTED` application exchange but sends no audio through QDLink.

Product assumptions for early milestones:

- Media, navigation prompts, and calls use the existing phone-HU Bluetooth profiles.
- Initial pairing and Android 12+ `BLUETOOTH_CONNECT`/Nearby Devices approval can require user action.
- Later profile reconnection may be automatic if already paired and the HU setting is enabled.
- The APK should not attempt private Bluetooth profile control unless public APIs are sufficient.

Exact responsibility for initiating the link and the effect of the QDLink acknowledgment are target-test questions.

## Official behavior versus QDPlay

| Concept | Official source | QDPlay source | Status |
|---|---|---|---|
| USB host/accessory mode | APK uses public `UsbAccessory`/PFD streams; manual shows Android prompt | HU is host; QDPlay is device/accessory-mode endpoint | Independently consistent |
| Default handler | Manual screenshot exposes “Use by default” | Not applicable on Linux | Android architecture supported |
| Progress bar | Starts after Android phone detection | README says it appears after video producer connects and USB session begins | Exact trigger unknown |
| Dimensions | APK consumes V2 `CAR_INFO`/`VIDEO_ARGS` and reports phone/mirror sizes | V1 `VERSION`; V2 `CAR_INFO` | Dynamic negotiation independently confirmed |
| Video | APK sends MediaCodec AVC over AOA in USB mode; Wi-Fi alternative exists | Annex-B H.264 over USB | Direct USB video independently confirmed |
| Reverse control | APK decodes touch and calls `dispatchGesture()` | V1/V2 touch returned to source | Wire and Android ends independently confirmed |
| Orientation | Landscape activities, land-mode messages, sensor/angle video fields | Fixed fields/land-mode handling | Official behavior is more dynamic than QDPlay |
| Audio | APK handles HU BT address/auto-connect and A2DP; no media payload path found | No QDLink audio, Bluetooth instructions | Bluetooth-first assumption strengthened |
| Reconnect | APK closes PFD/streams and uses 3 s heartbeat/5 s watchdog | Watchdog resets full session | Timings independently confirmed |
| Official app required | Manual says install it | QDPlay replaces it | Replacement proven only on QDPlay-supported receivers |

## Phase-0.25 official APK evidence

The static-analysis gap was closed for official Google Play version 1.9.7 (107). `VERIFIED_FROM_OFFICIAL_APK_STATIC` findings include:

- Active accessory filter `Neusoft / QDriveLink / 1.0` and attachment-driven `ConnectActivity`.
- Public `UsbAccessory` transport with both V1 and V2 protocol detection.
- AVC Baseline/level-3.1 Surface encoding with HU-supplied V2 width, height, fps, bitrate, and frame interval.
- Separate SPS/PPS configuration packets and raw encoder access-unit forwarding.
- Binary V2 multi-pointer records using big-endian floats; Accessibility gesture synthesis for external apps.
- Alternative Wi-Fi Direct transport and active Bluetooth/A2DP management.
- `GE13-J2` model routing, but no literal FES5PL/HU716P/Geometry C match.

The full artifact provenance, limitations, and dynamic no-car analysis are in `15_OFFICIAL_APK_ANALYSIS.md`; the implementation comparison is in `16_OFFICIAL_VS_QDPLAY.md`. Exact target mode selection and on-wire values remain `UNKNOWN`.
