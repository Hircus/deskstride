<div align="center">

# DeskStride

**Use your PitPat walking pad from your phone and your wrist, without the PitPat app.**

Start, pause and change speed from Android or Wear OS, follow the workout live,
and send every walk to Health Connect (and from there to Samsung Health, Google Fit and others).

![Android](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)
![Wear OS](https://img.shields.io/badge/Wear%20OS-3%2B-4285F4?logo=wearos&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7F52FF?logo=kotlin&logoColor=white)
![Health Connect](https://img.shields.io/badge/Health%20Connect-ready-00D69A)

</div>

---

## Features

### ðŸ“± Phone
- **Live dashboard**: speed with a scrolling chart, time, distance, steps and calories
- **Full control**: start (with confirmation), pause/resume, stop; change speed with âˆ’ / + (hold to repeat) or type an exact value
- **Treadmill scanner**: pick your treadmill once and DeskStride reconnects to it from then on
- **Runs in the background**: a foreground service keeps the connection alive with the screen off; it reconnects automatically if the treadmill drops
- **Crash-safe**: the current session is saved every 30 s and recovered if the app is killed
- **History and reports**: every walk with pace, average/max speed, heart rate and a speed chart
- **Health Connect export**: workout, distance, speed, active calories, steps (optional) and heart rate, without duplicates on re-send
- **Home screen widgets**: Today, Last 7 days, Last activity, and a one-tap "Walk" widget that connects and shows live stats

### âŒš Watch
- **Remote control**: start, pause and stop the belt, and change speed with the rotating bezel
- **Safe start**: tap twice to start, so the belt never starts by accident
- **Heart rate**: measured during the walk and added to the session
- **Tile**: today's km, steps and minutes at a glance, with a "Walk" button

## Compatibility

| | |
|---|---|
| **Treadmill** | PitPat-T01 (BA10-B). Other PitPat models using BLE service `0xFBA0` will probably work, but haven't been tested |
| **Phone** | Android 12+ with Bluetooth LE and [Health Connect](https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata) (built into Android 14+) |
| **Watch** | Optional. Wear OS 3+, paired with the phone |

> [!IMPORTANT]
> The treadmill accepts **one BLE connection at a time**: close the PitPat app before connecting with DeskStride.

## Getting started

### Build

You need **JDK 17** and the Android SDK (Android Studio sets both up for you).

```bash
git clone https://github.com/<you>/deskstride.git
cd deskstride
./gradlew :app:assembleDebug :wear:assembleDebug
```

Or open the folder in Android Studio and run the `app` and `wear` configurations.

Optional: put your PitPat account id in `local.properties` (not committed) as `pitpat.userId=<decimal id>`.
The treadmill doesn't seem to check it, so without it DeskStride sends 0.

### Install

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
adb -s <watch> install wear/build/outputs/apk/debug/wear-debug.apk
```

> [!NOTE]
> The phone and watch apps must have the **same package name and signing key**, otherwise the Wear Data Layer
> won't connect them. Build both on the same machine (same debug keystore) or sign both with your release key.

### First run
1. Open DeskStride and tap **Health Connect permissions**
2. Tap **Find treadmills** and pick yours (or just **Connect to treadmill** to use the first PitPat found)
3. Set your height and weight in the profile: height is used to estimate steps, weight for calories
4. In Samsung Health (or another app), allow it to read exercise data from Health Connect

## How it works

```
 â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”  BLE 0xFBA0   â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”  Data Layer  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
 â”‚   PitPat     â”‚ â—„â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â–º  â”‚   DeskStride phone   â”‚ â—„â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â–º â”‚  DeskStride  â”‚
 â”‚   treadmill  â”‚ poll / notify â”‚   (Walk + service)   â”‚ cmd / state  â”‚    watch     â”‚
 â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜               â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜              â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
                                           â”‚ write
                                           â–¼
                                 â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
                                 â”‚    Health Connect    â”‚ â”€â”€â–º Samsung Health, Fit, â€¦
                                 â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
```

- The phone polls the treadmill every 500 ms (`6A 05 FD F8 43` on `0xFBA1`) and gets 60-byte status packets back
  on `0xFBA2`, with speed, distance, time in ms, steps and belt state.
- `Walk` holds the connection and the session for the whole process; `WalkService` keeps the process alive.
- `SessionTracker` turns packets into sessions: it handles pauses, connecting to a belt that is already moving,
  counter resets and leftover steps.
- The watch is a thin remote: it sends commands (`start`, `pause`, `speed:2.5`, `hr:98`, â€¦) and draws the state the
  phone sends back.

The full byte-level protocol is in [**docs/PROTOCOL.md**](docs/PROTOCOL.md).

## Project layout

```
app/                      phone app (io.github.deskstride)
  Walk.kt                 connection + session + commands, lives in the process
  TreadmillBle.kt         BLE scan, GATT, command queue, auto-reconnect
  TreadmillPacket.kt      packet parser and command builder
  SessionTracker.kt       packets â†’ sessions
  HealthWriter.kt         Health Connect export
  WearBridge.kt           Data Layer bridge to the watch
  widget/                 home screen widgets
wear/                     Wear OS app: remote, heart rate, Tile
docs/                     protocol notes and hardware test checklist
```

## Tests

```bash
./gradlew test
```

Unit tests cover packet parsing (against real captures), command bytes, session tracking and the
phone â†” watch message format. Hardware checks are listed in [docs/TESTING.md](docs/TESTING.md).

## Localization

The UI is in English and Italian (`values-it`). To add a language, translate
`app/src/main/res/values/strings.xml` and `wear/src/main/res/values/strings.xml` into `values-<lang>/`.

## Disclaimer

DeskStride is an independent project, **not affiliated with or endorsed by PitPat**. The protocol was studied
for interoperability only. Use it at your own risk: always stay alert on a moving belt, keep the treadmill's
physical controls within reach, and stop the belt before stepping off.
