# Android USB accessory transport semantics

## Conclusion

`VERIFIED_FROM_OFFICIAL_DOC`: `UsbManager.openAccessory()` returns a `ParcelFileDescriptor` for one bidirectional Android Open Accessory byte stream. An APK normally wraps that descriptor in `FileInputStream` and `FileOutputStream`.

`VERIFIED_FROM_OFFICIAL_DOC`: an accessory read buffer must be large enough for all data in a single USB transfer. If the app reads only part of that transfer, Android discards the unread remainder. The AOA guide states that packet buffers can be as large as 16,384 bytes and recommends a 16,384-byte buffer.

`LAB_IMPLEMENTED`: the probe app always calls `FileInputStream.read()` with a 16,384-byte buffer. Returned bytes are fed to a separate bounded QDLink accumulator. The PFD layer never calls `read(512)`.

Sources:

- [Android USB accessory guide](https://developer.android.com/develop/connectivity/usb/accessory)
- [UsbManager API reference](https://developer.android.com/reference/android/hardware/usb/UsbManager)

## Transfer boundaries versus QDLink boundaries

These units must not be conflated:

| Unit | Owner | Meaning |
|---|---|---|
| USB endpoint packet | USB controller | Low-level packetization; not exposed as QDLink framing |
| USB transfer | HU host + Android accessory function | One host request/delivery; app must use a sufficiently large read buffer |
| `InputStream.read()` result | Android framework/PFD | Bytes delivered for a transfer; can be shorter than the supplied 16 KiB buffer |
| QDLink frame | QDLink protocol | V1 `!BIN` or V2 `5A5A`, often padded to 512-byte alignment |
| H.264 access unit | encoder/application | One encoded AVC output unit placed into one QDLink video message |

`INFERRED`: one Android read may contain a partial QDLink frame, one complete frame, or multiple 512-byte protocol frames, depending on the host's writes and USB implementation. The parser therefore uses declared protocol lengths and never Java read boundaries.

## Safe read algorithm

```text
while attached:
    transfer = input.read(byte[16384])
    if transfer == EOF: disconnect
    append returned bytes to bounded accumulator

    while accumulator has a complete top-level frame:
        if marker == !BIN:
            validate total_size, header_size, common_header_size
            consume total_size (already 512-padded in known V1)
        else if marker == 5A5A:
            validate full_message_len
            consume roundUp512(full_message_len)
            require zero padding
        else:
            protocol error; reset session
```

Limits in the lab core:

- maximum accumulated/top-level frame: 16 MiB;
- checked integer conversion and addition;
- exact marker and declared-length validation;
- strict V2 zero-padding validation;
- strict nested `A6A6` field bounds and CRC16/XMODEM;
- no allocation from an unvalidated wire length.

The 16 MiB protocol bound is not a claim about GE13. It is a defensive application ceiling that comfortably exceeds expected compressed access units while preventing unbounded allocation.

## Write algorithm

`LAB_IMPLEMENTED`: one serialized writer wraps each complete padded QDLink frame in a `ByteBuffer` and loops until the `FileChannel` consumes all bytes. Heartbeat, control replies, and video cannot interleave. A write failure closes the generation and no stale PFD is reused.

The APK cannot choose endpoint size, host transfer grouping, configfs functions, or gadget identity. Those remain Android/kernel responsibilities.

## QDPlay comparison

QDPlay reads fixed 512-byte chunks from Linux `/dev/usb_accessory` and performs additional reads for the declared remainder. That behavior is `VERIFIED_FROM_SOURCE` for its Linux gadget path, but it is not a safe Android `InputStream` contract. Its success does not prove that an APK may use a 512-byte buffer without data loss.

Portable elements:

- V1/V2 declared-length parsing;
- 512-byte QDLink padding rules;
- message state machine;
- H.264 and touch packet definitions.

Non-portable assumption:

- treating a 512-byte read as a harmless first slice of a larger transfer.

## Validation completed

`OBSERVED_IN_LAB`:

- A pure-Kotlin accumulator reconstructs a V2 frame at every possible split point.
- It separates two coalesced padded frames.
- V1/V2 malformed lengths, padding, touch counts, non-finite coordinates, and A6A6 CRC failures are rejected.
- 2,000 deterministic bounded mutations complete without an unbounded allocation or hang.

`UNKNOWN`: GE13's actual transfer-size distribution, short-read behavior, sustained video throughput, detach exception, and whether the HU ever emits a transfer larger than 16 KiB. Record every PFD read count during the first vehicle probe.

## Threading and teardown

The lab architecture uses:

- one blocking reader;
- one synchronized writer path;
- one encoder drain thread;
- a monotonic heartbeat/watchdog scheduler;
- a session generation number that rejects late work from a previous descriptor.

On EOF, detach, watchdog, parse failure, or I/O error: stop MediaCodec, release VirtualDisplay, close PFD, clear the accumulator and state machine, and wait for a new accessory object. Reopening a stale PFD is prohibited.
