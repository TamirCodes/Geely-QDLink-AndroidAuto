# Phase 0 source audit

Audit date: 2026-08-24  
Target: Geely Geometry C 480 2024 / FES5PL, GE13, `HU716P.00-BEH`

## Evidence labels

- `VERIFIED_FROM_SOURCE`: verified in the cited source code at the recorded commit.
- `VERIFIED_FROM_OFFICIAL_DOC`: stated by Geely, Neusoft/Google Play, or Android documentation.
- `OBSERVED_ON_TARGET`: observed on the exact target. In this audit, the version identifiers are user-provided observations; no live vehicle capture was available.
- `INFERRED`: technically supported interpretation that is not directly demonstrated on the target.
- `UNKNOWN`: not established by the available evidence.

Repository dates below are commit dates, not claims that the projects are maintained or compatible with GE13.

## Primary source: QDPlay

| Field | Value |
|---|---|
| Repository | [nikit6000/QDPlay](https://github.com/nikit6000/QDPlay) |
| Branch | `develop` |
| Commit | [`b6464e9c05fe690a83b1d6c1d66227059da30ee1`](https://github.com/nikit6000/QDPlay/tree/b6464e9c05fe690a83b1d6c1d66227059da30ee1) |
| Commit date | 2024-05-29 |
| License | GPL-3.0 (repository `LICENSE`); bundled cJSON files carry the MIT license |
| Trust | High for what QDPlay implements; low-to-medium for exact GE13 compatibility until tested |
| Purpose | Direct phone-side QDLink implementation on a Linux SBC and bridge to a local H.264 producer/input consumer |

The complete tracked tree was enumerated. The audit concentrated on every requested area:

- `src/main.c`
- `src/hu_message.c`, `inc/hu_message.h`
- `src/messages/v1`, `src/messages/v2`, and their headers
- `src/services/video_receiver`, `src/services/messaging_service`
- `src/usb_accessory/usb_accessory.c`
- `src/usb_accessory/usb_accessory_worker.c`
- `src/usb_accessory/usb_active_accessory.c`
- both message processors under `src/usb_accessory/message_processor`
- `video-sender` and `control-receiver`
- `inc/accessory.h`, command/data constants, and CRC utilities

## Source-Verified Preliminary Findings

Key QDPlay findings (`VERIFIED_FROM_SOURCE`; not `OBSERVED_ON_TARGET`):

- QDPlay makes its Linux board act as the USB device; the vehicle is the USB host.
- It first creates a Samsung-like gadget (`04e8:6860`) and, after the HU issues AOA `ACCESSORY_START`, reconfigures to Google AOA `18d1:2d00`.
- It opens `/dev/usb_accessory` and then runs the QDLink application protocol over a single bidirectional byte stream.
- It implements two incompatible QDLink generations: fixed/chunked V1 `!BIN` and V2 `5A5A`.
- V1 embeds a second ASCII/hex-framed HU-message format beginning `A6A6`.
- Both generations transport H.264 video and return touch events. Both support HU key-frame requests.
- No QDLink audio stream is implemented; QDPlay's README directs audio through Bluetooth.
- Automatic display-size discovery is only partially wired: both protocols receive HU dimensions, but QDPlay's external video producer retains its configured dimensions.
- The source contains parser and lifecycle defects that must not be copied verbatim. Examples include no received `A6A6` CRC validation, insufficient bounds validation, V2 zero-touch division, unchecked partial writes, and a suspicious V2 remaining-read comparison.

QDPlay proves a protocol implementation against unspecified Geely-family receivers. It does **not** state or demonstrate `HU716P.00-BEH`, `SWGE13A0623H8BEH.00019`, or FES5PL.

## Official QDLink application material

| Field | Value |
|---|---|
| Distribution | [QDLink on Google Play](https://play.google.com/store/apps/details?id=com.neusoft.qdrivelink&hl=en-US) |
| Package | `com.neusoft.qdrivelink` |
| Developer | Neusoft / 东软集团股份有限公司（萌驾） |
| Listing update observed | 2026-04-07 |
| License | Proprietary; Play distribution terms, no source license |
| Trust | High for declared product behavior; not a wire-protocol specification |

Findings (`VERIFIED_FROM_OFFICIAL_DOC`): the listing says QDLink connects to the car display, mirrors the phone, permits control in both directions, and uses `AccessibilityService` for reverse operation. It does not publish protocol fields, codecs, AOA strings, or compatibility by HU firmware.

### APK analysis status

`UNKNOWN`: no Android test device containing the official app was connected and no APK was obtained from an official/test-device source. Random APK mirrors were deliberately not used. Therefore this phase makes no claims about the official manifest, accessory filter, native libraries, JNI, or private constants beyond public documentation. A future authorized test-device pull should record APK version/hash before analysis.

## Official Geely documentation

| Field | Value |
|---|---|
| Document | [Geely Israel Multimedia / GE13 connection manual](https://geely.co.il/wp-content/uploads/2025/01/Multimedia-GE13_final_2-connections.pdf) |
| PDF | `Multimedia-GE13_final_2-connections.pdf`, 50 pages |
| Relevant PDF pages | 33-36 (printed pages 30-33) |
| Document metadata | PDF creation date 2021-10-05; hosted in the 2025/01 path |
| License | Copyrighted manufacturer documentation; quotation/reuse only as permitted |
| Trust | High for documented GE13-family user flow; not proof for the exact 2024 firmware |

The relevant pages were rendered and visually checked, not only text-extracted. Findings (`VERIFIED_FROM_OFFICIAL_DOC`):

- QDLink supports Android and iOS USB connection.
- The driver-side USB port supports QDLink; the front passenger-side port does not.
- After Android is connected, the HU identifies it and shows a connection progress bar.
- The Android example shows the standard accessory dialog with “Use by default for this USB accessory”.
- The manual then requires screen-capture approval and `START NOW`.
- An optional HU setting automatically connects Bluetooth after successful USB identification; another-phone conflicts can produce an HU prompt.
- The manual describes successful mirroring and reverse control but provides no wire details.

## Secondary QDLink source: carplayx50

| Field | Value |
|---|---|
| Repository | [cbtham/carplayx50](https://github.com/cbtham/carplayx50) |
| Branch/commit | `main` / [`57999a92d2d439db9073ba0b286100c37463cee7`](https://github.com/cbtham/carplayx50/tree/57999a92d2d439db9073ba0b286100c37463cee7) |
| Commit date | 2021-04-14 |
| License | No license file or grant found: all rights reserved by default |
| Trust | Community lead only |

The repository is essentially a README plus images. It describes Proton X50/Coolray experiments using the official QDrive app on an Android intermediary. It supports the topology that the HU expects an Android USB-device endpoint, but contains no reusable protocol implementation and no GE13/HU716P evidence. Its statements about touch and USB hubs are historical observations, not protocol facts.

## Android Auto reference sources

These are architectural references only. Android Auto and QDLink are distinct protocols.

### open-android-auto

| Field | Value |
|---|---|
| Repository | [mrmees/open-android-auto](https://github.com/mrmees/open-android-auto) |
| Branch/commit | `main` / [`61eab61c5f9968154ff1a80faa8c0a427b208479`](https://github.com/mrmees/open-android-auto/tree/61eab61c5f9968154ff1a80faa8c0a427b208479) |
| Commit date | 2026-07-26 |
| License | GPL-3.0 |
| Trust/purpose | Medium-to-high as a modern research corpus; service discovery, lifecycle, input/video architecture |

Relevant areas include `docs/protocol-*`, `docs/interactions`, protocol definitions, verification/provenance material, and reference implementations. It is not used as evidence for QDLink wire values.

### aasdk

| Field | Value |
|---|---|
| Repository | [f1xpl/aasdk](https://github.com/f1xpl/aasdk) |
| Branch/commit | `development` / [`046b3b381595509d0939fa84b14a90978f46ff63`](https://github.com/f1xpl/aasdk/tree/046b3b381595509d0939fa84b14a90978f46ff63) |
| Commit date | 2018-07-17 |
| License | GPL-3.0 stated in README |
| Trust/purpose | High for its implemented AA architecture at that revision; AOAP, USB/TCP, SSL, video/input/audio/sensor channels |

Relevant areas: `src/USB`, `src/Transport`, `src/Messenger`, `src/Channel`, headers, protobuf messages, and tests. Its AOAP direction is the inverse product role (a head-unit receiver controls the phone), but the separation of transport, framing, and services is useful.

### open-headunit

| Field | Value |
|---|---|
| Repository | [andreknieriem/open-headunit](https://github.com/andreknieriem/open-headunit) |
| Branch/commit | `main` / [`048f4eaf663cbb9d9589ebd9c4233d4a262759a3`](https://github.com/andreknieriem/open-headunit/tree/048f4eaf663cbb9d9589ebd9c4233d4a262759a3) |
| Commit date | 2026-08-24 |
| License | AGPL-3.0 |
| Trust/purpose | Medium; Android receiver lifecycle, USB, rendering, touch scaling, reconnection |

This project is an Android head-unit receiver, not a phone-side projection source. It is useful for resilient Android USB and Surface/MediaCodec patterns but carries strong copyleft obligations.

Files inspected include `UsbAttachedActivity.kt`, `UsbAccessoryMode.kt`, `UsbAccessoryConnection.kt`, `TouchCoordinateMapper.kt`, and `UsbSessionQuiescePolicy.kt`. They show HU-side `UsbDevice` host handling, exact-length stream buffering, coordinate transforms, and lifecycle recovery. They are architectural references only: their USB role is the opposite of the proposed phone APK's `UsbAccessory` role, and none is evidence for a QDLink wire value.

### OpenAutoLink

| Field | Value |
|---|---|
| Repository | [mossyhub/openautolink](https://github.com/mossyhub/openautolink) |
| Branch/commit | `main` / [`714704242fa33046953a972a48c556e76eb0a995`](https://github.com/mossyhub/openautolink/tree/714704242fa33046953a972a48c556e76eb0a995) |
| Commit date | 2026-08-24 |
| License | GPL-3.0-or-later per `THIRD_PARTY_LICENSES.md`; top-level README still says “TBD” |
| Trust/purpose | Medium; real Kotlin/C++ JNI integration, transport ownership, rendering, touch scaling, reconnect |

Relevant areas include `app/src/main/cpp`, `transport/aasdk`, `transport/usb`, `video`, `input`, `session`, and tests. It demonstrates a native protocol core behind Kotlin/JNI, but it runs on a head unit and speaks Android Auto, not QDLink.

Files inspected include `transport/usb/UsbAttachedActivity.kt`, `transport/usb/UsbAccessoryMode.kt`, `transport/aasdk/AasdkNative.kt`, `input/TouchScaler.kt`, `video/SurfaceRecoveryPolicy.kt`, and `app/src/main/cpp/CMakeLists.txt`. The source confirms a Kotlin-to-native aasdk bridge, a deliberate libusb stub on Android, touch scaling, and restart/key-frame recovery policies. These are design examples only; `UsbAccessoryMode.kt` is again the HU/accessory-host side sending AOA control requests to a phone.

## Android platform sources

These are living documentation/AOSP sources, accessed 2026-08-24; a repository commit date is therefore not asserted for the documentation pages. The AOSP links are pinned only by their displayed `master` URLs and must be rechecked before implementation against the target Android release.

| Source | Findings used | Trust |
|---|---|---|
| [USB accessory overview](https://developer.android.com/develop/connectivity/usb/accessory) | Accessory filter fields, attach intent, enumeration, permission, `openAccessory()` | Official |
| [UsbManager API](https://developer.android.com/reference/android/hardware/usb/UsbManager) | Default-vs-temporary permission and disconnect lifetime | Official |
| [AOA protocol](https://source.android.com/docs/core/interaction/accessories/aoa) | HU/accessory host sends identification strings and starts accessory mode | Official |
| [AOSP USB profile settings](https://android.googlesource.com/platform/frameworks/base/+/master/services/usb/java/com/android/server/usb/UsbProfileGroupSettingsManager.java) | Persisted accessory default routing | Primary platform source |
| [AOSP USB resolver](https://android.googlesource.com/platform/frameworks/base/+/master/packages/SystemUI/src/com/android/systemui/usb/UsbResolverActivity.java) | Chooser/default behavior; OEM implementations may vary | Primary platform source |
| [MediaProjection](https://developer.android.com/media/grow/media-projection) | Consent and foreground-service rules | Official |
| [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService) | `dispatchGesture()`, gesture capability | Official |
| [Foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types) | `connectedDevice`, USB prerequisite | Official |
| [Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions) | Android 12+ Nearby devices approval | Official |

## Community/target search

Exact searches for `HU716P.00-BEH`, `SWGE13A0623H8BEH`, and related GE13 terms produced no source-code or authoritative compatibility record. A public Geometry C 480 discussion contains unverified claims of a working QDLink-based solution without installation on the HU. It is recorded only as weak feasibility evidence and must not justify code or binary use.

## Audit conclusion

QDPlay is sufficient to map a plausible QDLink application protocol. Official Android and Geely documentation jointly support standard AOA handling by a real Android phone. They do not prove that the exact target accepts our post-AOA implementation or that it has no pre-AOA phone-identity dependency. The evidence therefore supports a `CONDITIONAL GO`, equivalent to the project-level conclusion `LIKELY — missing target validation`.
