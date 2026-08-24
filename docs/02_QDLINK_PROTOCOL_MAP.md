# QDLink protocol map from QDPlay

This document describes QDPlay commit `b6464e9c05fe690a83b1d6c1d66227059da30ee1`. Unless marked otherwise, every wire claim is `VERIFIED_FROM_SOURCE` for QDPlay and **not** an observation from `HU716P.00-BEH`.

## Layer model and USB topology

```text
GE13 / QDLink HU                    Linux QDPlay device
USB HOST                            USB DEVICE
    |                                   |
    |-- AOA GET_PROTOCOL -------------->|
    |-- AOA SEND_STRING[0..5] --------->|  handled by Linux accessory function
    |-- AOA START --------------------->|
    |                                   |  QDPlay re-enumerates as 18d1:2d00
    |<========= bulk AOA stream =======>|
    |         QDLink V1 or V2           |
```

`A6A6` is not the USB transport and not the universal top-level QDLink frame. It is a nested message format used inside one V1 `HU_MSG` payload.

### QDPlay's Linux-only USB identity layer

QDPlay runs on a Linux SBC that otherwise is not an Android phone, so it creates a USB gadget via configfs/libusbgx:

| Phase | VID:PID | Manufacturer | Product | Serial |
|---|---:|---|---|---|
| Initial imitation | `04e8:6860` | `Samsung Electronics Co., Ltd.` | `SAMSUNG_Android` | `R5CR103RSQE` |
| After AOA start | `18d1:2d00` | `Google Inc.` | `SAMSUNG_Android` | `R5CR103RSQE` |

The initial pair resembles a Samsung composite Android identity. `18d1:2d00` is the standard AOA accessory VID/PID without ADB; `2d01` is defined but unused.

QDPlay polls `ACCESSORY_IS_START_REQUESTED` on `/dev/usb_accessory`, then disables/reconfigures the gadget and reopens the same device node. It does not read or log the AOA strings supplied by the HU even though matching `ACCESSORY_GET_STRING_*` ioctls are present in its header.

### Real Android phone boundary

On a stock Android phone the operating system, USB gadget driver, and framework perform the device-mode and AOA transition. A normal APK begins at `USB_ACCESSORY_ATTACHED`/`UsbAccessory`; it cannot and should not recreate configfs, VID/PID, FunctionFS, or `/dev/usb_accessory`.

Candidate flow (`INFERRED`, pending target capture):

```text
Stock Android phone / candidate APK              GE13 HU

Android presents its normal USB identity   <---- USB enumeration by host
                                           <---- AOA GET_PROTOCOL
Android framework receives strings         <---- AOA SEND_STRING 0..5
Android enters accessory mode               <---- AOA START
System matches accessory filter
System launches chosen/default Activity
APK obtains permission and opens UsbAccessory
APK sends 512-byte V1 APP_STATUS            ---->
APK receives V1 VERSION or V2 CAR_INFO      <----
QDLink application state machine continues
```

The exact HU-supplied manufacturer/model/version/description/URI/serial are `UNKNOWN`.

## Transport after AOA

QDPlay uses one bidirectional file descriptor. There is no evidence of additional physical USB endpoints exposed to the application protocol and no TCP layer. The apparent video/control “channels” are logical packet types on the same stream.

QDPlay also exposes two **local-only** Unix sockets:

- `/tmp/qdplay.video.socket`: H.264 producer to QDPlay.
- `/tmp/qdplay.messaging.socket`: touch and key-frame notifications from QDPlay to the producer/controller.

These sockets are process separation inside the SBC and are not sent to the vehicle.

QDPlay pads many protocol messages to 512-byte boundaries. It assumes complete reads/writes more aggressively than a robust Android port may. Android's `ParcelFileDescriptor` streams must implement exact-length accumulation and complete write loops; packetization cannot assume each Java read equals one QDLink frame.

## Protocol detection

QDPlay sends one 512-byte V1-shaped `APP_STATUS` request immediately after opening the active accessory. It then reads a 512-byte response and tests processors in order:

1. V1 probe: response is a V1 `VERSION` request from the HU.
2. V2 probe: response starts `5A5A`, source is car, payload is JSON, command is `CAR_INFO`.

This is protocol probing rather than service discovery in the Android Auto sense. No generic directory of QDLink services is present in QDPlay.

## V1 framing: `!BIN`

All multi-byte integer fields are treated as big-endian by QDPlay.

### Common header (64 bytes)

| Offset | Size | Field |
|---:|---:|---|
| 0 | 4 | ASCII `!BIN` |
| 4 | 4 | data type |
| 8 | 4 | total size |
| 12 | 4 | header size, commonly 512 |
| 16 | 4 | common-header size, 64 |
| 20 | 4 | request-header size |
| 24 | 4 | response-header size |
| 28 | 4 | action |
| 32 | 32 | marker bytes; QDPlay fills `0x20..0x3f` in responses |

For command packets, a command header follows with a 32-bit timestamp and 32-bit command.

### Known V1 data types

| Value | Name | Direction/role seen by QDPlay |
|---:|---|---|
| 0 | `CMD` | Bidirectional control |
| 1 | `SCREEN_CAPTURE` | Phone video to HU; HU screen-parameter request/ack path |
| 2 | `KEY_EVENT` | Defined, not handled by QDPlay |
| 3 | `HU_MSG` | Nested `A6A6` application messages |
| 5 | `UPGRADE` | Start-sequence response |
| 10 | `IN_APP_CONTROL` | HU touch to phone |
| 12 | `SPEECH_STATUS` | Received and drained; no useful audio implementation |
| 13 | `APP_DATA` | Defined, not handled |
| 99 | `CUSTOM_STATUS` | Defined, not handled |

### Known V1 commands

| Value | Name | QDPlay behavior |
|---:|---|---|
| 1 | `APP_STATUS` | Initial phone probe, action 2 |
| 3 | `VERSION` | HU response/probe; contains car dimensions |
| 5 | `LAND_MODE` | QDPlay echoes requested value |
| 10 | `HEARTBEAT` | Keepalive |
| 12 | `PLAY_STATUS` | Activates forwarding and requests an IDR locally |
| 13 | `UPGRADE` | Sent after version response |
| 16 | `MIRROR_SUPPORT` | Triggers `phoneready` plus mirror-support response |
| 17 | `KEY_FRAME_REQ` | Passed to the video source |

### Nested V1 `A6A6` packet

The entire structure is ASCII. Numeric values are fixed-width hexadecimal text.

```text
A6A6
FF                    flow_id: 2 hex characters
LLLLLLLL              total/chunked length: 8 hex characters
AA app-id bytes       app-id length: 2 hex chars, then bytes
BB logic-id bytes     logic-id length: 2 hex chars, then bytes
CC                    item count: 2 hex characters
  IIIIIIII data...    for each item: 8-hex length, then bytes
CCCC                  CRC16/XMODEM: 4 hex characters
```

QDPlay computes CRC16/XMODEM over the unpadded encoded bytes excluding the four CRC characters. The encoded total-length field is the padded/chunked length when a chunk size is supplied. In the QDPlay V1 sender, the nested message is put after a 512-byte V1 application header and the complete transmission is padded to a 512 boundary.

Important limitation: `hu_message_decode()` checks the marker and a coarse total length, but does not validate the received CRC and does not comprehensively bound every cursor advance. A port must validate marker, declared length, field lengths, item count, CRC, and integer overflow before allocation.

Known V1 application messages use app ID `QDRIVE_ASSISTANT`:

| Incoming logic ID | QDPlay response |
|---|---|
| `HUINFO` | `phoneinitok`, then `NEEDUPGRADE` with JSON-like payload |
| `BT_AUTO_CONNECTED` | same logic ID with a success-like tuple payload |
| mirror-support command | separate `phoneready` application message plus command response |

The meanings of the nested type notation (`D`, `T`, `V`) are only partially inferred from literal strings.

## V1 connection sequence

```text
Phone/QDPlay                                          QDLink HU

APP_STATUS request (512, !BIN/CMD/action=2/cmd=1)  ---->
                                                    <---- VERSION request
VERSION response; phone/out-app size = car size    ---->
UPGRADE response                                   ---->
                                                    <---- HU_MSG/A6A6 HUINFO
HU_MSG/A6A6 phoneinitok                            ---->
HU_MSG/A6A6 NEEDUPGRADE                            ---->
                                                    <---- LAND_MODE
LAND_MODE response                                 ---->
                                                    <---- MIRROR_SUPPORT
HU_MSG/A6A6 phoneready                             ---->
MIRROR_SUPPORT response                            ---->
                                                    <---- PLAY_STATUS
activate video; request local IDR
H.264 screen packets                               ---->
                                                    <---- touch / keyframe / heartbeat
heartbeat when idle                                ---->
```

This is the sequence implied by independent handlers, not a captured transcript. Exact ordering and optional messages are `INFERRED`.

### V1 dimensions and video header

The HU's `VERSION` contains `car_width` and `car_height`. QDPlay replies with those values as `phone_width`, `phone_height`, `out_app_width`, and `out_app_height`.

Each H.264 transmission contains:

- A 512-byte `SCREEN_CAPTURE` header.
- Raw H.264 frame bytes after that header.
- Zero padding so the frame payload is a multiple of 512.

Header values used by QDPlay include H.264 encoding type `3`, dimensions repeated across multiple fields, `screen_orientation=0`, `screen_capture_orientation=90`, landscape/direction-like fields, caller-supplied frame rate, bit rate `0`, and frame interval `1`. A code comment and assignment disagree for `screen_capture_direct` (comment says 1, code writes 90); this field is `UNKNOWN`.

## V2 framing: `5A5A`

### Common header (16 bytes)

| Offset | Size | Field |
|---:|---:|---|
| 0 | 4 | ASCII `5A5A` |
| 4 | 4 | full message length, big-endian |
| 8 | 2 | extended-header size, big-endian |
| 10 | 1 | message type (`1` video, `2` touch) |
| 11 | 1 | source (`0` phone, `1` car) |
| 12 | 1 | destination; semantics unknown |
| 13 | 1 | payload format (`0` binary, `1` JSON, `2` video) |
| 14 | 1 | reserved |
| 15 | 1 | unknown |

Known JSON commands are `HEARTBEAT`, `PHONE_INFO`, `CAR_INFO`, `VIDEO_SUP_REQ`, `VIDEO_SUP_RSP`, `KEY_FRAME_REQ`, `VIDEO_CTRL`, and `UPDATE_NOTIFY`.

### V2 connection sequence

```text
Phone/QDPlay                                           QDLink HU

V1 APP_STATUS compatibility probe                   ---->
                                                     <---- 5A5A JSON CAR_INFO
parse CarWidth/CarHeight
5A5A JSON PHONE_INFO using HU dimensions            ---->
5A5A JSON UPDATE_NOTIFY(UpdateStatus=5)              ---->
                                                     <---- VIDEO_SUP_REQ
VIDEO_SUP_RSP: support=1 only when VideoFormat=3     ---->
                                                     <---- VIDEO_CTRL PlayStatus=1
activate H.264 forwarding
5A5A video packets                                   ---->
                                                     <---- KEY_FRAME_REQ
request IDR from producer
                                                     <---- binary touch messages
HEARTBEAT during output idle                         ---->
```

`PHONE_INFO` identifies a Samsung Galaxy A50, Android/platform version 31, QDLink version 1.7.7, platform 0, mirror support 2, and sets all relevant phone/mirror dimensions to the HU dimensions. These are compatibility literals in QDPlay, not proven requirements.

### V2 video

The 16-byte common header is followed by a packed 32-byte extended video header:

| Field | QDPlay value |
|---|---|
| extended size/type | 32 / 1 |
| width/height | supplied by local producer |
| orientation | `0x005a` (90 decimal) |
| landscape | 1 |
| encoding type | 3 (H.264) |
| frame rate | supplied by local producer |
| bit rate | 0 |
| frame interval | 0 |
| in/out app | 1 |

The frame follows directly and the complete message is zero-padded to 512 bytes. There is no per-frame CRC in QDPlay's V2 implementation.

## H.264 contract

QDPlay's README explicitly requires every submitted frame to begin with `00 00 00 01`, establishing Annex-B start-code framing. QDPlay does not parse NAL units, convert AVCC length prefixes, enforce a profile/level, or create SPS/PPS.

| Question | Evidence-based answer |
|---|---|
| Codec | H.264/AVC, encoding ID 3 (`VERIFIED_FROM_SOURCE`) |
| Annex-B or AVCC | Annex-B producer contract (`VERIFIED_FROM_SOURCE`) |
| SPS/PPS | `UNKNOWN`; QDPlay forwards producer bytes unchanged. Safe MVP assumption is SPS/PPS before/with each initial IDR until captured behavior says otherwise. |
| Profile/level | `UNKNOWN` |
| Frame rate | Producer-supplied; README images use 24 in comments but no protocol-wide required value is proven |
| Bitrate | V1/V2 headers send 0; actual accepted range `UNKNOWN` |
| IDR request | V1 command 17 and V2 JSON `KEY_FRAME_REQ`; V1 `PLAY_STATUS` also causes a local IDR request |
| Frame boundaries | One producer call is treated as one H.264 frame/access unit |

## Touch return path

### V1 touch

`IN_APP_CONTROL` contains signed 16-bit, big-endian values. QDPlay maps:

- `-32768`: DOWN
- `-32767`: MOVE
- `-32766`: intended UP; the implementation maps every unrecognized event to UP
- `event_value0`: X
- `event_value1`: Y

Additional fields (`event_number`, `event_value2/3`, `type`, `x/y`, `press`, `finger`) exist but are not interpreted. Coordinate units and range are `UNKNOWN`; values are forwarded as signed 16-bit integers.

### V2 touch

After the V2 common header:

```text
u32 action_id (big-endian)
u8  touch_count
repeat touch_count:
    u8  finger_id
    u8  action: 1 DOWN, 2 UP, 3 MOVE
    f32 x (four bytes transmitted big-endian)
    f32 y (four bytes transmitted big-endian)
```

The wire format supports multiple fingers and pointer IDs. QDPlay decodes all fingers but forwards only the first through its local IPC. Therefore multitouch is `VERIFIED_FROM_SOURCE` at the V2 parser/wire level, but not supported end-to-end by QDPlay and not verified on GE13.

Pressure is not present in the decoded V2 structure. Rotation and coordinate scaling are not applied.

## Session maintenance and teardown

- If QDPlay has transmitted nothing for three seconds, it sends the selected protocol's heartbeat.
- If nothing is received for five seconds, or the local video source disconnects, the read-watchdog exits.
- Teardown deactivates video, disables the gadget, closes the accessory descriptor, clears the processor, and joins worker threads.
- The outer worker returns to the initial-gadget/await loop. Local control clients have their own 250 ms reconnect retry.
- QDPlay does not implement exponential backoff, durable session state, process-death recovery, or Android lifecycle integration.

USB reconnect on Android must close the `ParcelFileDescriptor`, reset decoder/encoder and parser state, discard incomplete packets, wait for a new `UsbAccessory`, and require a fresh IDR.

## Bluetooth and audio

No H.264-adjacent or independent audio payload is sent by QDPlay. V1 contains an application-level `BT_AUTO_CONNECTED` exchange, but QDPlay only acknowledges it. QDPlay's README tells users to connect phone and HU via Bluetooth for audio.

The official GE13-family manual says the HU can automatically connect its Bluetooth after successful USB identification if enabled. Whether the QDLink acknowledgment triggers this on the exact target, and whether the phone must already be paired, are `UNKNOWN`.

## Answers to the required protocol questions

| # | Question | Answer |
|---:|---|---|
| 1 | USB host | HU (`VERIFIED_FROM_SOURCE` for QDPlay topology) |
| 2 | Side entering accessory mode | Phone/device side; QDPlay emulates it, Android normally performs it |
| 3 | VID/PID/descriptors | QDPlay uses `04e8:6860`, then `18d1:2d00`; real phone initial identity and GE13 dependency unknown |
| 4 | Phone-side client | Yes, QDPlay implements it directly |
| 5 | Vehicle-facing component | `usb_accessory*` plus selected V1/V2 processor |
| 6 | Official QDLink required | No for QDPlay; target still unverified |
| 7 | Post-negotiation transport | One bidirectional AOA byte stream |
| 8 | Logical channels | V1 data types or V2 message types/formats, multiplexed on that stream |
| 9 | Service discovery | No general discovery; probe plus fixed handshake/capability commands |
| 10 | Projection request | V1 play/mirror commands or V2 video-support/video-control commands |
| 11 | Video initialization | Dimension exchange, video-support/control, then H.264 after active state |
| 12 | Width/height | HU reports them in V1 `VERSION` or V2 `CAR_INFO`; repeated per frame |
| 13 | H.264 profile/level | Unknown |
| 14 | Annex-B/AVCC | Annex-B |
| 15 | SPS/PPS | Unknown; forwarded inline if producer includes them |
| 16 | FPS | Carried per frame; required target value unknown |
| 17 | Key frames | V1 command 17 / V2 `KEY_FRAME_REQ`; play start also requests one locally |
| 18 | Touch encoding | V1 signed 16-bit structure; V2 action/count/finger plus float coordinates |
| 19 | DOWN/MOVE/UP | Yes in both implementations |
| 20 | Multitouch | V2 wire parser yes; QDPlay IPC no; GE13 capability unknown |
| 21 | Coordinates | V1 signed 16-bit integers; V2 big-endian IEEE-754 float values |
| 22 | Orientation | Video headers contain fixed orientation/landscape values; dynamic signaling not found |
| 23 | Audio | No QDLink audio in QDPlay; Bluetooth assumed |
| 24 | Automatic Bluetooth | Protocol acknowledgment and official manual evidence exist; exact behavior unknown |
| 25 | Watchdog/reconnect | 3 s transmit idle heartbeat; 5 s receive timeout; full teardown/retry |
| 26 | Progress bar cause | Manual and README associate it with USB/session/video-source connection; exact wire milestone unknown |
| 27 | Completed connection | Video play/control active plus frames is the strongest source signal; exact HU UI signal unknown |
| 28 | Disconnect/reconnect | QDPlay tears down and recreates the gadget/session; Android design must wait for new accessory attachment |

## Target status

No packet capture exists from `SWGE13A0623H8BEH.00019` / `HU716P.00-BEH`. Every protocol detail above remains a candidate for the target until the safe in-car gate test identifies AOA strings, protocol version, command order, dimensions, and first accepted frame.
