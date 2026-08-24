# GE13 target profile

## Exact target

The following values are `OBSERVED_ON_TARGET (user-provided)` and are the authoritative target identifiers for this project:

```text
Vehicle: Geely Geometry C 480
Model year: 2024
Vehicle/model identifier: FES5PL
Generation: facelift / updated Geometry C
HU family: GE13

Software:
SWGE13A0623H8BEH.00019

Hardware:
HWGE131128M0191

Firmware:
HU716P.00-BEH
```

Factory QDLink is present and functional. Android Auto, Apple CarPlay, Android Settings, Developer Options, ADB, and arbitrary HU APK installation are not available in the observed product configuration.

No source, manual, post, or repository inspected in Phase 0 independently mentions the complete software or firmware identifiers. Similarity to other GE13, Coolray, Proton X50, GKUI19, or older Geometry C receivers is not compatibility proof.

## Constraints

- Use only the existing infotainment projection interface.
- Do not flash or alter HU/MCU firmware.
- Do not use CAN, vehicle ECUs, root, ADB, engineering menus, or HU-side APK installation.
- Perform all interactive tests while parked.
- Do not run third-party binaries or vehicle scripts from unverifiable sources.

## Known facts by evidence class

### `OBSERVED_ON_TARGET`

- The exact identifiers above.
- Factory QDLink exists and works with its supported application flow.
- No exposed Android Auto/CarPlay/settings/developer/ADB or installation path is known.

No packet capture, USB descriptor capture, display measurement, touch trace, or timing trace from this HU was available in Phase 0.

### `VERIFIED_FROM_OFFICIAL_DOC` for GE13 family

- The driver-side USB port is QDLink-capable; the passenger-side port is not.
- Android connection uses USB accessory approval.
- The HU displays a connection progress bar after identifying the phone.
- Android screen-capture approval is part of official mirroring.
- Reverse control and optional Bluetooth auto-connect are documented.

The manual PDF was created in 2021 and is GE13-family documentation. It cannot establish unchanged behavior in the 2024 target firmware.

### `VERIFIED_FROM_SOURCE` for QDPlay, not the target

- Geely-family QDLink implementations exist in at least two protocol forms.
- They can carry H.264 and return touch over a bidirectional AOA stream.
- Known project images use 1920x590 and 1760x720 configurations.
- HU dimensions can be reported by the protocol.

### `VERIFIED_FROM_OFFICIAL_APK_STATIC`, not the target

- Official QDLink 1.9.7 contains a `GE13-J2` model-routing literal.
- It does not contain the supplied FES5PL, HU716P, complete software string, or Geometry C name as plain text.
- V2 `CAR_INFO` can carry `CarType`, `ProjectID`, `CarUUID`, `CarFeature`, factories, and dimensions, so target routing may be delivered at runtime.
- Its active AOA attachment filter is `Neusoft / QDriveLink / 1.0`; the exact six values emitted by this HU still require capture.

## Display-space model

These four dimensions must remain separate until measured:

| Space | Definition | Target value |
|---|---|---|
| Physical panel | Actual HU LCD pixels | `UNKNOWN` |
| Projection viewport | Region QDLink reserves for video, possibly excluding HU chrome | `UNKNOWN` |
| Encoder frame | H.264 width/height sent by phone | `UNKNOWN`; should derive from negotiation/configuration |
| Touch coordinates | Coordinate range returned by HU | `UNKNOWN`; may match viewport, panel, normalized floats, or another space |

Never infer physical resolution solely from `CarWidth`/`CarHeight`. The field may describe a projection viewport. Record all values independently in the target test.

## USB/AOA target unknowns

| Question | Status | Why it matters |
|---|---|---|
| Does stock Android expose a `UsbAccessory`? | `UNKNOWN`, strongly expected | Mandatory feasibility gate |
| Manufacturer/model/version strings | Official APK expects `Neusoft / QDriveLink / 1.0`; target emission `UNKNOWN` | Needed for precise auto-launch filter/default mapping |
| Description/URI/serial | `UNKNOWN` | Diagnostics and possible package routing |
| AOA protocol version | `UNKNOWN` | Transport expectations |
| Pre-AOA phone identity dependency | `UNKNOWN` | A mandatory Samsung VID/PID would block a normal APK |
| Bulk transfer boundaries/endpoints | Framework abstracts them; target behavior `UNKNOWN` | Correct stream accumulation/performance |
| Official-app priority | `UNKNOWN` | Plug-and-play conflict handling |

## QDLink protocol target unknowns

- V1 `!BIN`, V2 `5A5A`, or another generation.
- Exact order and optionality of handshake messages.
- Required app/logic IDs and compatibility phone metadata.
- Trigger for progress bar and completion.
- Heartbeat interval and timeout tolerances.
- Handling of cable bounce and HU reboot.
- Whether any phase uses Wi-Fi after USB initialization.

## Video target unknowns

- Accepted width, height, aspect ratio, and alignment constraints.
- H.264 profile, level, entropy mode, reference frames, bitrate mode, and maximum bitrate.
- 24/30/60 fps support.
- Access-unit boundaries and maximum size.
- Whether target accepts the official separate `csd-0 + csd-1` packet strategy and resend cadence.
- AUD/SEI tolerance.
- IDR timing and startup deadline.
- Whether blank/padded bytes after frames are required exactly as QDPlay sends them.
- Expected color range and rotation semantics.

## Input target unknowns

- V1 integer or V2 floating-point events.
- DOWN/MOVE/UP reliability and event cadence.
- Pointer IDs/multitouch availability.
- Pressure or button/key events.
- Coordinate origin, bounds, rotation, and relationship to visible content.
- Behavior when the phone is locked or another app is foreground.

## Audio/Bluetooth target unknowns

- Whether QDLink contains any audio path not implemented by QDPlay.
- Whether `BT_AUTO_CONNECTED` is required for HU profile connection.
- Whether pairing must pre-exist.
- Which device initiates reconnect.
- Behavior when another phone is already connected.
- Whether navigation prompts/media/calls can coexist with the projection link without profile conflicts.

## Community evidence

Searches for the exact HU identifiers returned no authoritative source. Public Geometry C 480 owners have claimed QDLink-based Android Auto-like solutions without ADB, which is weak feasibility evidence only. No binary or instructions from those claims should be executed unless source, provenance, target identifiers, and behavior can be independently audited.

## Target conclusion

The target profile is compatible with the intended architecture at the product-description level: it has factory QDLink and official GE13 documentation shows standard Android accessory approval, screen projection, touch return, and Bluetooth assistance.

Compatibility at the protocol level remains `LIKELY — missing target validation`. The first target session must resolve the AOA gate before Phase 1 can be considered executable on this vehicle.
