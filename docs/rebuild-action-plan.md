# Rebuild Action Plan

## Current status

This repo is no longer "docs only".

Phase 0, Phase 1, Phase 2, Phase 3A, and Phase 3B are now implemented to the point where the Android wrapper can:

- build a real FEX host baseline from vendored source
- install a deterministic runtime under `filesDir`
- launch a real Linux `x86_64` guest ELF on Android arm64
- launch a dynamic Linux `x86_64` guest ELF with guest glibc/loader resolution
- launch a guest Vulkan probe that links `libvulkan.so.1`, creates a Vulkan instance, and enumerates physical devices through `lavapipe`
- build dual guest Mesa trees from pinned upstream source snapshots through repo-owned WSL tasks
- launch the same guest Vulkan probe through a Turnip guest path with branch-aware ICD selection
- write separated `app`, `fex`, and `guest` logs
- repeat the launch successfully on the current baseline devices
- surface a verified hardware Turnip pass on `AYN Odin2 Mini`
- surface a verified hardware Turnip pass on `Odin3` after a real Mesa-side UBWC `5.0` / `6.0` fix in `mesa26`

The next milestone is no longer "make Turnip exist at all", and Odin3 Turnip stabilization is no longer the blocker. The immediate path is to start the first `Xenia Canary` bring-up while keeping the new dual-device Turnip probe matrix green.

## What is implemented

### Repo structure

- `app`
- `runtime-core`
- `native-bridge`
- `buildSrc`
- `fixtures/guest-tests`
- `fixtures/guest-runtime`
- `fixtures/mesa-runtime`
- `third_party/FEX`
- `third_party/fex-patches/android`

### FEX baseline

- vendored as a git submodule under `third_party/FEX`
- pinned to `49a37c7d6fec7d94923507e5ce10d55c2920e380`
- built from source inside this repo
- Android fixes kept as repo-owned patches, not ad-hoc edits in the submodule checkout

### Runtime pipeline

- generated runtime assets for guest-side files
- generated `jniLibs` for FEX host artifacts
- generated Turnip Mesa guest trees from pinned source snapshots
- deterministic overlay flow:
  - repo mock payload
  - generated FEX guest assets
  - generated Vulkan-baseline guest assets
  - generated Turnip-baseline guest assets
  - optional `_local/runtime-drop/` for debug builds

### Host artifacts

- `libFEXLoader.so`
- `libFEXCore.so`

These are packaged as APK native libs, not app assets. The app enables legacy JNI lib extraction so `applicationInfo.nativeLibraryDir` contains executable filesystem paths for the FEX loader contract.

### Guest-side Phase 2 payload

- micro-rootfs skeleton
- static `hello_x86_64` ELF fixture
- generated FEX build metadata JSON

### Guest-side Phase 3A payload

- Ubuntu 24.04 `amd64` guest-runtime lock manifest with pinned package URLs and `sha256`
- generated guest rootfs slice assembled from the pinned `.deb` packages
- guest glibc loader under `rootfs/lib64/ld-linux-x86-64.so.2`
- guest runtime libraries under `rootfs/lib/x86_64-linux-gnu`
- guest Vulkan loader under `rootfs/usr/lib/x86_64-linux-gnu/libvulkan.so.1`
- guest `lavapipe` ICD under `rootfs/usr/lib/x86_64-linux-gnu/libvulkan_lvp.so`
- guest ICD JSON under `rootfs/usr/share/vulkan/icd.d/lvp_icd.json`
- generated guest runtime metadata JSON
- dynamic `dyn_hello_x86_64` guest probe
- `vulkan_probe_x86_64` guest probe

### Guest-side Phase 3B payload

- repo-owned Mesa source lock manifest under `fixtures/mesa-runtime/mesa-turnip-source-lock.json`
- WSL preflight for `meson`, `ninja`, `clang`, `python3`, `bison`, `flex`, `pkg-config`, `cmake`, `curl`, `glslangValidator`
- generated `mesa25` guest tree from `25.3` branch snapshot `7f1ccad77883be68e7750ab30b99b16df02e679d`
- generated `mesa26` guest tree from `main` snapshot `44669146808b74024b9befeb59266db18ae5e165`
- repo-owned Mesa patch queue under `third_party/mesa-patches`
- `mesa26` patch set `ubwc5-a830-v1`, applied during asset generation and carried into `mesa-runtime-metadata.json`
- staged bundle roots:
  - `rootfs/opt/x360-v3/mesa/mesa25/lib`
  - `rootfs/opt/x360-v3/mesa/mesa25/icd/turnip_icd.json`
  - `rootfs/opt/x360-v3/mesa/mesa26/lib`
  - `rootfs/opt/x360-v3/mesa/mesa26/icd/turnip_icd.json`
- generated Mesa runtime metadata JSON
- AUTO/manual selection policy between `mesa25`, `mesa26`, and `lavapipe`
- KGSL preflight before Turnip launch

### Runtime contract

Fixed runtime paths now include:

- `<filesDir>/rootfs`
- `<filesDir>/rootfs/lib64/ld-linux-x86-64.so.2`
- `<filesDir>/rootfs/lib/x86_64-linux-gnu/...`
- `<filesDir>/rootfs/usr/lib/x86_64-linux-gnu/libvulkan.so.1`
- `<filesDir>/rootfs/usr/lib/x86_64-linux-gnu/libvulkan_lvp.so`
- `<filesDir>/rootfs/usr/share/vulkan/icd.d/lvp_icd.json`
- `<filesDir>/rootfs/opt/x360-v3/mesa/mesa25/lib/libvulkan_freedreno.so`
- `<filesDir>/rootfs/opt/x360-v3/mesa/mesa25/icd/turnip_icd.json`
- `<filesDir>/rootfs/opt/x360-v3/mesa/mesa26/lib/libvulkan_freedreno.so`
- `<filesDir>/rootfs/opt/x360-v3/mesa/mesa26/icd/turnip_icd.json`
- `<filesDir>/rootfs/tmp`
- `<filesDir>/.fex-emu/Config.json`
- `<filesDir>/payload/guest-tests/bin/hello_x86_64`
- `<filesDir>/payload/guest-tests/bin/dyn_hello_x86_64`
- `<filesDir>/payload/guest-tests/bin/vulkan_probe_x86_64`
- `<filesDir>/payload/config/fex-build-metadata.json`
- `<filesDir>/payload/config/guest-runtime-metadata.json`
- `<filesDir>/payload/config/ubuntu-24.04-amd64-lvp.lock.json`
- `<filesDir>/payload/config/mesa-runtime-metadata.json`
- `<filesDir>/payload/config/mesa-turnip-source-lock.json`
- `<filesDir>/logs/app`
- `<filesDir>/logs/fex`
- `<filesDir>/logs/guest`
- `<filesDir>/rootfs/tmp/fex_hello_ok.json`
- `<filesDir>/rootfs/tmp/fex_dyn_hello_ok.json`
- `<filesDir>/rootfs/tmp/fex_vulkan_probe.json`

Historical placeholders remain reserved:

- `rootfs/tmp/anative_window.ptr`
- `rootfs/tmp/xenia_fb`

## Important implementation decisions

### 1. Modern Android tooling, pinned historical FEX

The repo uses current Android project tooling, not the old wrapper stack:

- AGP `9.1.0`
- Gradle `9.3.1`
- `compileSdk` / `targetSdk` `35`
- `minSdk` `33`

The historical compatibility pin is applied to the FEX source baseline, not to the entire Android project.

### 2. NDK `27.2.12479018`

The earlier working notes pointed toward `r25b`, but the actual bring-up in this repo is stabilized on `27.2.12479018`.

Reason:

- the pinned FEX commit `49a37c7` needed Android-specific integration fixes anyway
- the current modern NDK produced a reproducible local and device-successful build
- the reconstruction should optimize for a reproducible real baseline, not for historical-tool nostalgia

If a later milestone proves a specific reason to move back to `r25b`, that can be tested explicitly.

### 3. FEX patch queue is first-class

The Android baseline is not "submodule plus luck". It is:

- pinned commit
- copied working tree
- repo-owned patch queue applied during build preparation

This is important because the commit is historically meaningful, but Android buildability required an explicit compatibility layer.

### 4. Sentinel path is launcher-controlled

The guest fixtures keep their original `/tmp/...` default sentinel paths.

The current launcher passes relative sentinel paths inside the rootfs so host verification is deterministic under:

- `<filesDir>/rootfs/tmp/fex_hello_ok.json`
- `<filesDir>/rootfs/tmp/fex_dyn_hello_ok.json`
- `<filesDir>/rootfs/tmp/fex_vulkan_probe.json`

This avoids depending on unresolved guest absolute-path semantics while keeping the fixture provenance intact.

### 5. Ubuntu 24.04 lock manifest is the source of truth

The guest runtime is not copied from an opaque rootfs blob.

It is assembled from a repo-owned lock manifest that pins:

- distro and release
- package URLs
- package versions
- `sha256`
- extracted file slice

This keeps the Phase 3A guest runtime reproducible and reviewable.

### 6. Lavapipe first, Turnip later

The Vulkan baseline is intentionally software-first:

- `lavapipe` was chosen to prove guest loader, glibc, ICD, and Vulkan control flow without coupling to missing Turnip packaging details
- this milestone proves guest Vulkan initialization discipline, not GPU correctness

### 7. Dual-Mesa early, but policy follows measured device behavior

The repo now packages both Mesa branches immediately:

- `mesa25` as the stable Adreno `7xx` baseline
- `mesa26` as the modern branch already ready in runtime

The AUTO policy is now based on measured bring-up results, not only on the earlier paper plan:

- `kalama` / `QCS8550` -> `mesa25`
- `sun` / `CQ8725S` -> `mesa26`
- other Qualcomm devices -> `mesa25` fallback
- non-Qualcomm devices -> `lavapipe`

This change was made because real device tests showed:

- `AYN Odin2 Mini` succeeds with Turnip on refreshed `mesa25`
- `Odin3` requires `mesa26` because it reports `ubwc_mode = 5`
- `Odin3` passes the Turnip hardware probe once the repo-owned `mesa26` patch set teaches the KGSL path to accept UBWC `5.0` / `6.0` and let `fd_dev_info` provide the final A8xx/A830 UBWC config

## Verified Phase 3B result

### Pass statement

Phase 3B is considered complete in this repo because all of the following are now true:

- FEX host loader builds from vendored source inside the repo
- the app can install its Phase 3B runtime deterministically
- `hello_x86_64` still launches through real FEX on Android arm64
- `dyn_hello_x86_64` launches through real FEX and creates `rootfs/tmp/fex_dyn_hello_ok.json`
- `vulkan_probe_x86_64` can launch through:
  - `lavapipe`
  - `mesa25` Turnip
  - `mesa26` Turnip
- the guest Turnip hardware marker `X360_VK_PROBE_OK` is verified on the baseline pass device
- `app`, `fex`, and `guest` logs are separated by session
- repeated launch remains stable
- `run-as emu.x360.mobile.dev` confirms:
  - `mesa25/icd/turnip_icd.json`
  - `mesa26/icd/turnip_icd.json`
  - `fex_vulkan_probe.json`

### Verified devices

- `AYN Odin2 Mini` / Android 13 / API 33
- `Odin3` / Android 15 / API 35

### Verified per-device outcome

`AYN Odin2 Mini`:

- AUTO branch resolves to `mesa25`
- `vulkan_probe_x86_64` succeeds through Turnip
- manual `run-as` sentinel confirms:
  - `marker = X360_VK_PROBE_OK`
  - `driver_mode = turnip`
  - `instance_ok = true`
  - `device_count = 1`
  - `device_name = "Turnip Adreno (TM) 740"`

`Odin3`:

- AUTO branch resolves to `mesa26`
- `KgslPropertiesInstrumentedTest` confirms `ubwc_mode = 5`
- the `mesa26` patch set `ubwc5-a830-v1` is present in generated runtime metadata
- `launchTurnipProbeUsesHardwarePathAndSupportsRepeatLaunch` passes in connected tests
- the probe now reaches a real hardware Turnip device instead of dying in `vkEnumeratePhysicalDevices`

That means:

- `AYN Odin2 Mini` is the current Phase 3B pass baseline
- `Odin3` is now also a verified hardware-pass device for the Turnip probe milestone

## Phase status

### Phase 0: Reconstruction contract

Status: complete

Locked outcomes:

- Linux `x86_64` guest flow
- FEX-first Android wrapper design
- headless/offscreen-first recovery strategy

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
- `run-as` verification on the real app runtime

### Phase 3B: Turnip guest Vulkan baseline

Status: complete

Delivered:

- dual-Mesa build pipeline through WSL
- `mesa25` and `mesa26` staged runtime trees
- branch-aware Vulkan launch environment
- KGSL preflight
- AUTO/manual branch selection policy
- hardware Turnip pass on `AYN Odin2 Mini`
- repo-owned `mesa26` patch queue for UBWC `5.0` / `6.0`
- hardware Turnip pass on `Odin3`

## Next milestone: Phase 4

### Goal

Start the first `Xenia Canary` bring-up on top of the now-validated Turnip baseline, while keeping both verified probe devices in the regression matrix.

### Scope

Add the smallest credible guest-side stack that supports:

- launching Linux `x86_64` `Xenia Canary` inside the validated guest runtime
- keeping Turnip selection fixed on the validated device policy:
  - `mesa25` on `AYN Odin2 Mini`
  - `mesa26` on `Odin3`
- recovering the first non-presenting Xenia startup path before worrying about visible output
- preserving the existing probe path so Turnip regressions stay easy to isolate

### Deliverables

- first `Xenia Canary` guest packaging slice
- launcher contract for Xenia sessions
- first Xenia startup validation on the baseline Turnip device
- diagnostics that separate:
  - FEX failure
  - Turnip failure
  - Xenia startup failure
- keeping Odin3 in the Turnip regression matrix while Phase 4 grows

### Pass criteria

- the Vulkan probe still passes with `lavapipe`
- the Turnip probe still passes on `AYN Odin2 Mini`
- the Turnip probe still passes on `Odin3`
- Xenia starts far enough to prove the guest binary handoff is real
- later failures, if any, are inside Xenia or the remaining graphics bridge work, not in the FEX or Turnip baseline

### Explicitly still out of scope in Phase 3B

- frame presentation
- gameplay validation
- performance tuning
- thunk optimization work beyond the minimum needed for first Xenia bring-up

## Main risks from here

- the historical repo mixed multiple graphics experiments at once, so later recovery work must stay single-threaded by subsystem
- future Adreno `8xx` paths may still expose additional driver-side or loader-side differences even with dual Mesa packaged
- even after guest Turnip works, Xenia can still surface separate rendering, timing, and presentation issues

## Success definition for the full reconstruction

This reconstruction is successful when all of the following are true:

- the app can rebuild its runtime from documented steps
- FEX launches guest `x86_64` processes reliably
- guest Vulkan initializes with Turnip on Adreno
- the baseline Adreno hardware path is reproducible on the target class of devices
- Xenia renders visible frames again
- the stack is stable enough to optimize instead of rediscovering missing pieces
