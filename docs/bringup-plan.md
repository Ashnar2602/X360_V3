# Bring-Up Plan

## Goal

Reconstruct the previously working pipeline in a controlled order, so each stage proves one layer of the stack before the next layer is introduced.

## Completed phases

### Phase 1: Re-establish the architecture contract

Status: complete

Locked outcomes:

- Linux `x86_64` guest flow
- FEX-first Android wrapper design
- headless/offscreen-first recovery strategy

### Phase 2: Rebuild the runtime skeleton without Xenia

Status: complete

Pass outcome:

- translated Linux `x86_64` guest processes start deterministically through FEX
- repeated runs stay stable
- logging is split cleanly across app, FEX, and guest layers

### Phase 3: Rebuild the Vulkan userspace path

Status: complete

Pass outcome:

- guest glibc and loader resolution work
- guest Vulkan loader path works
- Vulkan instance creation succeeds
- physical device enumeration succeeds
- `lavapipe` remains available as a regression fallback

### Phase 4: Rebuild the hardware Turnip path

Status: complete

Pass outcome:

- guest Turnip packaging is reproducible from pinned Mesa source
- `mesa25` and `mesa26` are both staged in runtime
- branch-aware ICD selection works
- hardware Turnip probe passes on:
  - `AYN Odin2 Mini`
  - `Odin3`

### Phase 5: Introduce Xenia Canary

Status: complete for bring-up to Vulkan init

Pass outcome:

- pinned-source Linux `x86_64` Xenia Canary is built in-repo
- Xenia is staged into the guest runtime
- headless bring-up through FEX is real
- Xenia reaches `VULKAN_INITIALIZED` on both validated devices without requiring a game image

### Phase 6: Title-aware boot and steady-state headless run

Status: complete

Pass outcome:

- no-copy ISO launch path is real
- imported titles resolve through the guest portal under `rootfs/mnt/library`
- `Dante's Inferno` reaches `TITLE_MODULE_LOADING`
- `Dante's Inferno` survives a fixed observation window without fatal aborts
- final launch stage reaches `TITLE_RUNNING_HEADLESS` on both validated devices

### Phase 7: Recover visible presentation

Status: complete for the framebuffer-polling path

Pass outcome:

- Xenia exports frames through `xenia_fb`
- the Android app consumes the framebuffer stream
- `Dante's Inferno` shows visible frames on:
  - `AYN Odin2 Mini`
  - `Odin3`
- final visible stream stage reaches `FRAME_STREAM_ACTIVE`

### Phase 8: Product shell and fullscreen player

Status: complete

Pass outcome:

- branded splash routes into a library-first shell
- debug tooling survives behind `Options -> Debug`
- titles launch into a dedicated fullscreen `PlayerActivity`
- FPS overlay support exists and is user-configurable
- the visible framebuffer path is usable from the product shell, not only from the debug harness

### Phase 9: Universal Android display baseline

Status: complete

Pass outcome:

- the normal player path uses `FRAMEBUFFER_SHARED_MEMORY`
- visible rendering no longer depends on file polling cadence
- the fullscreen player consumes the app-owned shared-memory transport on:
  - `AYN Odin2 Mini`
  - `Odin3`
- title display is no longer coupled to ISO location; descriptor-backed title portals remain the normal path
- `FRAMEBUFFER_POLLING` survives only as a debug/regression backend

## Next phases

### Phase 10: Rebuild interaction layers

Test objective:

- recover input and audio only after visible playback is stable and diagnosable

Pass criteria:

- input and audio attach without destabilizing the running title
- regressions can still be attributed cleanly by subsystem

### Phase 11: Revalidate historical game cases

Priority titles:

- Dante's Inferno
- Forza Horizon

Pass criteria:

- game boot reaches the same rough milestones previously observed
- frame pacing is stable enough to compare against historical memory
- any regression is recorded by layer: FEX, Mesa/Turnip, Xenia, or Android glue

## Current test matrix

### Devices

- Ayn Odin 2 Mini
- Odin3

### Hardware profile

- `AYN Odin2 Mini`
  - Snapdragon 8 Gen 2
  - Adreno 740
  - 12 GB RAM
- `Odin3`
  - Snapdragon 8 Elite class platform
  - Adreno 830

### Stability checks

- cold start
- repeated relaunch
- connected-test bring-up for probes and Xenia startup
- opt-in Dante steady-state title smoke
- recovery after app data clear
- fullscreen player smoke on real devices

### Rendering and startup checks

- Vulkan probe still passes
- Turnip hardware path still passes
- Xenia reaches `VULKAN_INITIALIZED`
- `Dante's Inferno` reaches `TITLE_RUNNING_HEADLESS`
- `Dante's Inferno` reaches `FRAME_STREAM_ACTIVE`
- visible Dante frames are confirmed in the player on both devices
- no regression in the separated `app`, `fex`, and `guest` log contract

## Reconstruction priorities from here

1. Preserve the working Phase 6A baseline and its artifacts.
2. Keep the shared-memory player path stable on both devices.
3. Add interaction layers only after visible output is diagnosable.
4. Keep exact observations separate from guesses.
5. Only then chase performance tuning.
