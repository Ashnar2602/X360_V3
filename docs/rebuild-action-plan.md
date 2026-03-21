# Rebuild Action Plan

## Current status

This repo is no longer "docs only".

Phase 0, Phase 1, Phase 2, Phase 3A, Phase 3B, Phase 4A, Phase 4B, Phase 4C, Phase 5A, and Phase 5B are now implemented to the point where the Android wrapper can:

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
- persist a no-copy ISO library under app-owned metadata
- import and resolve filesystem-backed ISO paths into `rootfs/mnt/library`
- launch `Dante's Inferno` from ISO and reach `TITLE_MODULE_LOADING`
- keep `Dante's Inferno` alive headless for the fixed observation window without fatal aborts on both devices
- export visible frames through `rootfs/tmp/xenia_fb`
- display visible Dante frames in the Android app on both devices
- run a product-facing shell with splash, library home, options, debug screen, and fullscreen player

The immediate blocker is no longer "make Turnip exist at all", no longer "make Xenia start at all", no longer "make a title survive module load", and no longer "make any visible frames appear". The next milestone is interaction and player hardening on top of the visible path.

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
  - `kalama` / `QCS8550` -> `mesa26`
  - `sun` / `CQ8725S` -> `mesa26`
  - unknown Qualcomm -> `mesa25`
  - non-Qualcomm -> `lavapipe`

### Xenia bring-up baseline

- Xenia source lock under `fixtures/xenia-runtime/xenia-source-lock.json`
- active pin:
  - `sourceRef = canary_experimental`
  - `sourceRevision = c50b036178108f87cb0acaf3691a7c3caf07820f`
  - `patchSetId = phase5a-framebuffer-polling-v11`
- repo-owned Xenia patch queue under `third_party/xenia-patches/phase4`
- generated Xenia runtime tree:
  - `rootfs/opt/x360-v3/xenia/bin/xenia-canary`
  - `rootfs/opt/x360-v3/xenia/bin/xenia-canary.config.toml`
  - `rootfs/opt/x360-v3/xenia/bin/portable.txt`
  - `rootfs/opt/x360-v3/xenia/bin/logs`
  - `rootfs/opt/x360-v3/xenia/bin/cache`
  - `rootfs/opt/x360-v3/xenia/bin/cache0`
  - `rootfs/opt/x360-v3/xenia/bin/cache1`
  - `rootfs/opt/x360-v3/xenia/bin/scratch`
  - `rootfs/opt/x360-v3/xenia/cache-host`
  - `rootfs/opt/x360-v3/xenia/cache-host/modules`
  - `rootfs/opt/x360-v3/xenia/cache-host/shaders/shareable`
  - `rootfs/opt/x360-v3/xenia/content`
  - `rootfs/mnt/library`
- generated metadata:
  - `payload/config/xenia-source-lock.json`
  - `payload/config/xenia-build-metadata.json`
- headless POSIX bring-up fixes delivered by the patch queue:
  - GTK-independent headless app context
  - `memfd_create`-first POSIX memory mapping
  - null-safe headless path when no ImGui drawer exists
  - non-fatal module cache initialization for headless title boot
  - framebuffer-polling export path for visible Android frames
- persistent incremental build workspace for local Xenia development
- JSON-backed no-copy ISO library with title-aware launch

### Archived outputs

The current successful bring-up binaries are archived under:

- `artifacts/phase4a-vulkan-init/`
- `artifacts/phase5b-visible-player/`

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

Reserved future placeholder:

- `rootfs/tmp/anative_window.ptr`

Active framebuffer export path:

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

- AUTO branch resolves to `mesa26`
- Turnip probe passes
- Xenia bring-up reaches `VULKAN_INITIALIZED`
- guest log confirms `Turnip Adreno (TM) 740`

`Odin3`:

- AUTO branch resolves to `mesa26`
- Turnip probe passes
- `KgslPropertiesInstrumentedTest` confirms `ubwc_mode = 5`
- Xenia bring-up reaches `VULKAN_INITIALIZED`
- guest log confirms `Adreno (TM) 830`

### Phase 4B: ISO-first title boot

Status: complete

Delivered:

- JSON-backed game library under `filesDir/library/game-library.json`
- no-copy ISO import and persistence
- filesystem-backed resolver for `file://` and supported `content://` paths
- guest content portal under `rootfs/mnt/library/<entry-id>.iso`
- title-aware Xenia launch path
- startup stages:
  - `DISC_IMAGE_ACCEPTED`
  - `TITLE_MODULE_LOADING`
  - `TITLE_METADATA_AVAILABLE`
- `Dante's Inferno` smoke path validated to title boot from ISO on both devices

### Phase 4C: Steady-state headless title run

Status: complete

Delivered:

- Xenia cache root moved to `rootfs/opt/x360-v3/xenia/cache-host`
- guest-writable cache and scratch directories created deterministically before launch
- repo-owned Xenia patch to make module cache initialization non-fatal
- startup stage `TITLE_RUNNING_HEADLESS`
- steady-state success policy for imported titles
- diagnostic capture for:
  - alive-after-module-load seconds
  - cache backend status
  - cache root path
  - title metadata seen
- persistent incremental Xenia build path for local debug rebuilds

## Verified Phase 4C result

Phase 4C is considered complete in this repo because all of the following are now true:

- the Vulkan probe still passes with `lavapipe`
- the Turnip hardware probe still passes on `AYN Odin2 Mini`
- the Turnip hardware probe still passes on `Odin3`
- Xenia is still built from pinned source inside this repo
- imported ISO titles still pass through deterministic no-copy portalization
- `Dante's Inferno` reaches `TITLE_RUNNING_HEADLESS` on both connected devices
- the process survives the observation window without the previous `std::filesystem::filesystem_error` abort

### Verified per-device outcome

`AYN Odin2 Mini`:

- AUTO branch resolves to `mesa26`
- Turnip probe passes
- Xenia bring-up reaches `VULKAN_INITIALIZED`
- `Dante's Inferno` reaches `TITLE_RUNNING_HEADLESS`

`Odin3`:

- AUTO branch resolves to `mesa26`
- Turnip probe passes
- `KgslPropertiesInstrumentedTest` confirms `ubwc_mode = 5`
- Xenia bring-up reaches `VULKAN_INITIALIZED`
- `Dante's Inferno` reaches `TITLE_RUNNING_HEADLESS`

### Phase 5A: First visible frames through framebuffer polling

Status: complete

Delivered:

- repo-owned Xenia framebuffer-polling export path
- versioned `xenia_fb` contract
- Android-side framebuffer reader and preview pipeline
- startup stages:
  - `FIRST_FRAME_CAPTURED`
  - `FRAME_STREAM_ACTIVE`
- future-ready guest render-scale contract with:
  - `HALF`
  - `ONE`
  - `ONE_AND_HALF`
  - `TWO`

### Phase 5B: Product UI restructure and fullscreen player shell

Status: complete

Delivered:

- `SplashActivity` launcher flow
- library-first `MainActivity`
- `Options` path with preserved `Debug` screen
- per-game `Play` and `Options` actions
- dedicated fullscreen `PlayerActivity`
- FPS overlay setting and player session controller
- `X360 Mobile` `0.2.0 alpha` visible product shell

## Verified Phase 5B result

Phase 5B is considered complete in this repo because all of the following are now true:

- the Vulkan probe still passes with `lavapipe`
- the Turnip hardware probe still passes on `AYN Odin2 Mini`
- the Turnip hardware probe still passes on `Odin3`
- Xenia is still built from pinned source inside this repo
- imported ISO titles still pass through deterministic no-copy portalization
- `Dante's Inferno` still reaches `TITLE_RUNNING_HEADLESS` on both connected devices
- visible Dante frames are confirmed on both connected devices through `xenia_fb`
- the app launches into a product shell and plays titles in a dedicated fullscreen player

### Verified per-device outcome

`AYN Odin2 Mini`:

- AUTO branch resolves to `mesa26`
- Turnip probe passes
- Xenia bring-up reaches `VULKAN_INITIALIZED`
- `Dante's Inferno` reaches `TITLE_RUNNING_HEADLESS`
- visible Dante frames confirmed in the fullscreen player

`Odin3`:

- AUTO branch resolves to `mesa26`
- Turnip probe passes
- `KgslPropertiesInstrumentedTest` confirms `ubwc_mode = 5`
- Xenia bring-up reaches `VULKAN_INITIALIZED`
- `Dante's Inferno` reaches `TITLE_RUNNING_HEADLESS`
- visible Dante frames confirmed in the fullscreen player

Note:

- the visible-frame milestone is currently confirmed primarily by direct device observation in the player UI
- the stricter black-frame automation used during Phase 5A can still report a false negative if it samples too early while the stream is warming up

## Next milestone: Phase 6

### Goal

Move from visible passive playback to usable interaction while keeping the validated FEX, Turnip, title-boot, and visible-frame baseline green on both devices.

### Scope

- keep the current FEX and Turnip regression matrix intact
- keep the stable no-copy title launch path intact
- keep the visible framebuffer-polling player path intact
- recover input and audio only after the visible path stays stable

### Pass criteria

- the Vulkan probe still passes with `lavapipe`
- the Turnip probe still passes on both devices
- Xenia still reaches `VULKAN_INITIALIZED`
- `Dante's Inferno` still reaches `TITLE_RUNNING_HEADLESS`
- visible output remains real and diagnosable
- later failures, if any, are inside interaction/audio recovery rather than FEX, Turnip, title boot, or first-frame presentation

## Main risks from here

- the historical repo mixed multiple graphics experiments at once, so later recovery work must stay single-threaded by subsystem
- even after visible presentation works, input and audio can still expose separate timing, sync, and lifecycle issues
- preserving exact artifact provenance matters now because the stack is finally crossing from synthetic probes into emulator startup
