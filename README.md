# Xenia Canary Android via FEX Research

This repository now contains both:

- the reconstruction notes for the historical stack
- a working Android wrapper project that has completed Phase 6A universal Android display bring-up

Current implemented bring-up:

`Android app shell -> FEX host on Android arm64 -> Ubuntu 24.04 amd64 guest slice -> dynamic Linux x86_64 probes -> guest Vulkan loader -> dual Mesa guest trees -> lavapipe fallback + Turnip hardware probe -> pinned-source Xenia Canary -> no-copy ISO library -> descriptor-backed title portal -> steady-state headless title run -> shared-memory frame transport -> visible Android frames -> fullscreen player shell`

Target end-state remains:

`Android app shell -> FEX -> Linux x86_64 guest userspace -> Xenia Canary -> Vulkan -> Turnip -> KGSL -> Adreno -> stable visible presentation -> input/audio -> usable player UX`

The goal is still not to port Xenia natively to Android/ARM64 first. The goal is to rebuild the Linux `x86_64` guest flow on Android and recover the stack step by step.

## Current repo status

Implemented in this repo today:

- Android wrapper app with Compose UI
- `runtime-core` module for manifest, install, metadata, and launch contracts
- `native-bridge` JNI/CMake layer reserved for future FEX and `ANativeWindow` hooks
- deterministic runtime payload staging under `filesDir`
- split logging for `app`, `fex`, and `guest`
- vendored `third_party/FEX` submodule pinned to `49a37c7d6fec7d94923507e5ce10d55c2920e380`
- repo-owned Android patch queue under `third_party/fex-patches/android`
- generated FEX host artifacts packaged as APK native libs
- generated Ubuntu 24.04 `amd64` guest runtime slice from a repo-owned lock manifest
- generated dual-Mesa guest Turnip bundles built from pinned Mesa source snapshots in WSL:
  - `mesa25`: `25.3` branch snapshot `7f1ccad77883be68e7750ab30b99b16df02e679d`
  - `mesa26`: `main` snapshot `44669146808b74024b9befeb59266db18ae5e165`
- repo-owned Mesa patch queue under `third_party/mesa-patches`:
  - `mesa26`: `ubwc5-a830-v1`
- checked-in guest fixtures for:
  - static `hello_x86_64`
  - dynamic `dyn_hello_x86_64`
  - `vulkan_probe_x86_64`
- pinned-source Xenia build pipeline from `canary_experimental@c50b036178108f87cb0acaf3691a7c3caf07820f`
- repo-owned Xenia patch queue under `third_party/xenia-patches/phase4`
- persistent incremental Xenia dev workspace for local rebuilds, with clean full-build fallback
- generated Xenia guest runtime slice with:
  - `rootfs/opt/x360-v3/xenia/bin/xenia-canary`
  - `xenia-canary.config.toml`
  - `portable.txt`
  - `logs/`
  - `cache/`
- no-copy ISO game library persisted under `filesDir/library`
- descriptor-backed title portal used by the normal library launch path, independent from whether the ISO lives on internal storage or SD
- verified title-aware launch path for imported ISOs through `rootfs/mnt/library/<entry-id>.iso`
- current Xenia patch set `phase6a-shared-frame-v2`
- `RuntimePhase.XENIA_BRINGUP` as the current default target
- passing unit tests plus connected Android tests on:
  - `AYN Odin2 Mini` / Android 13 / API 33
  - `Odin3` / Android 15 / API 35
- verified hardware Turnip probe on both devices
- verified `Xenia Canary` bring-up to Vulkan initialization on both devices without requiring a game image
- verified `Dante's Inferno` title boot from ISO to `TITLE_RUNNING_HEADLESS` on both devices
- default player backend `FRAMEBUFFER_SHARED_MEMORY`
- debug/regression fallback backend `FRAMEBUFFER_POLLING`
- visible Dante frame output confirmed on both devices through the shared-memory player path
- shared-memory player color path corrected by removing the Android-side `R/B` swap
- shared-memory player launch forced to `readback_resolve=full` for correctness across both validated devices
- product-facing app shell with:
  - branded splash
  - library-first home
  - options flow
  - debug screen behind options
  - fullscreen `PlayerActivity`
  - optional FPS overlay
  - in-player pause menu opened by Android back
  - quick in-player options overlay for live-safe toggles such as the FPS counter

Still deferred to later milestones:

- input, audio, and gameplay validation
- direct surface bridge recovery (`ANativeWindow`, `xcb/android_surface`)
- thunk optimization beyond the minimum runtime already proven

## Tooling direction

The wrapper uses modern Android tooling:

- Android Gradle Plugin: `9.1.0`
- Gradle wrapper: `9.3.1`
- `compileSdk` / `targetSdk`: `35`
- `minSdk`: `33`
- local JDK baseline: `17`

The current FEX baseline is built in-repo with:

- Android NDK: `27.2.12479018`
- CMake: `3.22.1`
- ABI scope: `arm64-v8a` only

Why NDK `27` and not the old historical pin:

- the original research suggested `r25b`
- the real FEX bring-up at commit `49a37c7` required Android fixes that are materially easier and more reliable with the currently installed modern NDK
- the repo keeps the historical FEX commit pinned, but uses a modern Android build host unless an older toolchain is required by evidence

## Active source pins

- FEX baseline:
  - `49a37c7d6fec7d94923507e5ce10d55c2920e380`
  - patch set: `android-baseline-v1`
- Mesa bundles:
  - `mesa25`: `7f1ccad77883be68e7750ab30b99b16df02e679d`
  - `mesa26`: `44669146808b74024b9befeb59266db18ae5e165`
  - Odin3 fix patch set: `ubwc5-a830-v1`
- Active Xenia bring-up pin:
  - `canary_experimental@c50b036178108f87cb0acaf3691a7c3caf07820f`
- patch set: `phase6a-shared-frame-v2`
- Historical Xenia forensic reference:
  - `canary_experimental@d9747704bedc4e691ba243bf399647b836ce493e`
  - kept as a historical anchor, not as the current build target

## Repository layout

- `app/`: Android wrapper app, Compose UI, runtime orchestration, FEX launcher, Xenia launcher
- `runtime-core/`: runtime manifest, installer, directories, build metadata, launch contracts
- `native-bridge/`: JNI/CMake stub for future native hooks
- `buildSrc/`: generated asset pipeline, FEX source prep, Ubuntu guest-runtime assembly, Mesa build/staging tasks, Xenia source build/staging tasks
- `fixtures/guest-tests/`: checked-in guest probes plus source
- `fixtures/guest-runtime/`: Ubuntu 24.04 lock manifest and notes for the generated guest runtime slice
- `fixtures/mesa-runtime/`: Mesa source lock manifest for dual-Mesa Turnip bundles
- `fixtures/xenia-runtime/`: Xenia source lock manifest for the current Phase 4 bring-up
- `third_party/FEX/`: pinned FEX submodule
- `third_party/fex-patches/android/`: repo-owned Android patch queue for the pinned FEX baseline
- `third_party/mesa-patches/`: repo-owned Mesa patch queues applied during guest Turnip asset generation
- `third_party/xenia-patches/phase4/`: repo-owned Xenia bring-up patch queue
- `artifacts/phase4a-vulkan-init/`: archived binary outputs and hashes for the current successful Phase 4A baseline
- `docs/`: reconstruction notes, forensics, recovery playbooks, bring-up plans

Key docs:

- [docs/stack-reconstruction.md](docs/stack-reconstruction.md)
- [docs/bringup-plan.md](docs/bringup-plan.md)
- [docs/rebuild-action-plan.md](docs/rebuild-action-plan.md)
- [docs/forensics-360-test-1be7a16.md](docs/forensics-360-test-1be7a16.md)
- [docs/device-recovery-playbook.md](docs/device-recovery-playbook.md)
- [docs/version-strategy.md](docs/version-strategy.md)
- [docs/sources.md](docs/sources.md)

## Runtime contract

The current wrapper fixes these paths under `filesDir`:

- `rootfs/lib64/ld-linux-x86-64.so.2`
- `rootfs/lib/x86_64-linux-gnu/`
- `rootfs/usr/lib/x86_64-linux-gnu/libvulkan.so.1`
- `rootfs/usr/lib/x86_64-linux-gnu/libvulkan_lvp.so`
- `rootfs/usr/share/vulkan/icd.d/lvp_icd.json`
- `rootfs/opt/x360-v3/mesa/mesa25/lib/libvulkan_freedreno.so`
- `rootfs/opt/x360-v3/mesa/mesa25/icd/turnip_icd.json`
- `rootfs/opt/x360-v3/mesa/mesa26/lib/libvulkan_freedreno.so`
- `rootfs/opt/x360-v3/mesa/mesa26/icd/turnip_icd.json`
- `rootfs/opt/x360-v3/xenia/bin/xenia-canary`
- `rootfs/opt/x360-v3/xenia/bin/xenia-canary.config.toml`
- `rootfs/opt/x360-v3/xenia/bin/portable.txt`
- `rootfs/opt/x360-v3/xenia/bin/logs/`
- `rootfs/opt/x360-v3/xenia/bin/cache/`
- `rootfs/opt/x360-v3/xenia/bin/cache0/`
- `rootfs/opt/x360-v3/xenia/bin/cache1/`
- `rootfs/opt/x360-v3/xenia/bin/scratch/`
- `rootfs/opt/x360-v3/xenia/cache-host/`
- `rootfs/opt/x360-v3/xenia/cache-host/modules/`
- `rootfs/opt/x360-v3/xenia/cache-host/shaders/shareable/`
- `rootfs/opt/x360-v3/xenia/content/`
- `rootfs/mnt/library/`
- `.fex-emu/Config.json`
- `payload/config/fex-build-metadata.json`
- `payload/config/guest-runtime-metadata.json`
- `payload/config/ubuntu-24.04-amd64-lvp.lock.json`
- `payload/config/mesa-runtime-metadata.json`
- `payload/config/mesa-turnip-source-lock.json`
- `payload/config/xenia-source-lock.json`
- `payload/config/xenia-build-metadata.json`
- `library/game-library.json`
- `payload/guest-tests/bin/hello_x86_64`
- `payload/guest-tests/bin/dyn_hello_x86_64`
- `payload/guest-tests/bin/vulkan_probe_x86_64`
- `logs/app/`
- `logs/fex/`
- `logs/guest/`

Reserved future placeholder:

- `rootfs/tmp/anative_window.ptr`

Default shared-memory presentation session path:

- `rootfs/tmp/x360-v3/xenia/presentation/session-<session-id>/frame-transport.bin`

Debug framebuffer export path:

- `rootfs/tmp/xenia_fb`

Current sentinel paths on host:

- `<filesDir>/rootfs/tmp/fex_hello_ok.json`
- `<filesDir>/rootfs/tmp/fex_dyn_hello_ok.json`
- `<filesDir>/rootfs/tmp/fex_vulkan_probe.json`

## Preserved artifacts

The current successful milestone binary outputs are archived in Git under:

- `artifacts/phase4a-vulkan-init/xenia/xenia-canary`
- `artifacts/phase4a-vulkan-init/fex/libFEXLoader.so`
- `artifacts/phase4a-vulkan-init/fex/libFEXCore.so`
- `artifacts/phase4a-vulkan-init/android/app-debug-androidTest.apk`
- `artifacts/phase4a-vulkan-init/artifact-manifest.json`
- `artifacts/phase5b-visible-player/xenia/xenia-canary`
- `artifacts/phase5b-visible-player/fex/libFEXLoader.so`
- `artifacts/phase5b-visible-player/fex/libFEXCore.so`
- `artifacts/phase5b-visible-player/android/app-debug-androidTest.apk`
- `artifacts/phase5b-visible-player/artifact-manifest.json`

Notes:

- `artifact-manifest.json` stores the `sha256` and size for every archived binary
- the main `app-debug.apk` is not committed because it exceeds GitHub's `100 MB` per-file limit
- the exact omitted APK is still fingerprinted in `artifact-manifest.json` so the successful package state is not lost

## Local payload overlay

The repo does not track ad-hoc local runtime drops in Git.

For debug builds only, local runtime overrides can be placed under:

- `_local/runtime-drop/`

That directory is gitignored and overlaid at build time on top of the repo-shipped and generated runtime payload. The APK does not read absolute machine-specific paths at runtime.

## Quick start

Build debug APK:

```bash
./gradlew :app:assembleDebug
```

Run JVM/unit coverage used for the current milestone:

```bash
./gradlew :runtime-core:test :app:testDebugUnitTest
```

Run connected Android tests:

```bash
./gradlew :app:connectedDebugAndroidTest
```

Run the specific Xenia bring-up proof on connected devices:

```bash
adb shell am instrument -w -e class emu.x360.mobile.dev.RuntimeBootstrapInstrumentedTest#launchXeniaBringupReachesVulkanInitialized emu.x360.mobile.dev.test/androidx.test.runner.AndroidJUnitRunner
```

Run the opt-in Dante steady-state smoke on connected devices:

```bash
adb shell am instrument -w -r -e class emu.x360.mobile.dev.DanteSmokeInstrumentedTest -e enable_dante_smoke 1 -e dante_uri_mode file emu.x360.mobile.dev.test/androidx.test.runner.AndroidJUnitRunner
```

Install on connected devices:

```bash
./gradlew :app:installDebug
```

Launch manually on a connected device:

```bash
adb shell am start -W -n "emu.x360.mobile.dev/.MainActivity"
```

Inspect the verified runtime on-device:

```bash
adb shell run-as emu.x360.mobile.dev ls files/rootfs/lib64/ld-linux-x86-64.so.2
adb shell run-as emu.x360.mobile.dev ls files/rootfs/opt/x360-v3/mesa/mesa25/icd/turnip_icd.json
adb shell run-as emu.x360.mobile.dev ls files/rootfs/opt/x360-v3/mesa/mesa26/icd/turnip_icd.json
adb shell run-as emu.x360.mobile.dev ls files/rootfs/opt/x360-v3/xenia/bin/xenia-canary
adb shell run-as emu.x360.mobile.dev cat files/payload/config/xenia-build-metadata.json
```

## Verified Phase 6A result

Phase 6A is now real in this repo:

- FEX host artifacts are built from vendored source inside this repo
- the Android app installs and extracts its runtime deterministically
- `hello_x86_64` still launches through real FEX on Android arm64
- `dyn_hello_x86_64` proves guest dynamic loader and glibc resolution
- `vulkan_probe_x86_64` passes through `lavapipe`, `mesa25`, and `mesa26`
- the repo-owned `mesa26` UBWC fix makes Odin3 pass the same Turnip hardware milestone as Odin2 Mini
- pinned-source `Xenia Canary` is built inside this repo from `c50b036178108f87cb0acaf3691a7c3caf07820f`
- the repo-owned Xenia patch queue removes the GTK hard dependency, switches POSIX shared memory to `memfd_create`, makes headless bring-up null-safe without an ImGui window, and adds both the legacy framebuffer-polling path and the new shared-memory frame transport
- `Launch Xenia Bring-up` reaches `VULKAN_INITIALIZED` on both connected devices without requiring a game image
- imported ISO titles launch through the no-copy library path and reach deterministic title-aware stages
- `Dante's Inferno` reaches `TITLE_RUNNING_HEADLESS` on both connected devices and stays alive for the observation window without crashing
- the normal fullscreen player now uses the shared-memory transport path instead of file polling
- `FRAMEBUFFER_POLLING` remains available only as a debug/regression fallback
- the shared-memory player produces visible Dante frames on both connected devices
- the Odin3 shared-memory path is stabilized by forcing `readback_resolve=full`
- the shared-memory color path is corrected in Android so the visible player no longer depends on an `R/B` swizzle guess
- the app now launches through `SplashActivity`, lands on a library-first shell, and plays titles in a dedicated fullscreen `PlayerActivity`
- pressing Android back inside the player opens a semitransparent pause menu instead of immediately closing the session
- the pause menu can:
  - resume the game
  - open a small in-player options overlay
  - exit to the app home
  - exit Android completely after stopping the running session

Verified per-device bring-up:

- `AYN Odin2 Mini`
  - Mesa branch: `mesa26`
  - device: `Turnip Adreno (TM) 740`
  - Xenia reaches `VULKAN_INITIALIZED`
  - `Dante's Inferno` reaches `TITLE_RUNNING_HEADLESS`
  - visible Dante frames confirmed in the fullscreen player through `FRAMEBUFFER_SHARED_MEMORY`
- `Odin3`
  - Mesa branch: `mesa26`
  - device: `Adreno (TM) 830`
  - Xenia reaches `VULKAN_INITIALIZED`
  - `Dante's Inferno` reaches `TITLE_RUNNING_HEADLESS`
  - visible Dante frames confirmed in the fullscreen player through `FRAMEBUFFER_SHARED_MEMORY`

What Phase 6A does not prove yet:

- input
- audio
- gameplay correctness
- Android surface handoff
- Android 16 / `openat2` seccomp compatibility for FEX on newer third-party devices

Important note:

- connected automation still proves the lower-level launch and framebuffer-stream milestones
- the visible-frame milestone is confirmed by the real fullscreen player path plus targeted player smokes
- `FRAMEBUFFER_POLLING` is no longer the normal user-facing presentation path; it remains a debug fallback only

## Historical context

The strongest remembered baseline remains:

- devices: `Ayn Odin 2`, `Ayn Odin 2 Mini`
- SoC: `Snapdragon 8 Gen 2 / Adreno 740`
- RAM: `12 GB`
- known working titles: `Dante's Inferno`, `Forza Horizon`
- FEX family: `2601`
- historical Xenia base: `canary_experimental@d9747704b`
- graphics direction: headless/offscreen Linux `x86_64` guest flow on Android

The research and forensics docs remain the source of truth for the historical prototype. The active build and validation path in this repo has now moved to a modern pinned `canary_experimental` revision while keeping that historical anchor documented.
