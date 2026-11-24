# Hardware test checklist

Unit tests (`./gradlew test`) cover packet parsing, commands and session tracking. These checks need a real
treadmill, phone and watch. Tick `[x]` when a check passes; if it fails, note what happened.

## Setup
- [ ] Phone: tap "Health Connect permissions" and grant everything
- [ ] Watch: add the "DeskStride" Tile

## Phone in the background (foreground service)
- [ ] On connect a "Connected" notification appears; while walking it shows "In progress · time · km" and keeps updating
- [ ] Screen off for 2 minutes mid-walk: when it comes back on, time and steps have kept counting
- [ ] App swiped from recents mid-walk: the notification and the watch keep updating
- [ ] Tapping the notification reopens the app with the chart and data of the current session
- [ ] "Disconnect" removes the notification

## Session recovery
- [ ] Walk for over a minute, then Settings > Apps > DeskStride > Force stop
- [ ] On reopening, the session is in the history (at most 30 s shorter) and is sent to Health Connect

## Reconnection
- [ ] While connected, turn the treadmill off and on again: the app goes back to "Connected" by itself
- [ ] Same as above with the screen off
- [ ] With phone Bluetooth off, "Connect" shows "Bluetooth is off"

## Health Connect
- [ ] After a session, calories show up as exercise calories (not counted twice)
- [ ] Profile: untick "Send steps to Health Connect", do a session: no steps from DeskStride
- [ ] With the watch on: heart rate shows up in the workout

## Watch
- [ ] With the app open and a walk in progress the screen stays on
- [ ] On first launch it asks for the body sensor permission; then "♥ bpm" appears in the stats
- [ ] Heart rate stops while paused and resumes afterwards
- [ ] Phone report: "Avg heart rate" and "Max heart rate" rows
- [ ] Tile: today's km/steps/minutes are right; "● In progress" during a walk; updates when the session ends
- [ ] Tile "Walk" button (phone app open): opens the remote and connects the treadmill
- [ ] The next day the Tile starts again from 0 km
