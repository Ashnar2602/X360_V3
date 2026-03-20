# Rebuild Action Plan

## Current status

This repo is no longer "docs only".

Phase 0, Phase 1, Phase 2, Phase 3A, Phase 3B, and Phase 4A are now implemented to the point where the Android wrapper can:

- build a real FEX host baseline from vendored source
- install a deterministic runtime under `filesDir`
- launch a real Linux `x86_64` guest ELF on Android arm64
- launch a dynamic Linux `x86_64` guest ELF with guest glibc and loader resolution
- launch a guest Vulkan probe that links `libvulkan.so.1`, creates a Vulkan instance, and enumerates physical devices through `lavapipe`
- build dual guest Mesa trees from pinned upstream source snapshots through repo-owned WSL tasks
- launch the same guest Vulkan probe through a Turnip guest path with branch-aware ICD selection
- surface a verified hardware Turnip pass on `AYN Odin2 Mini`
- surface a verified hardware Turnip pass on `Odin3` after a real Mesa-side UBWC `5.0` / `6.0` fix in `mesa26`
- build a pinned-source Linux `x86_64` `Xenia Canary` binary inside this repo
- stage Xenia into the guest runtime with deterministic config, logs, and metadata
- launch Xenia through FEX on both devices and reach `VULKAN_INITIALIZED` without requiring a game image

The immediate blocker is no longer "make Turnip exist at all" and no longer "make Xenia start at all". The next milestone is title-aware Xenia bring-up and then presentation recovery.

## What is implemented

### Repo structure

- `app`
- `runtime-core`
- `native-bridge`
- `buildSrc`
- `fixtures/guest-tests`
- `fixtures/guest-runtime`
- `fixtures/mesa-runtime`
- `fixtures/xenia-runtime`
- `third_party/FEX`
- `third_party/fex-patches/android`
- `third_party/mesa-patches`
- `third_party/xenia-patches/phase4`
- `artifacts/phase4a-vulkan-init`

### FEX baseline

- vendored as a git submodule under `third_party/FEX`
- pinned to `49a37c7d6fec7d94923507e5ce10d55c2920e380`
- built from source inside this repo
- Android fixes kept as repo-owned patches, not ad-hoc edits in the submodule checkout

### Guest runtime and Vulkan baseline

- generated runtime assets for guest-side files
- generated `jniLibs` for FEX host artifacts
- generated Turnip Mesa guest trees from pinned source snapshots
- generated Xenia bring-up guest runtime from a pinned source build
- deterministic overlay flow:
  - repo payload
  - generated FEX guest assets
  - generated Vulkan-baseline guest assets
  - generated Turnip-baseline guest assets
  - generated Xenia bring-up assets
  - optional `_local/runtime-drop/` for debug builds

### Mesa / Turnip baseline

- `mesa25` guest tree from `25.3` branch snapshot `7f1ccad77883be68e7750ab30b99b16df02e679d`
- `mesa26` guest tree from `main` snapshot `44669146808b74024b9befeb59266db18ae5e165`
- repo-owned Mesa patch queue under `third_party/mesa-patches`
- `mesa26` patch set `ubwc5-a830-v1`
- branch-aware Vulkan launch environment
- KGSL preflight
- measured AUTO/manual branch policy:
  - `kalama` / `QCS8550` -> `mesa25`
  - `sun` / `CQ8725S` -> `mesa26`
  - unknown Qualcomm -> `mesa25`
  - non-Qualcomm -> `lavapipe`

### Xenia bring-up baseline

- Xenia source lock under `fixtures/xenia-runtime/xenia-source-lock.json`
- active pin:
  - `sourceRef = canary_experimental`
  - `sourceRevision = c50b036178108f87cb0acaf3691a7c3caf07820f`
  - `patchSetId = phase4-headless-posix-v2`
- repo-owned Xenia patch queue under `third_party/xenia-patches/phase4`
- generated Xenia runtime tree:
  - `rootfs/opt/x360-v3/xenia/bin/xenia-canary`
  - `rootfs/opt/x360-v3/xenia/bin/xenia-canary.config.toml`
  - `rootfs/opt/x360-v3/xenia/bin/portable.txt`
  - `rootfs/opt/x360-v3/xenia/bin/logs`
  - `rootfs/opt/x360-v3/xenia/bin/cache`
  - `rootfs/opt/x360-v3/xenia/content`
- generated metadata:
  - `payload/config/xenia-source-lock.json`
  - `payload/config/xenia-build-metadata.json`
- headless POSIX bring-up fixes delivered by the patch queue:
  - GTK-independent headless app context
  - `memfd_create`-first POSIX memory mapping
  - null-safe headless path when no ImGui drawer exists

### Archived outputs

The current successful bring-up binaries are archived under `artifacts/phase4a-vulkan-init/`:

- `xenia/xenia-canary`
- `xenia/xenia-source-lock.json`
- `xenia/xenia-build-metadata.json`
- `fex/libFEXLoader.so`
- `fex/libFEXCore.so`
- `fex/fex-build-metadata.json`
- `android/app-debug-androidTest.apk`
- `artifact-manifest.json`

The main `app-debug.apk` is not committed because it exceeds GitHub's `100 MB` per-file limit, but its exact `sha256` and size are captured in `artifact-manifest.json`.

## Runtime contract

Fixed runtime paths now include:

- `<filesDir>/rootfs/lib64/ld-linux-x86-64.so.2`
- `<filesDir>/rootfs/usr/lib/x86_64-linux-gnu/libvulkan.so.1`
- `<filesDir>/rootfs/usr/lib/x86_64-linux-gnu/libvulkan_lvp.so`
- `<filesDir>/rootfs/usr/share/vulkan/icd.d/lvp_icd.json`
- `<filesDir>/rootfs/opt/x360-v3/mesa/mesa25/lib/libvulkan_freedreno.so`
- `<filesDir>/rootfs/opt/x360-v3/mesa/mesa25/icd/turnip_icd.json`
- `<filesDir>/rootfs/opt/x360-v3/mesa/mesa26/lib/libvulkan_freedreno.so`
- `<filesDir>/rootfs/opt/x360-v3/mesa/mesa26/icd/turnip_icd.json`
- `<filesDir>/rootfs/opt/x360-v3/xenia/bin/xenia-canary`
- `<filesDir>/rootfs/opt/x360-v3/xenia/bin/xenia-canary.config.toml`
- `<filesDir>/payload/config/fex-build-metadata.json`
- `<filesDir>/payload/config/guest-runtime-metadata.json`
- `<filesDir>/payload/config/mesa-runtime-metadata.json`
- `<filesDir>/payload/config/mesa-turnip-source-lock.json`
- `<filesDir>/payload/config/xenia-source-lock.json`
- `<filesDir>/payload/config/xenia-build-metadata.json`
- `<filesDir>/logs/app`
- `<filesDir>/logs/fex`
- `<filesDir>/logs/guest`

Historical placeholders remain reserved:

- `rootfs/tmp/anative_window.ptr`
- `rootfs/tmp/xenia_fb`

## Phase status

### Phase 0: Reconstruction contract

Status: complete

### Phase 1: Wrapper skeleton

Status: complete

Delivered:

- Android app shell
- runtime installer
- runtime manifest contract
- separated logging
- stub fallback launcher

### Phase 2: FEX baseline to hello ELF

Status: complete

Delivered:

- vendored FEX baseline
- Android patch queue
- generated FEX host artifacts
- real FEX launcher
- micro-rootfs
- static guest fixture
- connected-device verification

### Phase 3A: Dynamic guest userspace plus Vulkan loader baseline

Status: complete

Delivered:

- Ubuntu 24.04 guest-runtime lock manifest
- generated guest rootfs slice
- guest dynamic loader and glibc path
- guest Vulkan loader path
- guest `lavapipe` ICD path
- dynamic hello probe
- Vulkan probe
- connected-device verification

### Phase 3B: Turnip guest Vulkan baseline

Status: complete

Delivered:

- dual-Mesa build pipeline through WSL
- `mesa25` and `mesa26` staged runtime trees
- branch-aware Vulkan launch environment
- KGSL preflight
- AUTO/manual branch selection policy
- hardware Turnip pass on `AYN Odin2 Mini`
- repo-owned `mesa26` UBWC `5.0` / `6.0` fix
- hardware Turnip pass on `Odin3`

### Phase 4A: Pinned-source Xenia bring-up to Vulkan init

Status: complete

Delivered:

- pinned-source Xenia build pipeline through WSL
- repo-owned Xenia patch queue
- generated Xenia runtime slice under `rootfs/opt/x360-v3/xenia`
- Xenia config and metadata staging
- headless POSIX bring-up path without GTK window creation
- `memfd_create`-first POSIX memory reservation path
- Xenia startup-stage parsing
- connected-device verification that `Launch Xenia Bring-up` reaches `VULKAN_INITIALIZED`

## Verified Phase 4A result

Phase 4A is considered complete in this repo because all of the following are now true:

- the Vulkan probe still passes with `lavapipe`
- the Turnip hardware probe still passes on `AYN Odin2 Mini`
- the Turnip hardware probe still passes on `Odin3`
- Xenia is built from pinned source inside this repo
- Xenia is staged into the runtime with deterministic config and metadata
- Xenia reaches `VULKAN_INITIALIZED` through FEX on both connected devices

### Verified devices

- `AYN Odin2 Mini` / Android 13 / API 33
- `Odin3` / Android 15 / API 35

### Verified per-device outcome

`AYN Odin2 Mini`:

- AUTO branch resolves to `mesa25`
- Turnip probe passes
- Xenia bring-up reaches `VULKAN_INITIALIZED`
- guest log confirms `Turnip Adreno (TM) 740`

`Odin3`:

- AUTO branch resolves to `mesa26`
- Turnip probe passes
- `KgslPropertiesInstrumentedTest` confirms `ubwc_mode = 5`
- Xenia bring-up reaches `VULKAN_INITIALIZED`
- guest log confirms `Adreno (TM) 830`

## Next milestone: Phase 4B

### Goal

Move from no-title Xenia startup to first title-aware Xenia bring-up while keeping the validated FEX and Turnip baseline green on both devices.

### Scope

- keep the current FEX and Turnip regression matrix intact
- add the smallest deterministic content handoff needed to prove Xenia can launch a target path
- keep the work non-presenting until title boot is stable
- continue to defer Android surface handoff and `xenia_fb` recovery

### Pass criteria

- the Vulkan probe still passes with `lavapipe`
- the Turnip probe still passes on both devices
- Xenia still reaches `VULKAN_INITIALIZED`
- the first title-aware launch path is real and diagnosable
- later failures, if any, are inside Xenia title boot or remaining graphics bridge work, not in FEX or Turnip

## Main risks from here

- the historical repo mixed multiple graphics experiments at once, so later recovery work must stay single-threaded by subsystem
- even after guest Turnip works and Xenia initializes Vulkan, title boot and presentation can still expose separate rendering, timing, and resource issues
- preserving exact artifact provenance matters now because the stack is finally crossing from synthetic probes into emulator startup
