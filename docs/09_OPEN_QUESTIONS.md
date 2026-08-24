# Open questions and blockers

Priority definitions:

- **P0:** Determines whether a normal APK architecture is possible.
- **P1:** Blocks the generated-video/touch proof.
- **P2:** Blocks plug-and-play product quality.
- **P3:** Later capability or optimization.

## P0 — mandatory USB feasibility gate

| Question | Current status | Decisive evidence/test |
|---|---|---|
| Does `HU716P.00-BEH` cause stock Android to expose a `UsbAccessory`? | `UNKNOWN`; official GE13-family manual strongly suggests yes | Test 2: public API enumeration and attach intent |
| What exact AOA manufacturer/model/version strings does the HU send? | `UNKNOWN`; QDPlay never reads them | Tests 2-3: log all `UsbAccessory` fields |
| Does GE13 require a specific initial phone VID/PID? | `UNKNOWN`; QDPlay chooses Samsung-like `04e8:6860`, but this may only emulate a generic phone | Compare multiple stock phones plus official QDLink baseline; observe whether public AOA is reached |
| Is any mandatory handshake performed before Android exposes the accessory? | `UNKNOWN` | Test 5: public PFD first-byte exchange; compare official flow |
| Can `openAccessory()` streams satisfy transfer boundaries and throughput? | `UNKNOWN` on target | Exact-length probe and sustained generated-video tests |
| Does the target require a private USB function, descriptor, or endpoint? | No evidence, but not disproved | Reproducible failure of public PFD with official app success and controlled diagnostics |

Resolution rule: any proven requirement for APK-controlled gadget VID/PID/configfs/FunctionFS is a Phase-1 `NO — architectural blocker`. JNI, root, custom kernel, ADB, or HU changes are not acceptable fallbacks.

## P1 — protocol and first-frame blockers

| Question | Current status | Decisive test |
|---|---|---|
| V1 `!BIN`, V2 `5A5A`, or another generation? | `UNKNOWN` | First response marker and validated transcript |
| Exact handshake ordering and optional commands? | Partly reconstructed from handlers, not captured | Test 6 ordered trace |
| Are QDPlay's fake Samsung phone metadata/app IDs mandatory? | `UNKNOWN` | Vary only after baseline works; compare acceptance |
| Which event completes the HU progress bar? | `UNKNOWN` | Timestamp UI progress against handshake and first video |
| Projection viewport width/height? | `UNKNOWN` | `VERSION`/`CAR_INFO` plus displayed test grid |
| Are physical panel, viewport, encoder, and touch spaces identical? | `UNKNOWN`; should not be assumed | Dimension record and edge calibration |
| H.264 profile/level? | Not constrained by QDPlay | Encoder compatibility matrix |
| Annex-B requirement? | Verified for QDPlay producer contract | Confirm target using first accepted frames |
| SPS/PPS separately or inline, and how often? | `UNKNOWN` | Vary initial/IDR codec configuration cadence |
| Required FPS/bitrate/GOP/max access unit? | `UNKNOWN` | Controlled matrix after first frame |
| Does padding need to be exactly 512-byte aligned? | QDPlay does it | Compare only after known-good baseline |
| How quickly must first IDR follow play request? | `UNKNOWN` | Timestamp request-to-first-frame and failure threshold |
| V1 orientation fields and suspicious `screen_capture_direct=90` meaning? | `UNKNOWN` | Captured target behavior and rotation tests |

## P1 — touch blockers

| Question | Current status | Decisive test |
|---|---|---|
| Does target send V1 integer or V2 float touch? | `UNKNOWN` | Test 10 raw events |
| Are DOWN/MOVE/UP all emitted reliably? | QDPlay supports them; target unknown | Tap/hold/swipe/drag traces |
| Multitouch? | V2 wire supports it; QDPlay drops extra fingers | Two-finger target test while parked |
| Pointer IDs stable across gesture? | `UNKNOWN` | Raw multi-event trace |
| Pressure present? | Not in QDPlay V2 mapping; unused V1 fields exist | Inspect capture |
| Coordinate origin/range/rotation? | `UNKNOWN` | Grid edges/corners and orientation test |
| HU chrome/insets included? | `UNKNOWN` | Compare visible viewport with raw min/max |

## P2 — plug-and-play and lifecycle

| Question | Current status | Decisive test |
|---|---|---|
| Can our APK be selected persistently as default? | Supported by Android/AOSP and manual screenshot; target phone unknown | Tests 4, 11, 12 |
| What occurs with official QDLink installed? | Expected chooser on first match | Test 11 with both apps enabled |
| Is official QDLink privileged or forced by OEM resolver? | `UNKNOWN` | Resolver/default behavior; no uninstall first |
| Is official app removal/disable required? | Expected no | Only conclude after repeated coexistence tests |
| Does auto-launch work while phone is unlocked and idle? | Platform supports attach launch | Ten-run Test 12 |
| Does it work while locked? | OEM/keyguard-dependent | Test 15 |
| Can a connected-device FGS start reliably from attach activity? | Platform architecture supported | Lifecycle/log test across phone versions |
| What if the process dies while USB remains attached? | Android may not redeliver attach | Test 16 enumeration/restart |
| Do defaults survive phone reboot and app update? | Expected but OEM-dependent | Test 17 |
| What happens after HU reboot or short cable loss? | QDPlay resets; Android target unknown | Tests 13-14 |
| Can video appear under five seconds? | `UNKNOWN` | Per-stage timestamps over ten runs |
| Can landscape/custom renderer start with no button? | Architecturally yes | Test 12/19 |

## P2 — prompts that cannot be automated

| Prompt/setup | Expected frequency | Open issue |
|---|---|---|
| USB resolver/default choice | First match or when default changes | Exact OEM UI and persistence |
| USB temporary permission | Each attach if no default | Avoid by persisted default where available |
| Accessibility enablement | First setup; remains until disabled/revoked | OEM battery/permission management |
| Bluetooth Nearby Devices | First permission grant | Permission revocation/update behavior |
| Bluetooth pairing/HU conflict | First pairing or competing phone | Profile-specific behavior |
| MediaProjection | Every new capture session on current Android | Incompatible with guaranteed zero-tap third-party mirroring |
| Foreground-service notification | Continuous system requirement | Notification permission changes visibility, not service requirement |

## P2 — Bluetooth/audio

- Does the HU initiate Bluetooth only after a particular QDLink message?
- Is QDPlay's `BT_AUTO_CONNECTED` acknowledgment sufficient/necessary?
- Must phone/HU already be paired?
- Which profiles reconnect: A2DP, HFP, AVRCP?
- What happens when another phone is connected?
- Can projection continue when Bluetooth fails?
- Does any GE13 protocol generation carry audio despite QDPlay not implementing it?

Tests 1, 6, and 18 resolve the product-critical subset. Audio remains non-blocking for M1.

## P2/P3 — third-party application rendering

The following distinction is resolved architecturally:

- Our own driving UI can render to MediaCodec without MediaProjection.
- Launching or controlling Waze does not expose Waze's pixels.

Open questions:

- Is consent-gated MediaProjection acceptable as an optional mode?
- Does the target phone's Android version permit full-display versus single-app capture?
- Does Waze render normally in the selected capture mode?
- How often does consent recur across cable reconnect, HU reboot, process death, and phone reboot?
- Are protected surfaces or overlays blank?
- Can supported Waze/Maps intents and public navigation data provide enough functionality for the custom UI without mirroring?
- Which product features must remain internal to preserve zero-tap behavior?

No technical plan should promise zero-tap Waze mirroring on current stock Android.

## P3 — performance and product hardening

- Required phone/Android minimum version and encoder capability floor.
- USB throughput and copy overhead at target resolution/bitrate.
- Thermal load and battery/charging behavior during long sessions.
- Glass-to-glass latency distribution and acceptable threshold.
- Frame-drop, recovery-IDR, and adaptive bitrate strategy.
- Day/night state source without vehicle-system integration.
- Safe notification/media/call metadata integrations.
- Whether Kotlin remains sufficient after profiling; JNI only for a measured bottleneck.

## Phase transition rule

Do not broaden Phase 1 beyond a diagnostic protocol proof until P0 is resolved as GO. If P0 becomes NO-GO, stop the normal-APK project rather than drifting into root, firmware, HU installation, or external hardware without a new explicitly approved product scope.
