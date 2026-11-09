# ROM Bench

A hardware test bench for custom ROM bring-up. Flash a new build, open ROM Bench, tap **Run automatic
tests**, walk through the guided tests that need a human (sound, touch, keys…), and export a report you can
attach to a bug or an XDA post — then compare it with the report of the previous build.

ROM Bench is made for the ROMs that ports produce: userdebug builds, often SELinux permissive, usually without
Google Play services, sometimes without half the HALs. It never needs network access or Play services, never
changes the ROM, and every check says *why* it failed and where to look.

- Android 11 to Android 17 (minSdk 30, targetSdk 36)
- Kotlin, Jetpack Compose, Material 3 (light and dark theme, system colours on Android 12+)
- English interface with Italian translation; reports in English by default
- GPL-3.0-or-later

## Areas

| Area | Automatic checks | Interactive / guided |
|---|---|---|
| Wi-Fi | adapter, capabilities, connection (band, channel, link speed, standard), factory vs random MAC, scan by band, gateway ping, internet probe, DNS | live RSSI chart, scan list, download speed test |
| Bluetooth | adapter, address, LE features, bonded devices, BLE and classic scans | enable, live device list, pairing check |
| Telephony / RIL | SIM and subscriptions, voice/data registration (per domain and transport), RAT, signal with plausibility, cell info, IMS/VoLTE hints (carrier config, phh IMS, dumpsys when allowed), mobile data probe on the cellular network even with Wi-Fi on | live signal chart, guided outgoing/incoming call with call state and RAT (VoLTE vs CS), SMS loopback with sent/delivered/received |
| NFC | features, adapter state, HAL properties | tag reader (UID, techs, NDEF) |
| GPS / GNSS | provider, hardware model, capabilities, 30 s listen: satellites per constellation, C/N0, TTFF, NMEA; flags satellites reported with C/N0 = 0 | sky plot, C/N0 bars, live fix, NMEA tail, aiding-data commands |
| Audio output | output devices, audio properties, mixer/policy files, speaker and earpiece playback actually consumed by the HAL and routed where requested | test tones per output, left/right, volume, live routing, headset jack detection |
| Microphones | input devices and microphone info, each source/device records 1 s: digital silence, level, DC offset, clipping, stereo | live level meter, record and play back per source |
| Cameras | Camera2 characteristics, each camera opened in turn: time to first frame, fps, black/flat or green frames, torch | preview, JPEG capture, flash modes, torch |
| Display & touch | resolution/density/size plausibility, measured vsync vs declared refresh, touchscreen device and axis range, backlight node | multitouch trails, dead-zone grid, colour screens, brightness vs backlight node, display modes |
| Keys, vibration, LED | input devices, declared keys, vibrator capabilities, LED nodes | key tester (incl. power and two-stage camera key), vibration patterns and effects, notification LED |
| Sensors | full list, 2 s listen per sensor: rate, timestamps, stuck values, physical plausibility | live charts per sensor, proximity/light/gyro/flip guided tests, compass |
| Battery & charging | presence, voltage/temperature/current with unit and sign heuristics, charger state, capacity vs design, power_supply nodes | live current/voltage charts, charging test |
| USB & OTG | host feature, port state and functions, attached devices, Type-C role, removable volumes | live attach/detach, OTG checks |
| Suspend & wakelocks | deep sleep since boot, /sys/power, wakeup sources, doze state, exact alarm permission | guided suspend test with an exact alarm from deep sleep, wakelock demo |
| CPU, GPU & thermal | cores online at idle and under load, per-cluster limits/governor/frequency under load, thermal zones, thermal HAL, GL renderer and Vulkan | live per-core frequencies, temperatures, stress test with throttling detection |
| Memory & storage | RAM, zram, lmkd configuration sanity, /data, eMMC/UFS info and wear, quick I/O speed, volumes | live RAM, storage, benchmark, mounts |
| Device summary | build, SELinux, patch age, kernel vs Android minimum, partition fingerprints, verified boot, Play services | build/kernel tables, property browser |

Results are **OK / Warning / Error / Info / Skipped**, each with a short summary, the reason, and technical
details. Guided answers ("Works" / "Problem" with an optional note / "N/A") are stored as results too.

## Reports

- Formats: plain text, Markdown, BBCode for XDA, JSON (`rombench-report/1`). Share, save to a file or copy.
- Identifiers (MAC, IMEI, ICCID, serial, SSID/BSSID, phone numbers, cell IDs, tag UIDs) are redacted by
  default. The full `getprop` dump can be appended (identifying properties removed when redacting).
- Every automatic run is saved; **Saved reports** lists them and compares any two: regressions, fixes and
  changed results, plus the build/kernel properties that differ.

## Automation from a PC

```bash
tools/autotest.sh -i app/build/outputs/apk/release/app-release.apk
```

installs the APK with its runtime permissions (`adb install -g`), grants `DUMP` and `READ_LOGS` (optional
extras for dumpsys/logcat based details), runs every automatic check and pulls the report into `reports/`.
Without the script:

```bash
adb shell am start -n org.rombench/org.rombench.MainActivity --ez autorun true
adb pull /sdcard/Android/data/org.rombench/files/reports/latest.json
```

`--es areas wifi,telephony` limits the run, `--es open area/camera` opens a screen. The debug build's package
is `org.rombench.debug`. Keep the screen on and unlocked: display, camera and audio checks need it.

## Building

Requirements: JDK 17, Android SDK with platform 37.2 (`compileSdk`), build-tools 37.

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest
```

The release APK is minified and signed with the key described in `keystore.properties` (`storeFile`,
`storePassword`, `keyAlias`, `keyPassword`; not in git). Without that file it is signed with the local debug
key, so it installs right away.

## Safety notes for ROM developers

- debugfs (`/sys/kernel/debug`, `/d`) is never read: on some kernels reading a debugfs node panics the phone.
- Nothing is written outside the app's own storage; brightness, audio mode, display mode, torch and key
  capture are restored when you leave a screen.
- Network tests are optional and use the hosts set in Settings (defaults: `1.1.1.1`,
  `connectivitycheck.gstatic.com`, `speed.cloudflare.com`).

## Credits

- Built with [Jetpack Compose](https://developer.android.com/jetpack/compose), Material 3, AndroidX Core,
  Activity and Lifecycle, and Kotlin coroutines — Apache License 2.0, by the Android Open Source Project,
  Google and JetBrains.
- Icons: Material Symbols / Material Icons (`material-icons-extended`), Apache License 2.0, Google.
- The checks encode lessons from the Lumia 950 XL, 1520 and 930 LineageOS ports and their community:
  Android4Lumia950 (djokcho, EpicLPer, j0sh1x and others), LineageOS and LineageOS-UL, and phh's IMS work used
  for VoLTE on legacy devices.

## License

Copyright (C) 2026 Lorenzo Giannoccaro. ROM Bench is free software: you can redistribute it and/or modify it
under the terms of the GNU General Public License as published by the Free Software Foundation, either
version 3 of the License, or (at your option) any later version. See [LICENSE](LICENSE).
