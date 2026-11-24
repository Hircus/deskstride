# PitPat BLE protocol

Notes on the Bluetooth LE protocol spoken by PitPat walking pads (tested on the **PitPat-T01 / BA10-B**).
They come from an HCI snoop log of the official app and from studying how PitPat 4.25 parses packets,
for interoperability. Implemented in [`TreadmillPacket.kt`](../app/src/main/java/io/github/deskstride/TreadmillPacket.kt).

All values are **big-endian**, byte ranges are inclusive.

## GATT layout

| UUID | Role |
|---|---|
| `0xFBA0` | service |
| `0xFBA1` | write: commands and status requests |
| `0xFBA2` | notify: status packets |

Status replies are 60 bytes, so request an MTU of at least 63 (DeskStride asks for 247).

## Status (notifications on 0xFBA2)

`[0]=0x66`: short format (20 bytes, sent unprompted; the full variant carries the device serial number).
`[0]=0x67`: extended format (60 bytes, reply to the poll), with fields 32..56.

| byte | field |
|---|---|
| 2 | error code |
| 3-4 | actual speed (km/h ×1000) |
| 5-6 | target speed |
| 7-10 | distance (u32, m) |
| 11 | current incline |
| 12 | target incline (bits 0-3 command source, bits 6-7 walk/run state) |
| 13 | heart rate |
| 14-17 | steps (u32; always 0 on the T01) |
| 18-19 | kcal (u16) |
| 20-23 | time (ms if firmware version > 19, seconds otherwise) |
| 24 | run id |
| 25 | firmware version |
| 26 | flags: bit0 network, bits1-2 units, **bits3-5 state** (0 = stopped, 8 = running, 16 = paused, 24 = countdown, 32/40 = other), bits5-6 wristband, bit7 miles |
| 27-28 | max speed |
| 29 | max incline |
| 30 | device type (&31) |
| 31 | minor version |
| 0x66 only: 30/32 + 16 | device serial number (ASCII) |
| 0x67 only: 32 | idle (nobody on the belt) |
| 33 | sensor state |
| 34-35 / 36-37 | peak / valley (signed) |
| 38-39 | cumulative steps |
| 40 | instantaneous current |
| 41-42 | motor revolutions |
| 43-44 | steps estimated from motor current (the useful step count on the T01) |
| 45 | remote battery |
| 46 | remote connection |
| 47 | bit0 buzzer, bit1 controller, bits5-6 belt side, bit2 activation |
| 48 / 49 | model / brand |
| 53-56 | torque |
| 58 | checksum: XOR of bytes 1..57 |
| 59 | terminator `0x43` |

The treadmill resets time, distance and kcal every time the belt starts. The step counter at 43-44 is **not**
always reset: right after a start, subtract what's already there.

## Commands (writes to 0xFBA1)

Every frame is `6A <len> ... <xor> 43`, where `<xor>` is the XOR of bytes 1..n-3.

**Poll** (status request, read-only): `6A 05 FD F8 43`. Send it every ~500 ms; the reply is a `67 3C` packet.

**Control**, 23 bytes:

| byte | field |
|---|---|
| 0-1 | `6A 17` |
| 2 | goal type (0 = none; F3 units; F5 reverse/vibration) |
| 3-5 | goal value (u24) |
| 6-7 | speed (km/h ×1000) |
| 8 | acceleration time (PitPat sends 5 to start/stop, 1 to change speed) |
| 9 | incline |
| 10 | user weight in kg (used for kcal) |
| 11 | 0 |
| 12 | action: 4 = start/run, 2 = pause, 0 = stop; bit3 = miles |
| 13-20 | PitPat user id (u64), 0 on stop |
| 21 | XOR of bytes 1..20 |
| 22 | `43` |

- **Stop**: speed 0, action 0.
- **Pause**: target speed, action 2.
- **Change speed**: same as start (action 4) with the new speed; PitPat uses byte 8 = 1 for this.

The treadmill doesn't seem to check the user id; DeskStride sends `pitpat.userId` from `local.properties` (0 if missing).
