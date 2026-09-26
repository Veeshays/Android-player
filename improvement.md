# PupPlay — Remaining limitations & follow-ups

Living list. Updated 2026-09-25. Device-verified items move out; new findings move in.

## Engine verdict (proven on JVM via Robolectric, PlaybackEngineTest 5/5)

- `PlaybackService` lifecycle runs: session + ExoPlayer constructed, session
  wraps the SAME player instance the UI would drive.
- Real ExoPlayer `prepare()` resolves a local file to READY with true
  duration (~5000ms for the fixture); `play()` advances position; no errors.
- Therefore the engine, MediaSource resolution, and state machine WORK. The
  on-device static player is NOT an engine defect — remaining suspects are, in
  order: (1) controller↔session binding on the device (stock Media3 binder hop;
  could not bind under Robolectric's manifest parsing), (2) content-URI
  readability on that device, (3) audio focus/output routing on that device.

## Media-model verdict (proven via MediaStoreForensicTest 12/12)

- Cursor → Track → MediaItem preserves full content-URI identity everywhere:
  `mediaId` = content URI, `localConfiguration.uri` = same playable URI,
  metadata intact. `requestMetadata.mediaUri` is legitimately null for
  directly-built items (records redirect origins, unused for local files).
- Persistence round-trips full URIs (queue/history/positions/ratings/favs).
- The "8890" display came from URI-tail fallbacks rendering numeric MediaStore
  ID tails as titles (hero after restart with empty controller; queue rows with
  blank metadata). Fixed: both now show honest empty/unknown states, and a
  duplicate hero artwork layer was removed.
- Numerically-named files ("8890.mp3") keep filename identity consistently.

## Decisive on-device protocol (do this first on hardware)

1. Install debug APK, Fetch, open Settings → Playback diagnostics.
2. Tap **Play test tone** (bundled 5s 440Hz WAV through the real pipeline):
   - Tone audible ⇒ engine/connection fine → tap a library song; if silent,
     run **Probe current file** (URI readability) and copy the report.
   - Tone silent ⇒ engine/connection broken → copy the report (state, timeline,
     volume, last error) and fix that layer.
3. Report back: tone result + probe result + copied diagnostics.

## Needs a real Android device (no device attached to this machine)

- **Audible playback proof**: tap → sound through speaker/headphones, minimize/lock
  continuation, notification + lock-screen controls, audio-focus/ducking, BT.
  The path reads correct and failures now surface via Snackbar + diagnostics,
  but nothing here substitutes for ears-on testing.
- **Video rendering**: PlayerView surface attach, fullscreen dialog, rotation,
  aspect preservation on real hardware + GPU behavior.
- **Artwork rendering**: in-house thumbnail loader (replaced Coil) needs eyeballing
  across audio album art, video frames, and missing-art fallback.
- **Lyrics end-to-end**: LRCLIB lookup against real metadata + airplane-mode path.
- **Sun/glow perf**: frame pacing on low-end devices; Efficient-mode feel.
- **Library scale**: 1k–10k MediaStore rows, scroll smoothness, Fetch progress feel.

## Known architecture notes (not bugs, do not "fix" blindly)

- `stickyHeader` does not resolve against this project's Compose-foundation
  classpath (duplicate foundation artifacts: 1.7.0 + 1.7.6 + JetBrains remnants
  seen during audit). Group headers are plain items. Revisit only with a
  dependency-alignment pass (BOM-first, one foundation).
- Coil was removed in favor of `library/Artwork.kt` (system thumbnails +
  sampled decode, 32-entry LRU). Smaller APK, zero multiplatform deps — but the
  loader is custom code: watch for exotic URIs (SD card, work profile) on device.
- `PlaybackService` persists positions/history on pause/track-change via
  debounced DataStore writes; queue snapshot mirrors the controller playlist.
  Flow-∞ (auto-continue with similar tracks) is a persisted *flag* only — the
  PC's similar-track continuation is not implemented on Android yet.
- Sleep timer persists `sleepMins` but has no active countdown engine on Android.
- PiP button is a placeholder (no `enterPictureInPictureMode` wiring).
- `VideoScreen` speed button changes rate but does not persist it per video.
- Min SDK 26: video thumbnails below API 29 fall back to the monogram
  (`loadThumbnail` is 29+); audio art still decodes via stream on all levels.
- Debug builds use `applicationIdSuffix .debug`; release is unsigned without a
  keystore (`assembleRelease` output is unsigned — never distribute it).

## Sensible follow-ups (small, in priority order)

1. Device pass over TEST_PLAN.md + this file's device list; fix what the
   Snackbar/diagnostics name.
2. Dependency alignment (single foundation) → re-enable sticky headers.
3. Flow-∞ continuation + sleep countdown engine (both have persisted state).
4. PiP wiring behind the existing button.
5. Recently-watched rail for videos (positions map already has the data).
6. Full 35-curve EQ table UI (8 canonical curves ship now).
7. Release signing (keystore + `signingConfigs`) → signed AAB for distribution.
8. Embedded subtitle/audio-track picking for video (platform-limited; research).
