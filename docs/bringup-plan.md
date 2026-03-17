# Bring-Up Plan

## Goal

Reconstruct the previously working pipeline in a controlled order, so each stage proves one layer of the stack before the next layer is introduced.

## Phase 1: Re-establish the architecture contract

Outputs:

- pin the target stack as Linux `x86_64` Xenia Canary under FEX 2601-like behavior
- document whether the project will prefer exact historical recreation first or "closest working upstream equivalents"
- write down all remembered startup assumptions and environment variables as they resurface

Pass criteria:

- the repo captures one clear target architecture instead of multiple competing designs

## Phase 2: Rebuild the runtime skeleton without Xenia

Test objective:

- prove that a translated Linux `x86_64` ELF can start cleanly in the app-owned Android environment through FEX

Pass criteria:

- deterministic process startup
- stable repeated runs
- logging that clearly separates app layer, FEX layer, and guest process layer

## Phase 3: Rebuild the Vulkan userspace path

Test objective:

- prove that the packaged Vulkan loader/ICD path works in the translated `x86_64` environment

Pass criteria:

- Vulkan instance creation succeeds
- physical device enumeration succeeds
- logical device and queue creation succeeds
- repeated queue submit and synchronization succeed without a visible stall pattern

## Phase 4: Rebuild headless/offscreen presentation

Test objective:

- prove that the rendering path can produce images without X11 or Wayland

Pass criteria:

- offscreen rendering completes across many frames
- output can be copied or exposed to the Android display path
- no KGSL saturation or freeze over a sustained run window

## Phase 5: Introduce Xenia Canary

Test objective:

- bring up the Linux `x86_64` Vulkan path of Xenia Canary in the rebuilt runtime

Pass criteria:

- emulator boot reaches guest startup
- rendering path initializes
- basic input plumbing reaches the emulator

## Phase 6: Revalidate historical game cases

Priority titles:

- Dante's Inferno
- Forza Horizon

Pass criteria:

- game boot reaches the same rough milestones previously observed
- frame pacing is stable enough to compare against historical memory
- any regression is recorded by layer: FEX, Mesa/Turnip, Xenia, or Android glue

## Test matrix

### Devices

- Ayn Odin 2
- Ayn Odin 2 Mini

### Hardware profile

- Snapdragon 8 Gen 2
- Adreno 740
- 12 GB RAM

### Stability checks

- cold start
- repeated relaunch
- ten-minute idle or attract-mode style run
- sustained gameplay window
- recovery after suspend/resume if applicable

### Rendering checks

- first frame appears
- frame count continues to advance
- no freeze after initial burst of work
- no evidence of unbounded queue or resource growth

## Reconstruction priorities

1. Recover the display/presentation architecture.
2. Recover Vulkan loader and Turnip packaging details.
3. Recover FEX 2601 integration assumptions.
4. Recover Xenia-specific patches or build toggles.
5. Only then chase performance tuning.

## Notes for future work

- If exact historical binaries cannot be recovered, recreate the behavior envelope first, then narrow differences.
- Keep exact observations separate from guesses to avoid building myths into the stack.
- Every future test result should be tagged by component layer and device.
