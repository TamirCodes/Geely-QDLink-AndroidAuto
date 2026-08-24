# Android port feasibility and USB GO/NO-GO gate

## Decision

**Gate result: `CONDITIONAL GO`.**  
**Project conclusion: `LIKELY — missing target validation`.**

A normal, stock, non-root Android APK can implement the **post-AOA QDLink application protocol** using public Android APIs. It cannot create QDPlay's Linux USB gadget, select VID/PID, configure configfs, or control kernel re-enumeration. Official QDLink 1.9.7 now independently proves that its production Android client uses `UsbManager.getAccessoryList()`, `hasPermission()`, `openAccessory()`, and PFD file streams, with no application gadget/configfs layer.

The result is conditional rather than final because the exact `HU716P.00-BEH` has not been captured exposing a `UsbAccessory` and accepting clean-room QDLink bytes. The installed official app's architecture makes a mandatory application-controlled Samsung identity very unlikely, but only the target attach test closes that condition. JNI does not change the USB security boundary.

## The decisive distinction

### QDPlay layer A: Linux impersonation of an Android phone

QDPlay must provide everything a real Android system normally provides:

- USB device controller/gadget configuration.
- Initial phone-like VID/PID and descriptors.
- Android accessory kernel function.
- Receipt of AOA control requests from the HU.
- Re-enumeration as `18d1:2d00`.
- `/dev/usb_accessory` endpoint.

These operations require Linux kernel/configfs access and are outside the Android application sandbox. They are **not portable APK features**.

### QDPlay layer B: application protocol over the active AOA stream

Once `/dev/usb_accessory` is open, QDPlay only reads and writes bytes:

- 512-byte protocol probe.
- V1/V2 framing and state machine.
- H.264 packetization.
- touch decode.
- heartbeat/watchdog/reconnect.

On Android, the public replacement is:

```text
UsbManager
  → UsbAccessory
  → permission/default selection
  → openAccessory(accessory)
  → ParcelFileDescriptor
  → FileInputStream + FileOutputStream
  → QDLink protocol bytes
```

No public Android API lets the APK select the phone's initial USB VID/PID. Therefore the product is feasible exactly when GE13 treats a normal phone as a standard AOA target and starts QDLink after Android exposes the accessory.

## Standard Android AOA flow

`VERIFIED_FROM_OFFICIAL_DOC` for Android, and `INFERRED` for the exact target:

```text
GE13 (USB host)                              Stock Android phone

Enumerate ordinary phone USB identity   ---->
AOA GET_PROTOCOL                        ---->
AOA SEND_STRING manufacturer/model/... ---->
AOA START                               ---->
                                         Android switches its managed USB
                                         gadget to accessory mode
Re-enumerate / establish AOA bulk pipe  <----
                                         framework constructs UsbAccessory
                                         resolver matches app filters
                                         system launches selected Activity
                                         user/default grants package access
                                         APK calls openAccessory()
QDLink APP_STATUS/probe                  <----
QDLink VERSION or CAR_INFO               ---->
```

The external host sends the strings. They identify the **accessory/HU application**, not the phone's post-AOA USB manufacturer descriptor. Android matches application filters on `manufacturer`, `model`, and optionally `version`. Description, URI, and serial are exposed for diagnostics but are not filter attributes.

The official APK's active filter is exactly `Neusoft / QDriveLink / 1.0` (`VERIFIED_FROM_OFFICIAL_APK_STATIC`). Those are the expected matching values, not yet an `OBSERVED_ON_TARGET` record. The gate test must still log `UsbAccessory.getManufacturer()`, `getModel()`, `getVersion()`, `getDescription()`, `getUri()`, and `getSerial()` to capture all six actual target values and null/empty behavior.

## Capability comparison

| QDPlay operation | Stock APK capability | Required in APK? | Result |
|---|---|---|---|
| Create configfs gadget | None | No, if standard AOA | Gate-sensitive |
| Set `04e8:6860` | None | Unknown target dependency | If required: NO-GO |
| Set Samsung product/serial | None | Unknown target dependency | If required: NO-GO |
| Detect AOA `START` ioctl | Framework handles it | No | Replaced by attachment intent/enumeration |
| Set `18d1:2d00` | Framework/kernel handles it | No | Standard AOA behavior |
| Open `/dev/usb_accessory` | Not directly | Yes, via framework equivalent | `openAccessory()` |
| Read/write AOA bytes | Yes | Yes | File streams over PFD |
| Control endpoint sizes | No | Usually no | Stream parser must tolerate fragmentation |
| Protocol V1/V2 | Yes | Yes | Kotlin/Java or JNI |
| H.264 encoding | Yes | Yes | MediaCodec surface encoder |
| Touch return | Yes | Yes | Parse stream; optional AccessibilityService |

## Formal gate criteria

### Promote to `GO`

All of these must be observed on a stock non-root phone connected to the driver-side port:

1. Android reports `FEATURE_USB_ACCESSORY`.
2. GE13 causes a non-null `UsbAccessory` to appear.
3. Its identity strings are retrievable and stable across at least three reconnects.
4. A matching test handler receives `USB_ACCESSORY_ATTACHED` and can obtain permission.
5. `openAccessory()` returns a usable descriptor.
6. The APK can send the 512-byte probe and receive a QDLink response over that descriptor.
7. No action before step 2 requires app-controlled VID/PID or gadget configuration.

### Change to `NO — architectural blocker`

Any repeatable finding that the target requires one of these before exposing the AOA stream:

- A fixed Samsung or other nonstandard phone VID/PID unavailable on the test phone.
- Private USB descriptors or composite functions that a stock phone does not present.
- APK-controlled AOA re-enumeration, FunctionFS endpoint, or USB gadget ioctl.
- A vendor kernel/service permission inaccessible to a normal application.
- Direct raw endpoint behavior that `UsbAccessory` streams cannot provide and that is mandatory to establish the session.

An ordinary NDK library cannot bypass Android's USB gadget security boundary. Root/custom kernel/device-owner/OEM signing would violate product requirements and therefore do not convert NO-GO to GO.

## Plug-and-play handler behavior

### Accessory filter and automatic launch

The application can declare:

- `android.hardware.usb.accessory` feature.
- an activity intent filter for `android.hardware.usb.action.USB_ACCESSORY_ATTACHED`.
- an XML `usb-accessory` element matching captured manufacturer/model and, only if stable and necessary, version.

The system may start the matching activity when GE13 attaches. Because attachment begins in a system-routed activity, the application can immediately establish UI-visible state and start its connected-device foreground service without a daily Connect button.

Use the official `Neusoft / QDriveLink / 1.0` filter for the first diagnostic build, then compare it with captured target values. Do not broaden it to a wildcard. A later product filter may omit version only if target captures show instability or absence and collision testing is safe.

### Permission persistence

| Mechanism | Lifetime/behavior |
|---|---|
| `requestPermission(accessory, PendingIntent)` | Temporary; valid until the accessory disconnects |
| User chooses the app as default for the accessory | Persisted routing; `hasPermission()` may be true on later attachments |
| Package reinstall/clear data | Assume routing/setup may be lost; test |
| App update | Expected to retain package default, but test across target phone builds |
| Different manufacturer/model/version | Different match/default key; chooser can return |

No public API lets our application silently set or replace the default.

### Official QDLink coexistence

If official QDLink remains installed and matches the same strings, standard AOSP behavior is:

1. First match: confirmation or resolver/chooser.
2. User selects our application and the persistent option.
3. Future matching attachments route directly to our application and grant it access.

Removal or disabling of official QDLink should not be a normal requirement. It becomes a documented workaround only if a target phone's OEM resolver or a privileged official package prevents persistent selection. That behavior is `UNKNOWN` until tested.

## Long-running session and process lifecycle

Use a `connectedDevice` foreground service because the session continuously transfers data to an attached USB accessory. On Android 14+, declare `FOREGROUND_SERVICE_CONNECTED_DEVICE`; granted USB access satisfies its runtime prerequisite. The attachment activity should start/promote the service promptly and pass a durable session request rather than owning the transport itself.

Expected recovery design:

- Cable detach: close PFD, cancel reads, stop encoder, clear parser/session state.
- Short cable bounce: debounce UI only; never reuse a stale PFD.
- Reattach: accept the new `UsbAccessory`, reopen, probe, demand fresh IDR.
- HU reboot: treat as detach/re-enumeration or read timeout; reconnect automatically.
- App process death while attached: on service restart enumerate `accessoryList` and reopen only if permission remains; Android may not redeliver the original attach intent.
- Phone unlock: reevaluate accessory enumeration and UI state; do not depend on unlock if the OEM permits attach while locked.
- Phone reboot: default routing may persist, but Bluetooth/accessibility and auto-start behavior must be verified.

The OS may stop apps or let users stop an FGS. “Automatic recovery” means best effort within Android policy, not an unkillable daemon.

## Permissions and unavoidable dialogs

| Capability | First setup | Later custom-UI session | Can APK automate? |
|---|---|---|---|
| USB default handler | System chooser/confirmation | Ideally none | No; user selection required |
| Temporary USB permission without default | System dialog | Can recur each attach | No |
| Foreground-service notification | Always required to post service notification; Android 13 notification permission affects visibility | Notification remains | No hiding the service requirement |
| Accessibility reverse control | User enables service in Settings | No recurring prompt while enabled | Cannot silently enable |
| Bluetooth connect on Android 12+ | Nearby Devices permission; pairing/HU prompts as needed | Usually automatic when paired | Cannot approve/pair silently |
| MediaProjection | System capture consent | Required for each new session on current Android | Cannot automate |
| Custom rendered UI | No screen-capture consent | No screen-capture prompt | Yes, app owns pixels |

The custom UI should function even if Accessibility or Bluetooth permissions are absent; those capabilities should degrade independently.

## Landscape and automatic session start

- A dedicated projection activity can request landscape automatically.
- More robustly, the app can render an off-screen/custom scene directly into the encoder Surface at the negotiated landscape dimensions, independent of the phone display orientation.
- The handshake begins as soon as the descriptor is open; no extra Connect button is technically required.
- Video begins only after the protocol's play/video-control state and an IDR-ready encoder.
- A visible activity may still be necessary for reliable launch/UI under OEM background-activity rules. The daily target is no taps, not necessarily no phone-screen transition.

## Video feasibility

MediaCodec can create an AVC encoder configured with `COLOR_FormatSurface`; `createInputSurface()` supplies a Surface for the test pattern or custom driving renderer. Encoder output is available as byte buffers. The packetizer must:

- Normalize to Annex-B/start-code form if the codec emits length-prefixed access units; the official app forwards MediaCodec buffers without a visible AVCC conversion.
- cache `csd-0`/`csd-1`, send them as a separate configuration packet before frames, and resend on HU request.
- honor codec-config buffers and output-format changes.
- request an IDR on HU demand using the sync-frame parameter **and** resend SPS/PPS; the official app visibly does the latter but no immediate sync-frame call was found.
- preserve one QDLink frame/access-unit boundary.
- implement complete 512-padded writes without blocking the control reader.

Hardware encoding is widely available but exact GE13 dimensions/profile support must be queried from the phone and tested.

## Input feasibility

An APK can parse QDLink touch and drive its own UI directly. For other foreground apps, an enabled AccessibilityService can dispatch taps, swipes, long presses, drags, and multi-stroke gestures. Reconstruct gestures from DOWN/MOVE/UP into a complete path; do not dispatch every MOVE as a separate swipe.

Known limitations:

- Explicit user enablement is required.
- Coordinate mapping must be calibrated.
- Secure/system surfaces and some app behavior may reject or alter interaction.
- Only a target capture can establish GE13 multitouch.

## Custom UI versus third-party rendering

Custom UI is compatible with zero-tap projection because the app owns and encodes its Surface. This includes a driving launcher, app controls, media metadata, and navigation information obtained through legitimate APIs.

Waze/Google Maps pixels are not available merely because the APK launches or controls those apps. Whole-display or selected-app capture requires MediaProjection, whose recurring consent prevents guaranteed zero-tap daily startup. Protected content may also be blank. Third-party mirroring is therefore optional and separate from the core plug-and-play architecture.

## Bluetooth/audio feasibility

Initial milestones should use existing Bluetooth profiles rather than invent QDLink audio. The user must pair once and grant Nearby Devices where applicable. The HU's documented automatic Bluetooth setting may reconnect profiles after successful USB identification. The APK should respond to any required QDLink control message but should not assume it can force profile connections using public APIs. Target tests decide whether audio is fully automatic.

## Kotlin/JNI decision

### Recommendation: **A. Kotlin/Java only for the Phase-1 protocol proof**, with module boundaries that permit a later native implementation.

Reasoning, now also supported by the official APK:

- The mandatory USB entry point is Android framework AOA; the official client uses it directly, and native code cannot provide forbidden gadget functions.
- QDPlay's protocol consists of byte-order conversion, bounded framing, JSON, CRC16, typed SSP values, and state transitions—well within Kotlin performance for control traffic. The official app's only native library is a narrow proprietary SSP typed-value helper, not USB/video code.
- H.264 encoding and Surface flow are already Android APIs.
- Keeping USB ownership, lifecycle, permissions, and services in Kotlin minimizes cross-language teardown bugs.
- Clean-room implementation avoids importing QDPlay GPL code into a differently licensed product.

JNI should be added only after measurement demonstrates a specific need, such as sustained packet-copy cost at the target video rate. If added, use a narrow pure-byte codec interface; never move `UsbAccessory`, PFD ownership, Activity, or service lifecycle into native code.

This recommendation does not claim Kotlin can emulate USB gadget identities. If the target requires that capability, the gate is NO-GO for every normal-APK language choice.

## Final feasibility answer

**LIKELY — missing target validation.** Evidence supporting it:

1. QDPlay completes QDLink without the official app once an AOA stream exists.
2. QDPlay separates gadget establishment from post-AOA byte protocol.
3. Android officially exposes the equivalent byte stream to normal apps through `UsbAccessory`.
4. The official GE13-family manual shows Android's standard USB accessory/default dialog.
5. Android provides hardware AVC encoding and Accessibility gesture dispatch.
6. A custom-rendered UI avoids recurring MediaProjection consent.
7. Official QDLink 1.9.7 independently demonstrates public AOA, direct AVC-over-AOA, touch decode, and Android Accessibility integration.

The biggest blocker risk is a target-specific pre-AOA identity requirement or protocol variation. The first in-car USB gate test must resolve it before substantial Phase-1 investment.
