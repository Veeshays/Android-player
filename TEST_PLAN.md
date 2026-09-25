# PupPlay Android — Verification Pass (§22)

Unit gate: `./gradlew :app:testDebugUnitTest` (QueueManagerTest + PulseLogicTest).

## Playback
- [ ] Open audio → play → pause → seek → next → previous → queue → playlist
- [ ] Volume keys + mute; metadata (title/artist/artwork/position) correct
- [ ] Corrupt file → skipped with one concise message, no crash; 3 consecutive → stops
- [ ] Unsupported file → counted + skipped, library intact

## Background
- [ ] Play → minimize → switch apps → notification controls work (play/pause/prev/next/seek)
- [ ] Lock → lock-screen controls + artwork correct → unlock → state correct
- [ ] Cold start → playback → minimize → lock → unlock → return to app: same item + position
- [ ] No duplicate/stale notifications; nothing alive when queue empty/stopped

## Interruptions
- [ ] Call → pause → resume only if we were playing
- [ ] Headphone/BT disconnect → pause; other music app → yield, no focus fight
- [ ] Alarm/navigation → duck/pause appropriately

## Video
- [ ] Portrait → landscape → fullscreen → immersive (no bars over controls)
- [ ] Rotation mid-play: position + item preserved; aspect never stretched
- [ ] Seek + pause/resume + speed ladder + subtitle cue + offset + PiP

## Lifecycle / storage
- [ ] Rotate, background/foreground, process recreation: queue/position/screen restored
- [ ] Permission denial → rationale + graceful empty state (no crash)
- [ ] File deleted while playing → pruned + auto-skip + message
- [ ] Renamed/moved file → treated as delete+add (documented PC parity)

## One UI / responsive
- [ ] Small phone, large phone, landscape, cutout, gesture + 3-button nav
- [ ] Edge-to-edge: no clipped/overlapping controls, no horizontal overflow,
      no buttons behind system bars; 48dp touch targets; TalkBack labels sane
- [ ] Dark / light / system; dynamic-color opt-in doesn't break Pulse default

## Performance
- [ ] Cold start < 1.5s to interactive shells on mid-range device
- [ ] 5k-track library: scan non-blocking, scroll smooth, no save jank
- [ ] 30-min playback: no leaks (profiler flat), no wake-lock hold when paused
