# Bring-Up Plan

## Goal

Reconstruct the Android + FEX + Linux `x86_64` + Xenia stack in a controlled order, so each phase proves one layer before the next is trusted.

## Completed and implemented phases

### Phase 1: Architecture contract

Status: complete

Locked outcomes:

- Linux `x86_64` guest flow
- FEX-first Android wrapper design
- headless/offscreen-first recovery strategy

### Phase 2: Runtime skeleton without Xenia

Status: complete

Pass outcome:

- translated Linux `x86_64` guest processes start deterministically through FEX
- logs are split cleanly across app, FEX, and guest layers

### Phase 3: Vulkan userspace path

Status: complete

Pass outcome:

- guest glibc and loader resolution work
- guest Vulkan loader path works
- Vulkan instance creation succeeds
- physical device enumeration succeeds
- `lavapipe` remains available as a regression fallback

### Phase 4: Hardware Turnip path

Status: complete

Pass outcome:

- guest Turnip packaging is reproducible from pinned Mesa source
- `mesa25` and `mesa26` are both staged in runtime
- branch-aware ICD selection works
- hardware Turnip probe passes on:
  - `AYN Odin2 Mini`
  - `Odin3`

### Phase 5: Xenia bring-up

Status: complete

Pass outcome:

- pinned-source Linux `x86_64` Xenia Canary is built in-repo
- Xenia is staged into the guest runtime
- Xenia reaches `VULKAN_INITIALIZED` on both validated devices

### Phase 6: Title-aware boot and steady-state headless run

Status: complete

Pass outcome:

- no-copy ISO launch path is real
- imported titles resolve through `rootfs/mnt/library/<entry-id>.iso`
- `Dante's Inferno` reaches `TITLE_RUNNING_HEADLESS`

### Phase 7: First visible presentation

Status: complete

Pass outcome:

- Xenia exports visible frames
- the Android app presents them fullscreen
- `Dante's Inferno` reaches `FRAME_STREAM_ACTIVE`

### Phase 8: Product shell and fullscreen player

Status: complete

Pass outcome:

- splash -> library home flow
- debug UI preserved behind options
- dedicated `PlayerActivity`
- pause menu on Android back
- quick in-player options

### Phase 9: Universal Android display baseline

Status: implemented, but not the current product default

What exists:

- app-owned shared-memory presentation backend
- Surface/Image-based player plumbing for experimentation

Current practical note:

- the current stable default player backend has been moved back to `FRAMEBUFFER_POLLING`
- shared-memory remains in the repo as an alternate/debug path while progression work continues

### Phase 10A: Universal Android input baseline

Status: implemented

Pass outcome:

- Android controller input is captured in the player
- input is bridged into headless Xenia
- controller navigation works in-app and controller events reach the emulator

### Phase 10B: Global patch DB + title content UI

Status: implemented

What exists:

- official `xenia-canary/game-patches` snapshot bundled into runtime
- patch DB metadata exposed in app/runtime diagnostics
- title content UI and store
- helper pipeline for XContent container inspection/install

### Phase 10C: Progression-stall triage

Status: implemented

What exists:

- live player session is the canonical diagnostic path
- session-scoped diagnostics bundle keyed by `sessionId`
- progression-stall classification model
- storage/content/profile preflight diagnostics
- guest-side instrumentation for content/XAM/XLive/XNet paths

### Phase 10D: Freeze-lab diagnostics

Status: implemented

What exists:

- freeze-cause evidence model distinct from progression buckets
- transport/presenter/screen truth points in the live diagnostics bundle
- debug freeze-lab controls and report capture in the app

### Phase 10E: Per-game DLC policy

Status: implemented

What exists:

- per-title DLC visibility setting in game options
- `X360_MARKETPLACE_CONTENT_POLICY` launch wiring
- safe missing-DLC handling without mandatory ODD marketplace probing in the product path

### Phase 10F: Post-DLC freeze isolation

Status: implemented

What exists:

- better distinction between root-probe noise and real content/file misses
- deeper VFS/content snapshots around the real player session
- cleaner evidence collection for `DLC disabled` comparison runs

### Phase 10G: Movie/audio loading deep-debug

Status: implemented

What exists:

- movie/audio state snapshots in diagnostics
- guest-side tracing for movie-thread churn, audio-client churn, and VFS/media access
- diagnostic launch profiles for audio/XMA variants

### Phase 11A: Desktop-working Canary rebase + upstream-first XAM realignment

Status: implemented

Pass outcome:

- Android guest is rebased to the desktop-working upstream family:
  - `canary_experimental@553aedebb59340d3106cd979ca7d09cc8e3bd98e`
- the Xenia patch queue is now selected by `patchSetId`, not hardwired to `phase4`
- active patch set is `phase11a-upstreamfirst-v1`
- `xam_media`, `xam_net`, and `apps/xlivebase_app` are back on upstream-first semantics by default
- `xam_content` keeps only the minimum Android/product delta for DLC visibility policy and diagnostics
- the generated payload stages `xenia-canary` SHA-256 `6ae5bc7384ca5b59cac2b03035dcf26b9d3f39e5d6efe788c6795859601a5526`

## Current blocker

The current blocker is not "make frames appear" and not "make input reach Xenia".

The blocker is:

- titles can boot, render, and accept input
- but some titles still stall during gameplay/load transitions

Important current notes:

- the old polling regression where only the first frame was exported has been fixed in the active runtime payload
- the old DLC/marketplace-content miss is no longer treated as the primary blocker
- Phase 11A removed old-pin drift and silent local XAM semantic divergence as primary suspects
- `Dante's Inferno` still has an unresolved loading/progression freeze on Android/Linux even after the rebase to the desktop-working upstream family

## Current test matrix

### Devices

- `AYN Odin2 Mini`
- `Odin3`

### Core checks

- cold start
- repeated relaunch
- connected Xenia/Vulkan bring-up
- visible player smoke
- controller-input smoke
- live player diagnostics bundle generation
- freeze-lab evidence capture

### Rendering and launch checks

- Vulkan probe still passes
- Turnip hardware path still passes
- Xenia reaches `VULKAN_INITIALIZED`
- imported ISO launches still work
- player reaches `FRAME_STREAM_ACTIVE`
- controller events still reach the player session
- patch DB loads for more than zero titles
- runtime payload metadata reports the rebased Xenia revision and patch set

## Near-term priorities

1. Preserve the current live player baseline on both devices.
2. Keep the official patch DB and title-content subsystem working.
3. Keep the Xenia baseline aligned with the desktop-working upstream family unless a measured regression forces a divergence.
4. Use live player diagnostics, freeze-lab bundles, and desktop parity traces to classify remaining stalls.
5. Fix progression blockers without rewriting the rendering stack again.
6. Only after progression is trustworthy, return to audio and performance work.
