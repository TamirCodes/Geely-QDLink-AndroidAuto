# Geely QDLink AndroidAuto research

Research and lab scaffolding for a stock-Android QDLink projection client targeting:

```text
Geely Geometry C 480 2024 / FES5PL
GE13
SWGE13A0623H8BEH.00019
HWGE131128M0191
HU716P.00-BEH
```

Current status: **Phase 0.5 lab validation complete; ready for the non-invasive vehicle probe. No polished APK and no vehicle modification.**

## Modules

- `qdlink-core` — pure Kotlin V1/V2/A6A6 codecs, CRC, bounded accumulator, state machine, and transports.
- `tools/ge13-simulator` — desktop V1/V2 HU simulator.
- `lab-app` — minimal Android AOA, VirtualDisplay, MediaCodec, touch, and diagnostics probe.
- `docs` — source audit, protocol map, feasibility gates, test plans, and lab results.

## Build and test

```powershell
.\gradlew.bat :qdlink-core:test :tools:ge13-simulator:installDist :lab-app:assembleDebug
```

See [docs/LAB_APP_AND_SIMULATOR.md](docs/LAB_APP_AND_SIMULATOR.md) for lab commands, [docs/13_LAB_VALIDATION_RESULTS.md](docs/13_LAB_VALIDATION_RESULTS.md) for verified results, and [docs/14_TARGET_PROBE_INSTRUCTIONS.md](docs/14_TARGET_PROBE_INSTRUCTIONS.md) for the first parked vehicle test.

The probe's default vehicle mode is listen-only Stage C. It also provides foreground accessory enumeration and local Copy/Export Diagnostics; there is no automatic upload.

## Safety boundary

This project is limited to Android USB accessory/QDLink infotainment projection. It must not alter vehicle firmware, CAN/ECU state, MCU software, head-unit partitions, or Android USB gadget configuration. Test only while parked.
