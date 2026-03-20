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

## Next phases

### Phase 6: Title-aware Xenia bring-up

Test objective:

- move from no-title Xenia startup to first deterministic target launch

Pass criteria:

- Xenia still reaches Vulkan init first
- title handoff is real and logged
- failures can be attributed to Xenia title boot rather than FEX or Turnip

### Phase 7: Rebuild presentation

Test objective:

- recover a visible rendering/output path on Android

Pass criteria:

- frame production survives beyond startup
- output can be surfaced to Android without breaking the validated guest Vulkan path
- no regression in the now-working Xenia startup milestone

### Phase 8: Revalidate historical game cases

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
- recovery after app data clear

### Rendering and startup checks

- Vulkan probe still passes
- Turnip hardware path still passes
- Xenia reaches `VULKAN_INITIALIZED`
- no regression in the separated `app`, `fex`, and `guest` log contract

## Reconstruction priorities from here

1. Preserve the working Phase 4A baseline and its artifacts.
2. Add title-aware Xenia bring-up without disturbing FEX or Turnip.
3. Recover presentation only after title boot is diagnosable.
4. Keep exact observations separate from guesses.
5. Only then chase performance tuning.
