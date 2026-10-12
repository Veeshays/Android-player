# PupPlay Android — Fresh-PC Setup

Continue development of this repo on any Windows PC with these steps.
Nothing machine-specific is committed (`local.properties`, `build/`,
`.gradle/` are git-ignored); each machine provides its own SDK path.

## 1. Prereqs

- **JDK 17** (e.g. Eclipse Temurin 17). `java -version` must say 17.
- **Android SDK** with these packages (via Android Studio SDK Manager or
  `cmdline-tools;latest/bin/sdkmanager`):
  - `platform-tools`
  - `platforms;android-34`
  - `build-tools;34.0.0`
- ~8 GB free disk (Gradle caches + APK outputs).
- No system Gradle needed — the repo ships the **Gradle wrapper**
  (`gradlew` / `gradlew.bat`, pinned to Gradle 8.9). First run downloads
  the distribution (~130 MB, needs network once).

## 2. Clone + point at your SDK

```bat
git clone https://github.com/Veeshays/Android-player.git
cd Android-player
```

Tell Gradle where YOUR SDK lives (one line, never committed):

```bat
echo sdk.dir=C:\\Users\\YOU\\AppData\\Local\\Android\\Sdk > local.properties
```

(use double backslashes; or set the `ANDROID_HOME` env var instead).

## 3. Build / test / install (from repo root)

```bat
gradlew.bat :app:testDebugUnitTest --offline     ^/rem 98 unit tests, ~8 min first run
gradlew.bat :app:assembleDebug --offline         ^/rem debug APK (package com.veeshays.pulse.debug)
gradlew.bat :app:assembleRelease --offline       ^rem UNSIGNED release APK (sideload only, no keystore yet)
```

Outputs: `app\build\outputs\apk\debug\app-debug.apk`,
`app\build\outputs\apk\release\app-release-unsigned.apk`.
Test evidence: `app\build\test-results\testDebugUnitTest\*.xml`.

Drop `--offline` on first run so dependencies can download.

## 4. Device testing (the completion gate)

1. Enable USB debugging on the phone, connect, accept the RSA prompt:
   `%ANDROID_HOME%\platform-tools\adb.exe devices -l` (expect one `device`).
2. Uninstall ALL stale builds (release + debug are separate icons):
   `adb uninstall com.veeshays.pulse` and `adb uninstall com.veeshays.pulse.debug`.
3. `adb install -r app\build\outputs\apk\debug\app-debug.apk`
4. Open the app → Fetch → grant **Music/audio** access (READ_MEDIA_AUDIO).
5. Capture the trace BEFORE tapping (second terminal):
   `adb logcat -c & adb logcat -s PupPlayTrace:I *:S`
6. Tap a real song → expect `TAP … readable=true`, `HANDOFF …`,
   `ADD passthrough … kind=track`, `TIMELINE …`,
   `STATE READY … playing=true`, audible sound + moving timer.

## 5. Release to GitHub (`vX.Y.Z` pattern, `gh` required)

```bat
gradlew.bat :app:assembleRelease --offline
gh release create vX.Y.Z app\build\outputs\apk\release\app-release-unsigned.apk ^
  --repo Veeshays/Android-player --title "..." --notes "..."
```

Release APKs are **unsigned** (no keystore) — fine for sideload testing,
never for store distribution. Release signing is an open follow-up
(see `improvement.md`).

## 6. Notes / traps

- Emulator needs hardware acceleration (HAXM/WHPX, elevation to enable).
  Without it `x86_64` images refuse to boot — use a real device.
- `scripts\install-debug.ps1` / `uninstall-debug.ps1` wrap install steps
  but need `ANDROID_HOME` set; raw `adb` commands above always work.
- In-app forensics live in Settings → Playback diagnostics
  (Copy report button). `improvement.md` is the living status log —
  update it with every device result, pass or fail.
