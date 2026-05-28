# Locus Brose Adapter

A [Locus Maps](https://www.locusmap.eu/) sensor adapter for **Brose e-bike drive units**.

Uses the Locus Maps Sensor Adapter API 0.10.0 — Locus handles the BLE connection directly, this adapter only parses the raw motor data.

## What it does

Connects Locus Maps to your Brose motor via BLE and provides:

| Sensor | Source |
|--------|--------|
| Speed (km/h) | TEL frame bytes 2–3 |
| Cadence (RPM) | TEL frame byte 4 |
| Battery (%) | TEL byte 12 / SEC protobuf |
| Assist mode | TEL byte 11 |
| Motor power (W) | SEC protobuf |
| Estimated range (km) | SEC protobuf |

## Requirements

- Android 8.0+ (API 26)
- Locus Maps Beta with Sensor Adapter API 0.10.0+
- Brose drive unit (pre-2022, open BLE protocol)

## Setup

1. Install this app on the **same device** as Locus Maps
2. Open Locus Maps → Sensors → Add sensor → Brose Motor
3. Select your motor from the scan list
4. Done — Locus connects directly to your Brose motor

## Brose BLE Protocol

| UUID | Role |
|------|------|
| `31be2300-d927-11e9-8a34-2a2ae2dbcce4` | Service |
| `31be23a6-d927-11e9-8a34-2a2ae2dbcce4` | TEL — Telemetry NOTIFY |
| `31be32ba-d927-11e9-8a34-2a2ae2dbcce4` | SEC — Protobuf NOTIFY |
| `31be3634-d927-11e9-8a34-2a2ae2dbcce4` | CMD — Command WRITE |

The SEC data stream is triggered by writing `80 06 00 1A 04 08 02 2A 00` to CMD every 2 seconds.

## Build

```bash
./gradlew :app:assembleDebug
```

## License

MIT
