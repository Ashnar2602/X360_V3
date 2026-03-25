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

Status: implemented, progression fix still incomplete

What exists:

- official `xenia-canary/game-patches` snapshot bundled into runtime
- patch DB metadata exposed in app/runtime diagnostics
- title content UI and store
- helper pipeline for XContent container inspection/install

### Phase 10C: Progression-stall triage

Status: implemented, investigation ongoing

What exists:

- live player session is the canonical diagnostic path
- session-scoped diagnostics bundle keyed by `sessionId`
- progression-stall classification model
- storage/content/profile preflight diagnostics
- guest-side instrumentation for content/XAM/XLive/XNet paths

## Current blocker

The current blocker is not "make frames appear" and not "make input reach Xenia".

The blocker is:

- titles can boot, render, and accept input
- but some titles still stall during gameplay/load transitions

Important current note:

- the old polling regression where only the first frame was exported has been fixed in the active runtime payload
- this closed a real display-side freeze class on both validated devices
- remaining stalls must now be treated as real progression problems unless logs prove otherwise

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

### Rendering and launch checks

- Vulkan probe still passes
- Turnip hardware path still passes
- Xenia reaches `VULKAN_INITIALIZED`
- imported ISO launches still work
- player reaches `FRAME_STREAM_ACTIVE`
- controller events still reach the player session
- patch DB loads for more than zero titles

## Near-term priorities

1. Preserve the current live player baseline on both devices.
2. Keep the official patch DB and title-content subsystem working.
3. Use the live player diagnostics bundle, not one-shot smoke, to classify stalls.
4. Fix progression blockers without rewriting the rendering stack again.
5. Only after progression is trustworthy, return to audio and performance work.
