# X360 Mobile / Xenia Canary Android via FEX

This repository contains:

- the reconstruction notes for the historical Android + FEX + Xenia stack
- the current Android wrapper app `emu.x360.mobile.dev`
- the runtime/toolchain needed to build, stage, install, and debug the guest stack end to end

Current implemented path:

`Android app shell -> FEX host on Android arm64 -> Ubuntu 24.04 amd64 guest slice -> guest Vulkan loader -> dual Mesa guest trees -> Turnip or lavapipe -> pinned Xenia Canary -> no-copy ISO library -> descriptor-backed title portal -> visible fullscreen player -> controller input bridge -> patch DB + content tooling + progression diagnostics`

Current practical product baseline:

- package name: `emu.x360.mobile.dev`
- default player backend: `FRAMEBUFFER_POLLING`
- alternate backend kept in the codebase for comparison/debug: `FRAMEBUFFER_SHARED_MEMORY`
- current priority problem: progression stalls during some in-game transitions after boot/render/input are already alive

## Current repo status

Implemented in this workspace today:

- Android wrapper app with splash, library-first shell, options flow, debug screen, and fullscreen player
- `runtime-core` manifest/install/metadata contracts
- `native-bridge` JNI/CMake layer for Android-specific process and FD plumbing
- deterministic runtime payload staging under `filesDir`
- split logs under `app`, `fex`, and `guest`
- vendored `third_party/FEX` submodule pinned to `49a37c7d6fec7d94923507e5ce10d55c2920e380`
- repo-owned FEX Android patch queue under `third_party/fex-patches/android`
- generated Ubuntu 24.04 `amd64` guest runtime slice from a lock manifest
- generated dual Mesa guest trees:
  - `mesa25` from `7f1ccad77883be68e7750ab30b99b16df02e679d`
  - `mesa26` from `44669146808b74024b9befeb59266db18ae5e165`
- repo-owned Mesa patch queue under `third_party/mesa-patches`
- pinned-source Xenia build pipeline from `canary_experimental@c50b036178108f87cb0acaf3691a7c3caf07820f`
- repo-owned Xenia patch queue under `third_party/xenia-patches/phase4`
- official `xenia-canary/game-patches` snapshot bundled into the runtime
- persistent incremental Xenia dev workspace plus clean full-build fallback
- no-copy ISO library persisted under `filesDir/library`
- descriptor-backed title portal under `rootfs/mnt/library/<entry-id>.iso`
- controller input bridge from Android controller events into headless Xenia
- content-package UI/pipeline for title-specific XContent containers
- progression diagnostics bundle and stall classification for live player sessions

What is currently verified:

- hardware Turnip probe passes on:
  - `AYN Odin2 Mini`
  - `Odin3`
- Xenia bring-up reaches `VULKAN_INITIALIZED`
- `Dante's Inferno` boots from ISO, renders visible frames, and accepts controller input on both validated devices
- the official patch DB is loaded in runtime
- the polling export regression that previously froze after the first frame is fixed in the live APK payload

What is still open:

- progression stalls after some loading transitions are not fully resolved yet
- audio is still deferred
- direct surface bridge remains deferred
- the shared-memory player path remains in the repo, but the current stable default is polling

## Active source pins

- FEX:
  - `49a37c7d6fec7d94923507e5ce10d55c2920e380`
  - patch set: `android-baseline-v1`
- Mesa:
  - `mesa25`: `7f1ccad77883be68e7750ab30b99b16df02e679d`
  - `mesa26`: `44669146808b74024b9befeb59266db18ae5e165`
  - Mesa patch set: `ubwc5-a830-v1`
- Xenia:
  - `canary_experimental@c50b036178108f87cb0acaf3691a7c3caf07820f`
  - patch set: `phase10c-triage-v2`

## Repository layout

- `app/`
  - Android UI shell
  - player session orchestration
  - runtime install/launch logic
  - device-facing diagnostics and debug flows
- `runtime-core/`
  - manifest, install, metadata, directories, codec, and runtime contracts
- `native-bridge/`
  - JNI/CMake glue for Android-native hooks and process helpers
- `buildSrc/`
  - FEX prep/build tasks
  - guest-runtime assembly
  - Mesa build/staging tasks
  - Xenia source build/staging tasks
  - runtime payload overlay planner
- `fixtures/guest-runtime/`
  - Ubuntu guest-runtime lock data
- `fixtures/mesa-runtime/`
  - Mesa source lock data
- `fixtures/xenia-runtime/`
  - Xenia source lock and game-patches lock
- `third_party/FEX/`
  - pinned FEX submodule
- `third_party/fex-patches/android/`
  - repo-owned FEX Android patches
- `third_party/mesa-patches/`
  - repo-owned Mesa patches
- `third_party/xenia-patches/phase4/`
  - repo-owned Xenia patch queue
- `docs/`
  - reconstruction notes, plans, playbooks, and developer hand-off
- `artifacts/`
  - archived successful milestone outputs

## Runtime payload flow

Debug APK payload assembly is layered in this order:

1. `app/src/main/mock-runtime`
2. generated FEX guest assets
3. generated Vulkan guest assets
4. generated Turnip guest assets
5. generated Xenia assets
6. optional `_local/runtime-drop/` overlay for debug builds only

The APK carries `assets/runtime-payload/...`.

At first launch or runtime refresh, the app installs those assets under its own `filesDir`, verifies checksums from `runtime-manifest.json`, and writes an install marker/fingerprint. If the runtime payload changes, the installer will refresh the runtime automatically.

Important consequence:

- patching `filesDir` by hand on device is temporary
- if the runtime payload inside the APK still contains the old binary, the app can reinstall the old one on the next refresh
- for durable fixes, update the payload that the APK packages

## Current validated devices

- `AYN Odin2 Mini`
- `Odin3`

Known package to use on devices:

- `emu.x360.mobile.dev`

Do not use other historical package names. The active app for this workspace ends with `.dev`.

## Local runtime override

For debug builds only, local runtime overrides can be placed under:

- `_local/runtime-drop/`

This is the safest way to inject a custom runtime artifact into the APK without permanently editing source pins or generated assets.

## Quick start

Low-RAM safe build commands used in this workspace:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :runtime-core:test :app:testDebugUnitTest
.\gradlew.bat --no-daemon --max-workers=1 :app:assembleDebug :app:installDebug :app:installDebugAndroidTest
```

If the Xenia asset generator is healthy:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:generateDebugXeniaBringupAssets
```

Connected tests:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:connectedDebugAndroidTest
```

## Key docs

- [docs/bringup-plan.md](docs/bringup-plan.md)
- [docs/rebuild-action-plan.md](docs/rebuild-action-plan.md)
- [docs/version-strategy.md](docs/version-strategy.md)
- [docs/device-recovery-playbook.md](docs/device-recovery-playbook.md)
- [docs/developer-handoff.md](docs/developer-handoff.md)

## Working tree note

This workspace usually contains many `_tmp*` files with screenshots, framebuffer dumps, logs, and diagnostics. They are intentionally not committed. Do not mass-delete them casually unless they have already been archived elsewhere.
