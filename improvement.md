# PupPlay — Remaining limitations & follow-ups

Living list. Updated 2026-10-11 (critical playback-failure investigation). Device-verified items move out; new findings move in.

## Investigation 2026-10-11 — device report shows stale install, NOT current code

Device report: `engine=ok state=buffering playWhenReady=true playing=false
media=pupplay://probe-tone pos=0 dur=0 error=null transitions=[]`.

**Root cause: the device runs a pre-rebuild APK.** Proof: a full-source
search shows the string `pupplay://probe-tone` exists NOWHERE in current
`app/src/main` — not as a mediaId, URI, or constant (only in comments
and in tests asserting it never resolves). The current build physically
cannot produce `media=pupplay://probe-tone`. Consequences:

- The "tone rings but songs silent at 00:00" symptom was observed on the
  OLD engine (tone-through-controller + session tone fallback), where a
  tap could leave the stale probe item active.
- In the current build there is no probe item to get stuck: the tone
  never touches ExoPlayer, and every tap calls `setMediaItems`, which
  REPLACES the whole timeline (verified in code + regression test).
- `transitions=[]` is consistent with a stale/restarted process, not
  with current code (which records TAP/HANDOFF/STATE/ERROR lines).

Before retesting on hardware: **uninstall ALL old builds**
(`com.veeshays.pulse` release AND `com.veeshays.pulse.debug` — they are
separate app icons and the report could come from either), then install
the debug APK below and re-run the protocol. If the report reappears on
the new build, the PupPlayTrace logcat lines (next section) will name
the exact file + exception.

## Investigation 2026-10-11 — temporary structured diagnostics (logcat tag PupPlayTrace)

Added for this debugging session; remove after the real-song pass:
- `Screens.playTracks`: `TAP title=… uri=… mime=… durMs=… albumId=…
  readable=… bytes=… err=… queueSize=… startIdx=…` (proves the exact
  scanned track, its readability, and the handoff index) + `HANDOFF
  items=… idx=… mediaId=… uri=…` after `setMediaItems` (proves
  replacement). Handoff failures log with exception + report it.
- `PlaybackService`: `STATE … kind=track|file|resource|none … pos=…
  dur=… buffered=…` on every state change (distinguishes real-track
  vs resource vs unknown-scheme playback — probe vs song is now
  structurally visible); `TRANSITION …` with title; `ADD passthrough /
  resolve / reject` in the session guard; `ERROR code=…(name) uri=…
  chain=[… <- …]` with the FULL nested cause chain in `onPlayerError`.
- Readability gate extracted to `probeReadable(resolver, key)` in
  `QueueMedia.kt` (returns mime + bytes read + named error, never throws)
  so the exact tap gate is unit-testable. No personal metadata is logged
  beyond track identity needed to pin a failing file.
- Media3 API verified by inspection: `MediaMetadata.Builder.setDurationMs
  (java.lang.Long)` is boxed/nullable — unknown scanner durations stay
  null (never fake 0:00); the extractor fills the real timeline duration.

## Investigation 2026-10-11 — regression tests + evidence

- New `SongTapRegressionTest` (5/5, Robolectric, no mocks): real WAV
  fixture through the real `probeReadable` gate (readable, bytes>0);
  bogus content URI fails with a named error; tapped tracks build a
  playlist of ONLY playable URIs with identity preserved and correct
  start index; `pupplay://probe-tone` can never resolve; durations
  propagate scanner → MediaItem (240000 → metadata; 0 → null).
- Full suite: `:app:testDebugUnitTest --offline` BUILD SUCCESSFUL,
  **92 tests, 0 failures, 0 errors** (was 87; +5 new). Evidence:
  `app/build/test-results/testDebugUnitTest/*.xml`
  (`SongTapRegressionTest` timestamp 2026-10-11T15:34:05Z).
- `:app:assembleDebug --offline` BUILD SUCCESSFUL;
  `app-debug.apk` (22,097,401 B, 2026-10-11 21:05 local).
- Install: **BLOCKED** — `adb install -r app-debug.apk` →
  `adb.exe: no devices/emulators found`; `adb devices -l` empty
  (evidence captured 2026-10-11 ~21:05 local). No logcat exists because
  no device is attached. Audible playback, moving timer, pause/seek/
  next/prev on hardware: NOT VERIFIED. Gate still open; no redesign.

## To continue on a connected device (exact commands)

1. `D:\Android\Sdk\platform-tools\adb.exe devices -l` (expect one `device`)
2. Uninstall stale builds:
   `adb uninstall com.veeshays.pulse` and
   `adb uninstall com.veeshays.pulse.debug`
3. `adb install -r D:\OPENCODEE\07.PulseAndroid\app\build\outputs\apk\debug\app-debug.apk`
4. Grant audio access on first Fetch (Android 13+: READ_MEDIA_AUDIO).
5. In a second terminal, start trace capture BEFORE tapping:
   `adb logcat -c; adb logcat -s PupPlayTrace:I '*:S'`
6. Fetch → tap a real song → expect `TAP … readable=true`,
   `HANDOFF …`, `ADD passthrough … kind=track`,
   `STATE READY … playing=true pos=… dur=…`, audible sound + moving timer.
7. If silent: the `ERROR … uri=… chain=[…]` line + `TAP` line name the
   file and cause — paste them into a bug report with the copied
   in-app diagnostics (Settings → Playback diagnostics → Copy report).

## Rebuild 2026-10-11 — root cause of the old playback failures

1. **`pupplay://probe-tone` had no DataSource.** ExoPlayer's
   `DefaultDataSource` cannot open custom schemes, so any item whose
   *playable* URI was `pupplay://…` stalled in BUFFERING forever (the
   "remains buffering" symptom). A second variant parsed the mediaId LABEL
   as a URI (`FileNotFoundException` in diagnostics): a `mediaId` is an
   opaque identity string, only `localConfiguration.uri` plays.
2. **The tone was load-bearing for proving playback.** Diagnostics routed
   the self-test through controller → session → ExoPlayer, so a broken
   tone path masked (and was blamed for) real MediaStore-URI failures.
3. **Album art used the wrong id.** `artworkUri()` fed the audio *track*
   id into `MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI` (which expects
   the *album* id) — wrong art or a dead URI on notification/lock-screen.
4. **Errors swallowed the evidence.** `onPlayerError` reported a generic
   string with no exception class/code and no failing URI, so neither the
   Snackbar nor the diagnostics could name the broken file.
5. Single-instance was already correct (one `ExoPlayer.Builder`, one
   `MediaController.Builder`, session wraps the same player — proven by
   `session_wrapsSamePlayerInstance`); the rebuild keeps and hardens it
   with an explicit recreate-guard in `onCreate`.

## Rebuild 2026-10-11 — architecture changes

- `playback/QueueMedia.kt`: `resolveIdentityToUri(mediaId)` takes ONE arg.
  Only `content://`, `file://`, `android.resource://` pass through.
  `TONE_MEDIA_ID` / `toneResourceUri` / the tone branch are DELETED — any
  `pupplay://` identity now resolves to null (reported, never guessed).
- `playback/TonePlayer.kt` (new): diagnostic tone via `AudioTrack` PCM
  sine (440Hz, 1500ms), fully outside ExoPlayer/session/controller. Proves
  device output only; can neither pass nor fail library playback.
- `playback/PlaybackService.kt`: removed `ACTION_DIAG_PLAY` +
  `onStartCommand` direct-play hack (service has exactly one entry: the
  session); `onCreate` releases stale player/session on recreate;
  `onPlayerError` now reports
  `class + code + message + failing URI` and records an `ERROR …` transition;
  session `onAddMediaItems` rejects unresolvable identities loudly with the
  requested id (no tone fallback).
- `playback/Diagnostics.kt`: "Play test tone" uses `TonePlayer`
  (no controller needed); "Direct play" button removed; probe output now
  carries the full URI + exception message.
- `models/Media.kt` + `library/MediaLibrary.kt`: `Track.albumId` added
  (default −1, serialization-compatible); audio query requests
  `ALBUM_ID` (video volume excluded — unknown columns fail on-device);
  `artworkUri(key, albumId, video)` keys off the album id, null when
  unknown; `artworkUri(track)` overload for queue assembly. `ColumnIdx`
  tolerates cursors without `ALBUM_ID` (fixtures, video).
- Tests: `ResolverTest` (7) asserts `pupplay://probe-tone` NEVER resolves;
  `PlaybackEngineTest` engine proof uses neutral label `test:local-file`;
  new `TonePlayerTest` (5): PCM length/range/periodicity + artwork
  album-id/unknown/video rules. No mocks on media-discovery/playback paths
  (real Robolectric service + ExoPlayer, real `mapRows` over MatrixCursor).

## Rebuild 2026-10-11 — commands executed

- `adb devices` → `List of devices attached` (EMPTY — no device).
- `:app:testDebugUnitTest --offline` → BUILD SUCCESSFUL, **87 tests,
  0 failures, 0 errors** (incl. PlaybackEngineTest 6/6, ResolverTest 7/7,
  MediaStoreForensicTest 12/12, TonePlayerTest 5/5). Evidence:
  `app/build/test-results/testDebugUnitTest/*.xml`.
- `:app:assembleDebug --offline` → BUILD SUCCESSFUL in 3m48s.
  `app/build/outputs/apk/debug/app-debug.apk` (22,096,675 bytes,
  2026-10-11 20:05 local). `aapt dump badging`: package
  `com.veeshays.pulse.debug`, minSdk 26, targetSdk 34, permissions
  READ_MEDIA_AUDIO / READ_MEDIA_VIDEO / READ_EXTERNAL_STORAGE(max32) as
  declared. (`scripts/verify-apk.ps1` itself crashes on this machine —
  it mis-parses the escaped `sdk.dir` in `local.properties`; aapt used
  directly instead.)
- Install + logcat: NOT executed — no authorized device attached
  (`install-debug.ps1` fails closed on empty `adb devices`, correctly).

## Test results (evidence-backed)

- Build: PASS (assembleDebug, see APK above).
- Automated tests: PASS 87/87 (see XML evidence above).
- Audible device playback: NOT VERIFIED — no device was attached. The
  completion gate is therefore NOT passed: no redesign, no claims about
  speaker output, position advance on hardware, background/lock-screen,
  notification controls, headset handling, or force-stop relaunch.

## Needs a real Android device (no device attached to this machine)

- Install the 2026-10-11 debug APK, Fetch, play multiple local files:
  audible sound, advancing position, pause/resume, seek, prev/next,
  queue + repeat/shuffle, then background/lock/notification/reconnect,
  force-stop + relaunch. Capture `adb logcat` for every failure; the new
  `ERROR …` transitions name the exact exception + URI.
- Independently: Settings → Play test tone (AudioTrack path) for output
  sanity — it proves NOTHING about library playback by design.
- Unchanged carry-overs: lyrics E2E, sun/glow perf on low-end, 1k–10k
  library scale, BT/focus/ducking ears-on.

## Engine verdict (proven on JVM via Robolectric, PlaybackEngineTest 6/6)

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
- Playback stack pinned to the canonical model: explicit
  DefaultMediaSourceFactory (progressive fallback), session-level
  onAddMediaItems guard rejecting sourceless items loudly, and an
  openFileDescriptor readability pre-check on every tap (unreadable files
  report instead of stalling in BUFFERING).
- PupPlay is music-only: video tab/screen removed (4 sections:
  Player · Library · EQ · Settings); engine keeps video capability.
- Runtime forensics (Sept 29): phone showed BUFFERING + empty transitions,
  so every layer is now instance-tagged (service/player/session/controller
  IDs, lifecycle create/destroy events, requested-vs-resolved URIs,
  playWhenReady). Taps pre-verify readability via openFileDescriptor.
  (Superseded Oct 11: the tone→file fallback, cache-file tone, and
  direct-service diag play were REMOVED — see rebuild section above.)
- Tone post-mortem (Oct 4, superseded Oct 11): the cache-file tone could go
  stale between copy and play, and the probe parsed the mediaId LABEL as a
  URI (guaranteed FileNotFound). Superseded: the tone no longer exists in
  the playback path at all (independent AudioTrack in TonePlayer.kt);
  probes always target the playable URI, never the label. Fetch notices
  carry full scan reports (rows/mapped/skipped/truncated/added/removed/
  visible).

## Decisive on-device protocol (do this first on hardware)

1. Install debug APK, Fetch, open Settings → Playback diagnostics.
2. Tap a library song FIRST (the real gate — no tone proxy):
   - Song audible ⇒ engine/connection/URIs fine → exercise queue,
     background/lock/notification, force-stop + relaunch.
   - Song silent ⇒ run **Probe current file** (URI readability) and copy
     the report — the `ERROR …` transition names the exact exception + URI.
3. Separately, **Play test tone** (AudioTrack, outside the engine) checks
   device output only. Report back: song result + probe result + tone
   result + copied diagnostics.

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
