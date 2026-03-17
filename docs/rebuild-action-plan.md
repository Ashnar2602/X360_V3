# Rebuild Action Plan

## Objective

Rebuild the Android app around the workflow that already proved viable:

`Android app shell -> FEX 2601 -> Linux x86_64 guest userspace -> Xenia Canary -> Vulkan -> Turnip -> KGSL -> Adreno`

The immediate goal is not to ship the fastest version. The immediate goal is to recover a reproducible baseline that boots, renders, and can be validated against the historical milestone.

## Ground Rules

- Recreate the known-good architecture before attempting improvements.
- Keep `FEX 2601` as the baseline until the stack is stable again.
- Treat `Mesa 25.x` as the first graphics baseline for Adreno `7xx`.
- Do not mix structural rewrites and performance work in the same milestone.
- Every milestone must end with a concrete pass/fail test.

## Working Baseline

### Fixed assumptions

- Device class: Snapdragon 8 Gen 2 / Adreno 740 / 12 GB RAM
- Initial target devices:
  - Ayn Odin 2
  - Ayn Odin 2 Mini
- Known-good emulator family:
  - Xenia Canary Linux `x86_64`
- Known-good FEX family:
  - `FEX-2601-181-g49a37c7`
- Known-good Xenia base:
  - `canary_experimental@d9747704b`
- Known-good graphics direction:
  - Vulkan
  - headless or offscreen runtime
  - X11 and Wayland bypassed or neutralized

### Things we intentionally defer

- `FEX 2603`
- Mesa `26.x` on the first bring-up path
- async callback optimization
- direct final Android presentation path if framebuffer capture is enough to prove life first

## Phase 0: Freeze the Reconstruction Contract

### Goal

Make sure the project has one clear technical target and one clear version policy.

### Deliverables

- confirmed baseline component list
- repo branch policy
- runtime naming and directory layout proposal

### Pass criteria

- no ambiguity remains about whether we are rebuilding:
  - Linux `x86_64` guest flow
  - `FEX 2601`
  - `Mesa 25.x`
  - headless or offscreen presentation first

## Phase 1: Recreate the App Skeleton

### Goal

Bring back the Android wrapper structure without depending on Xenia yet.

### Deliverables

- app module layout
- asset extraction path
- runtime directory initialization
- logging split by:
  - app
  - FEX
  - guest process

### Pass criteria

- the app can unpack its runtime payload deterministically
- the app can launch a controlled guest process through FEX
- logs are readable enough to debug the next layer

## Phase 2: Rebuild FEX 2601 Baseline

### Goal

Restore the Android build and integration assumptions for the exact FEX family that previously worked.

### Deliverables

- pinned FEX source commit or nearest exact source state
- documented Android-specific patches
- reproducible FEX build notes
- runtime launch contract:
  - loader path
  - environment variables
  - rootfs expectations

### Pass criteria

- a simple Linux `x86_64` test ELF launches and exits correctly
- repeated launches remain stable
- memory mapping and JIT execution behave consistently

## Phase 3: Rebuild the Minimal Guest Userspace

### Goal

Assemble only the `x86_64` userspace pieces required for guest startup and graphics bring-up.

### Deliverables

- rootfs layout
- required desktop compatibility libraries
- Vulkan loader placement
- ICD manifest placement
- symlink map for guest-visible libraries

### Pass criteria

- the guest environment resolves its dynamic libraries without manual on-device patching
- the runtime can be rebuilt from documented assets and scripts

## Phase 4: Recover the Vulkan Graphics Path

### Goal

Prove that Vulkan works inside the guest flow before Xenia is introduced.

### Deliverables

- packaged `Mesa 25.x` Turnip `x86_64` runtime
- loader environment contract
- test program for:
  - instance creation
  - physical device enumeration
  - logical device creation
  - queue submit and fence wait

### Pass criteria

- Vulkan initialization succeeds reliably
- repeated GPU work does not freeze after the initial burst
- there is no sign of immediate KGSL saturation during the synthetic test

## Phase 5: Rebuild Presentation and Frame Visibility

### Goal

Recover the route from guest-rendered frames to something visible in the Android app.

### Two valid subtargets

- historical baseline:
  - framebuffer capture via `/tmp/xenia_fb`
- later preferred direction:
  - `xcb -> android_surface` bridge

### Deliverables

- one working visibility path
- pacing and lifetime notes for images or buffers
- logging around frame cadence and stalls

### Pass criteria

- frames continue to advance for a sustained run window
- no freeze after seconds or minutes under repeated frame production
- Android UI can observe the output without destabilizing the guest

## Phase 6: Reintroduce Xenia Canary

### Goal

Swap the synthetic Vulkan test out for the real Xenia Linux `x86_64` binary and restore the emulator startup path.

### Deliverables

- Xenia packaging flow
- required runtime flags
- config file strategy
- boot log capture

### Pass criteria

- Xenia starts under FEX
- Vulkan backend initializes
- the app receives visible frame output again

## Phase 7: Revalidate the Historical Titles

### Priority ROMs

- Dante's Inferno
- Forza Horizon

### Goal

Confirm that the rebuilt stack reaches the same class of functionality previously observed.

### Pass criteria

- each game boots far enough to compare behavior with memory
- rendering stays alive during real gameplay
- input plumbing reaches at least the same partial state as before

## Phase 8: Modernization Tracks After Baseline

### Track A

- upgrade `FEX 2601 -> 2603`

### Track B

- add dual Mesa packaging:
  - `Mesa 25.x`
  - `Mesa 26.x`

### Track C

- revisit async callbacks and presentation optimizations

### Rule

Only one modernization track should move at a time, so regressions remain attributable.

## Immediate Execution Order

1. Lock the baseline versions and directory layout.
2. Recreate the Android wrapper and asset extraction flow.
3. Rebuild `FEX 2601` for the app and prove a simple guest ELF launch.
4. Recreate the minimal guest rootfs and guest library layout.
5. Rebuild the `Mesa 25.x` Turnip `x86_64` userspace and make Vulkan enumerate.
6. Restore one visible frame path, even if it is the older framebuffer capture route.
7. Bring Xenia back into the loop.
8. Only after first visible game boot, branch for FEX and Mesa upgrades.

## Main Risks

- the missing Turnip packaging step may have depended on manual work not captured in Git
- the old repo mixed multiple graphics approaches in flight at once
- KGSL saturation may return unless frame retirement and pacing are handled earlier
- desktop Linux dependencies inside Xenia may require careful suppression rather than removal

## Success Definition

This reconstruction is successful when we can say all of the following are true:

- the app can rebuild its runtime from documented steps
- FEX launches guest `x86_64` processes reliably
- guest Vulkan initializes with Turnip on Adreno
- Xenia renders visible frames again
- the stack is stable enough to begin optimization instead of rediscovery
