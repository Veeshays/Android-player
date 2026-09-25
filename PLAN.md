# PupPlay Android — Implementation Plan (from PC audit)

Source of truth: `D:\OPENCODEE\MUSIC_VIDEO_AUDIT.md` + direct reads of
`02.MusicPlayer/{index.html,style.css,app.js,tags.js,eq.js}` and
`04.VideoPlayer/{index.html,app.js}`.

## 1. What the PC player is

- Two Tauri 2 + WebView2 apps, dependency-free classic `<script>` frontend, no framework/bundler.
- Music (`pulse-player` 0.30.0): `<audio>` → MediaElementSource → preamp + 8 biquads → analyser → gain. Offline tag parser (`tags.js`), EQ (`eq.js`), lyrics (lrclib), meta (MusicBrainz+iTunes).
- Video (`pulse-video` 0.10.0): `<video>` direct, thumbnailer (hidden video → 192px JPEG), resume, subs (`subs.js`), folder posters (iTunes).
- Persistence: `localStorage` JSON (`pulse-v1` SCHEMA_V=9, `pulse-video-v1`) + IndexedDB blob vault (music only). Path-keyed identity (`trackKey=path`), queue-follows-view, resume guards `pos>8 && remaining>15`, history/playCounts/ratings/favs/tagOverrides, sleep timer, MediaSession.
- Visual identity: 5 live themes (aura/ocean/emerald/ember/crimson), finishes (neon/matte/artisan/natural/cyber), glass (translucent/frosted/stained…), Sun/light effects + stained-glass assets, lyric lab typography, visualizer (bars/mirror/wave/blocks/line/peak), topbar → drawer → tabbed stage → nowbar/minibar.

## 2. What can be shared / reused (ported, not copy-pasted)

| PC logic | Android home |
|---|---|
| `tags.js` ID3v2/v1, FLAC, MP4 ilst, OGG, `stripLeadingNo`, `Artist - Title` fallback | `tags/TagParser.kt` — pure-Kotlin, never-throws, same rules |
| Queue-follows-view, shuffle/repeat/flow ∞, sleep fade, history/playCounts, corrupt auto-skip + streak guard | `playback/QueueManager.kt` — same semantics, touch-safe |
| Resume guards `>8s && remaining>15s`, positions cap 200 | `PulseStore.kt` + `QueueManager` — identical thresholds |
| Settings caps, schema stamp, `pulse-eq-v1` separation | `store/PulseStore.kt` (DataStore JSON, debounced writes — fixes PC P1 `saveSettings` O(n) jank) |
| EQ 8-band 60–8k, 35 factory curves, Chameleon genre map, click-free bypass | `eq/PulseEq.kt` — same freqs/curves/map, ExoPlayer loudness path |
| Theme/finish/glass constants, 5 palettes | `ui/Brand.kt` + `ui/PulseTheme.kt` — centralized, future rebrand = edit 1 file |
| Sun/light visual identity | `ui/SunEffect.kt` — visually equivalent gradient+ray Canvas, battery-aware (no heavy shaders) |
| Library pipeline: scan → ingest → dedupe by path → cache paint → ghost rescan → sweep | `library/MediaLibrary.kt` over MediaStore (incremental, no full rescan) |
| Subtitle/SRT+VTT parsing, offset, per-video memory | `subs/Subs.kt` — same parser + offset + memory caps |
| Error model: ⚠ badge, auto-skip, per-service offline names, no stack traces | `core/Errors.kt` + UI status line — same contract |

## 3. What is inherently desktop-specific (replaced, not ported)

- Tauri shell (`scan_folder`/`read_file_range`/`api_get`/`convertFileSrc`, dialogs, single-instance, `WEBVIEW2_…` flag) → Android: MediaStore + ContentResolver + Storage Access Framework; network via OkHttp-free HttpURLConnection helper with same allowlist/timeout ethos.
- Hover drawer (10px edge, 180ms close), hover peek-view, wheel-over-volume, kbd shortcuts, context menus, `show in Explorer` → bottom nav + NavigationDrawer, bottom sheets/dialogs, gestures (swipe-to-remove, drag-reorder), touch targets ≥48dp.
- Blob vault (IndexedDB audio bytes, doubles disk) → **deleted by design** on Android; MediaStore URIs are the source of truth.
- Serial `<audio preload:metadata>` probe queue → MediaStore columns + MediaMetadataRetriever on IO dispatcher, concurrency 4, playback preempts background work.
- Window maximize/fullscreen/PiP-button → edge-to-edge, immersive video, system PiP; `pagehide` persist → lifecycle + `onTaskRemoved` + DataStore.
- NSIS/MSI bundle → Gradle `assembleRelease` APK/AAB.

## 4. Mobile architecture (what was built in `07.PulseAndroid`)

```
app/
  Brand.kt            centralized name/package/colors/typo/copy (future rebrand = 1 file)
  PulseTheme.kt       5 PC palettes + finishes + glass → Material3, dark/light/system, One UI spacing
  SunEffect.kt        optimized Sun/glow/stained-lite Canvas (identity preserved, GPU-cheap)
  models/Media.kt     Track/Video/QueueSnapshot/Playlist — path/URI-keyed like PC
  store/PulseStore.kt DataStore JSON: settings/queue/positions/ratings/favs/playlists (caps enforced, debounced)
  library/MediaLibrary.kt MediaStore incremental scan + thumbs + dedupe + sweep + error taxonomy
  tags/TagParser.kt   port of tags.js (never-throws)
  subs/Subs.kt        SRT/VTT parser + offset + per-video memory + lang detect
  eq/PulseEq.kt       8-band curves + Chameleon genre map
  playback/QueueManager.kt queue-follows-view + resume guards + sleep + history
  playback/PlaybackService.kt MediaSessionService + ExoPlayer: background, notification,
                              lock-screen, audio-focus/duck, noisy/call/BT handling, video track
  ui/MainActivity.kt  edge-to-edge, bottom-nav + drawer, back-stack (fullscreen→sheet→queue→tabs→bg),
                      rotation/process-recreation restore
  ui/Screens.kt       Player/Library/Video/Lyrics/EQ/Settings — stacked responsive layouts
  ui/Components.kt    MiniPlayer, QueueSheet, sheets-as-context-menus, touch controls, a11y semantics
```

Playback path: `MediaLibrary (URIs)` → `QueueManager (view query)` → `PlaybackService (ExoPlayer + MediaSession)` → notification/lock-screen/system. UI observes `MediaController`; service survives UI death. Stops cleanly when queue empty/stopped (no leaked service).

## 5. Key decisions

1. **Native Kotlin + Media3, no WebView reuse.** Background playback, MediaSession, audio focus, and MediaStore cannot be done reliably from a wrapped WebView; the PC JS engine is inherently UI-bound (`<audio>` dies with the WebView). Logic is *ported*, UI is *re-expressed* in Compose with the same visual language.
2. **No Room/SQLite, no heavy deps.** DataStore JSON mirrors PC `localStorage` caps; deps = Media3 + Compose + DataStore + Lifecycle + Coil(trim: artwork only). Matches "lightweight" requirement; SQLite only if libraries exceed ~20k (same call as PC audit).
3. **Sun effects preserved as optimized equivalent.** Desktop shader-heavy light/stained looks become a cheap Canvas (radial gradient + rotating rays + optional stained tint, ≤30fps when visible, 0fps when backgrounded). Same identity, no jank, no battery drain. Full sliders arrive later; defaults match PC factory retune.
4. **Do not fight audio focus.** Transient → duck/pause + resume only if we had focus; permanent → pause; noisy (headphones/BT disconnect) → pause; calls → pause + resume only if appropriate. Documented in `PlaybackService`.
