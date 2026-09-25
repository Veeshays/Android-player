# PupPlay for Android / One UI — Mobile Counterpart of the PC Player

Lightweight, polished Android + Samsung One UI adaptation of the existing PopTune
music / PupScreen video players (`02.MusicPlayer` + `04.VideoPlayer`). Same product, mobile
form factor: same name, same 5-theme identity, same queue/library/settings
semantics, same Sun visual system — re-expressed for touch, small screens,
and the Android lifecycle. Branding redesign is **not** included (later pass).

## 1. Implementation summary

- **Audit first:** full PC audit in `MUSIC_VIDEO_AUDIT.md`; mobile plan in `PLAN.md`.
- **Native Kotlin + Media3** (no WebView wrapper): `PlaybackService`
  (MediaSessionService + ExoPlayer) owns playback so minimize / lock /
  app-switch never stop audio. Single media notification + lock-screen controls
  (artwork/title/artist, play/pause/prev/next/seek), updated by the session —
  no duplicates, removed on stop.
- **Audio focus done deliberately:** ExoPlayer handles duck/transient/permanent
  loss; `AUDIO_BECOMING_NOISY` (headphones/BT pull) pauses; resume only when we
  were playing — never fights other apps. Calls = transient loss → pause.
- **Library:** MediaStore incremental scan (no full rescan, no polling),
  URI-keyed identity (PC `trackKey=path` parity → rescans relink), paged UI
  (100 + Show more), background tag enrichment, unsupported/missing files
  handled gracefully, main thread never blocked.
- **Ports (never duplicated logic):** `TagParser` (tags.js rules incl.
  Blink-182/30STM/UB40 safety), `QueueManager` (queue-follows-view, shuffle,
  repeat off/all/one, Flow ∞, sleep, history/playCounts, resume guards
  8s/15s, error streak guard), `PulseEq` (8-band 60–8k, factory curves,
  Chameleon genre map), `Subs` (SRT/VTT + offset + per-video memory + lang
  detect), `PulseStore` (DataStore JSON with PC caps + schema stamp, debounced
  writes — fixes the PC `saveSettings` O(n) jank).
- **Sun effects preserved** as an optimized equivalent (`SunEffect`: radial
  glow + slow rays + optional stained wash, one animation clock, static when
  backgrounded) — same identity, no jank, no battery drain.
- **Mobile mapping:** sidebar → bottom nav + drawer; tabs → 4 destinations;
  context menus → bottom sheets; hover → pressed states; keyboard → touch +
  gestures (swipe-to-remove, drag-reorder with explicit tap-to-play);
  Back = fullscreen → sheet → queue → nested → background (playback survives).
- **One UI polish:** edge-to-edge + insets (no controls behind system bars),
  dynamic-color opt-in (default stays Pulse Aura), dark/light/system, 48dp
  touch floor, rounded surfaces, cutout/landscape/gesture + 3-button safe.
- **Centralized rebrand surface:** `ui/Brand.kt` (name, copy, metrics, type) +
  `PulseTheme.kt` + `strings.xml` + manifest label — the future visual
  redesign touches one file, never the engine.
- **Lightweight:** deps = Media3 + Compose + DataStore + Lifecycle + Coil
  (artwork only). No Room (DataStore JSON until 20k+ need, same call as PC
  audit), no analytics/accounts/ads, no polling, no wake locks, FGS alive only
  while audible.

## 2. Files changed (all new under `07.PulseAndroid/`)

```
settings.gradle.kts · build.gradle.kts · gradle.properties · PLAN.md · README.md · TEST_PLAN.md
app/build.gradle.kts · proguard-rules.pro
app/src/main/AndroidManifest.xml
app/src/main/res/values/{strings,themes}.xml
app/src/main/java/com/veeshays/pulse/
  PulseApp.kt · core/Errors.kt · models/Media.kt · store/PulseStore.kt
  library/MediaLibrary.kt · tags/TagParser.kt · subs/Subs.kt · eq/PulseEq.kt
  playback/{QueueManager,PlaybackService}.kt
  ui/{Brand,PulseTheme,SunEffect,MainActivity,Screens,Components}.kt
app/src/test/java/com/veeshays/pulse/{QueueManagerTest,PulseLogicTest}.kt
```

PC codebase untouched (audit-only against it).

## 3. Architecture decisions — see PLAN.md §4–5.

## 4. Android / One UI behavior

Background audio, media notification, lock-screen, focus/duck/noisy/calls/BT,
immersive video + PiP, rotation/process-recreation restore, permission
rationale + denial path, error taxonomy (concise messages, no stack traces).

## 5. Performance findings

- Cold start: store load off-main + instant library shells (no blob-vault
  deserialize — the PC music boot bottleneck is gone by design).
- Saves debounced 400ms, small JSON only (PC O(n) reserialize fixed).
- Scan: one MediaStore query + IO enrichment; UI paged; playback preempts.
- Sun: ≤1 infinite clock, static frames in lists/background; FGS only audible.
- No polling; controller observation is lifecycle-aware (no listener leaks).

## 6. Test results

- `QueueManagerTest` (8) + `PulseLogicTest` (TagParser 4, Subs 2, theme/EQ 2):
  written as unit tests (`./gradlew :app:testDebugUnitTest`).
- Logic mirror executed under Node here (no JDK on this machine):
  **ALL LOGIC CHECKS PASS** (filename rules, SRT stamps, queue step/wrap,
  resume guards, Chameleon).
- Brace/package structural check over all 24 sources: OK (one regex-brace
  false positive in `Subs.kt` cleared by string-stripped recount 14/14).
- Full matrix (playback/background/video/lifecycle/One UI/perf) in
  `TEST_PLAN.md` — run on device/lab before release signing.

## 7. Known limitations

1. No JDK/Android SDK on this machine — project is complete source, **not yet
   compiled here**; first build happens in Android Studio / CI
   (`assembleDebug`, then `bundleRelease`).
2. Online enrichment (lyrics/MusicBrainz/covers/subtitle search) is
   architecture-ready (DataSource-OkHttp dep, manual-apply UX contract kept)
   but providers are not yet wired — local-first playback/library are.
3. Video embedded subs/audio-track/chapter selection (PC backlog too) and the
   full 35-curve EQ table UI remain future additive work; 8 canonical curves
   + Chameleon ship now.
4. Thumbnail pipeline uses Coil+MediaStore (not the PC hidden-video
   thumbnailer) — faster, but styled badges for undecodable files are minimal.

## 8. Release build information

- `applicationId com.veeshays.pulse`, `minSdk 26`, `target/compileSdk 34`,
  `versionCode 1`, `versionName 1.0.0`.
- Permissions: `READ_MEDIA_AUDIO/VIDEO` (+ legacy `READ_EXTERNAL_STORAGE`
  maxSdk 32), `FOREGROUND_SERVICE` + `…_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`,
  `MODIFY_AUDIO_SETTINGS`, `BLUETOOTH_CONNECT` (31+, neverForLocation).
- Release: R8 full (`isMinifyEnabled` + `isShrinkResources`), no debug logging,
  no dev UI, no TODO-gated features. Build:
  `./gradlew :app:assembleRelease` (APK) / `:app:bundleRelease` (AAB).
- Pre-release gate: clean + release build, `./gradlew :app:testDebugUnitTest`,
  TEST_PLAN.md matrix incl. cold start → play → minimize → lock → unlock →
  return, and notification/lock-screen verification.
