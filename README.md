# Xenia Canary Android via FEX Research

This repository now contains both:

- the reconstruction notes for the historical stack
- a working Android wrapper project that has completed Phase 3B bring-up

Current implemented bring-up:

`Android app shell -> FEX host on Android arm64 -> Ubuntu 24.04 amd64 guest slice -> dynamic Linux x86_64 probes -> guest Vulkan loader -> dual Mesa guest trees -> lavapipe fallback + Turnip hardware probe`

Target end-state remains:

`Android app shell -> FEX -> Linux x86_64 guest userspace -> Xenia Canary -> Vulkan -> Turnip -> KGSL -> Adreno`

The goal is still not to port Xenia natively to Android/ARM64 first. The goal is to rebuild the Linux `x86_64` guest flow on Android and recover the stack step by step.

## Current repo status

Implemented in this repo today:

- Android wrapper app with Compose UI
- `runtime-core` module for manifest/install/launch contracts
- `native-bridge` JNI/CMake stub reserved for future FEX and `ANativeWindow` hooks
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
- real `FexGuestLauncher` with stable config/env/log contracts
- `RuntimePhase.TURNIP_BASELINE` as the current default target
- passing unit tests plus connected Android tests on:
  - `AYN Odin2 Mini` / Android 13 / API 33
  - `Odin3` / Android 15 / API 35
- verified hardware Turnip probe on `AYN Odin2 Mini`
- verified hardware Turnip probe on `Odin3` after the real Mesa-side UBWC 5/6 fix in `mesa26`
- manual `run-as` verification for:
  - `mesa25` and `mesa26` ICD trees
  - `fex_vulkan_probe.json`
  - staged Mesa runtime metadata with patch provenance

Still deferred to later milestones:

- guest thunks and Vulkan forwarding
- Xenia packaging
- visible frame presentation

## Tooling direction

The wrapper uses modern Android tooling:

- Android Gradle Plugin: `9.1.0`
- Gradle wrapper: `9.3.1`
- `compileSdk` / `targetSdk`: `35`
- `minSdk`: `33`

The current FEX baseline is built in-repo with:

- Android NDK: `27.2.12479018`
- CMake: `3.22.1`
- ABI scope: `arm64-v8a` only

Why NDK `27` and not the old historical pin:

- the original research suggested `r25b`
- the real FEX bring-up at commit `49a37c7` required Android fixes that are materially easier and more reliable with the currently installed modern NDK
- the repo keeps the historical FEX commit pinned, but uses a modern Android build host unless an older toolchain is required by evidence

The local machine currently builds with JDK `17`, which is the supported AGP baseline available here.

## Repository layout

- `app/`: Android wrapper app, Compose UI, runtime orchestration, FEX launcher
- `runtime-core/`: runtime manifest, installer, directories, build metadata, launch contracts
- `native-bridge/`: JNI/CMake stub for future native hooks
- `buildSrc/`: generated asset pipeline, FEX source prep, Ubuntu guest-runtime assembly, FEX build and staging tasks
- `fixtures/guest-tests/`: checked-in guest probes plus source
- `fixtures/guest-runtime/`: Ubuntu 24.04 lock manifest and notes for the generated guest runtime slice
- `fixtures/mesa-runtime/`: Mesa source lock manifest for dual-Mesa Turnip bundles
- `third_party/FEX/`: pinned FEX submodule
- `third_party/fex-patches/android/`: repo-owned Android patch queue for the pinned FEX baseline
- `third_party/mesa-patches/`: repo-owned Mesa patch queues applied during guest Turnip asset generation
- `docs/`: reconstruction notes, forensics, recovery playbooks, bring-up plans

Key docs:

- [docs/stack-reconstruction.md](docs/stack-reconstruction.md)
- [docs/bringup-plan.md](docs/bringup-plan.md)
- [docs/rebuild-action-plan.md](docs/rebuild-action-plan.md)
- [docs/forensics-360-test-1be7a16.md](docs/forensics-360-test-1be7a16.md)
- [docs/device-recovery-playbook.md](docs/device-recovery-playbook.md)
- [docs/version-strategy.md](docs/version-strategy.md)

## Runtime contract

The current wrapper fixes these paths under `filesDir`:

- `rootfs/`
- `rootfs/lib64/ld-linux-x86-64.so.2`
- `rootfs/lib/x86_64-linux-gnu/`
- `rootfs/usr/lib/x86_64-linux-gnu/libvulkan.so.1`
- `rootfs/usr/lib/x86_64-linux-gnu/libvulkan_lvp.so`
- `rootfs/usr/share/vulkan/icd.d/lvp_icd.json`
- `rootfs/opt/x360-v3/mesa/mesa25/lib/libvulkan_freedreno.so`
- `rootfs/opt/x360-v3/mesa/mesa25/icd/turnip_icd.json`
- `rootfs/opt/x360-v3/mesa/mesa26/lib/libvulkan_freedreno.so`
- `rootfs/opt/x360-v3/mesa/mesa26/icd/turnip_icd.json`
- `rootfs/tmp/`
- `rootfs/proc/`
- `rootfs/dev/`
- `rootfs/etc/`
- `.fex-emu/Config.json`
- `payload/bin/`
- `payload/config/fex-build-metadata.json`
- `payload/config/guest-runtime-metadata.json`
- `payload/config/ubuntu-24.04-amd64-lvp.lock.json`
- `payload/config/mesa-runtime-metadata.json`
- `payload/config/mesa-turnip-source-lock.json`
- `payload/guest-tests/bin/hello_x86_64`
- `payload/guest-tests/bin/dyn_hello_x86_64`
- `payload/guest-tests/bin/vulkan_probe_x86_64`
- `logs/app/`
- `logs/fex/`
- `logs/guest/`

Historical placeholders still remain reserved:

- `rootfs/tmp/anative_window.ptr`
- `rootfs/tmp/xenia_fb`

Current sentinel paths on host:

- `<filesDir>/rootfs/tmp/fex_hello_ok.json`
- `<filesDir>/rootfs/tmp/fex_dyn_hello_ok.json`
- `<filesDir>/rootfs/tmp/fex_vulkan_probe.json`

## Local payload overlay

The repo does not track real guest/Xenia/Turnip payloads in Git.

For debug builds only, local runtime overrides can be placed under:

- `_local/runtime-drop/`

That directory is gitignored and overlaid at build time on top of the repo-shipped mock/generated runtime payload. The APK does not read absolute machine-specific paths at runtime.

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

Install on connected devices:

```bash
./gradlew :app:installDebug
```

Launch manually on a connected device:

```bash
adb shell am start -W -n "emu.x360.mobile.dev/.MainActivity"
```

Inspect the verified Vulkan-baseline runtime on-device:

```bash
adb shell run-as emu.x360.mobile.dev ls files/rootfs/lib64/ld-linux-x86-64.so.2
adb shell run-as emu.x360.mobile.dev ls files/rootfs/usr/lib/x86_64-linux-gnu/libvulkan.so.1
adb shell run-as emu.x360.mobile.dev ls files/rootfs/opt/x360-v3/mesa/mesa25/icd/turnip_icd.json
adb shell run-as emu.x360.mobile.dev ls files/rootfs/opt/x360-v3/mesa/mesa26/icd/turnip_icd.json
adb shell run-as emu.x360.mobile.dev cat files/rootfs/tmp/fex_vulkan_probe.json
```

## Verified Phase 3B result

The Phase 3B baseline is now real in this repo:

- FEX host artifacts are built from vendored source inside this repo
- the Android app installs and extracts its runtime deterministically
- `hello_x86_64` still launches through real FEX on Android arm64
- `dyn_hello_x86_64` proves guest dynamic loader and glibc resolution
- `vulkan_probe_x86_64` can now run against:
  - `lavapipe`
  - `mesa25` Turnip guest tree
  - `mesa26` Turnip guest tree
- `app`, `fex`, and `guest` logs are written separately
- repeat launch stays stable in connected tests
- `run-as` confirms both staged Mesa trees and the Vulkan probe sentinel in the app runtime
- `mesa26` carries a repo-owned UBWC fix patch set, `ubwc5-a830-v1`, that teaches the KGSL Turnip path to accept UBWC `5.0` and `6.0` while deferring the final bank-swizzle/macrotile values to Mesa's existing `fd_dev_info` database for A8xx/A830

What this milestone does not prove yet:

- thunk plumbing
- Xenia startup
- frame presentation

Current verified hardware result on the baseline pass device:

- device: `AYN Odin2 Mini`
- AUTO branch: `mesa25`
- sentinel:
  - `marker = X360_VK_PROBE_OK`
  - `driver_mode = turnip`
  - `instance_ok = true`
  - `device_count = 1`
  - `device_name = "Turnip Adreno (TM) 740"`

Current verified hardware result on the secondary device:

- device: `Odin3`
- AUTO branch: `mesa26`
- connected test and log artifacts now confirm:
  - `KgslPropertiesInstrumentedTest` sees `ubwc_mode = 5`
  - `launchTurnipProbeUsesHardwarePathAndSupportsRepeatLaunch` passes
  - `instance_ok = true`
  - `device_count >= 1`
  - `device_name` is a real Turnip hardware device, not `llvmpipe`

The important change for Odin3 is not a wrapper-side workaround. The fix lives in the repo-owned `mesa26` patch queue and makes the KGSL Turnip path treat UBWC `5.0` and `6.0` as valid while letting Mesa's A8xx/A830 device database fill the real UBWC config.

That means:

- `AYN Odin2 Mini` remains the Phase 3B baseline pass device
- `Odin3` now passes the same Turnip hardware probe milestone and is no longer stuck on the old diagnostic path

## Historical context

The strongest remembered baseline remains:

- devices: `Ayn Odin 2`, `Ayn Odin 2 Mini`
- SoC: `Snapdragon 8 Gen 2 / Adreno 740`
- RAM: `12 GB`
- known working titles: `Dante's Inferno`, `Forza Horizon`
- FEX family: `2601`
- Xenia base: `canary_experimental@d9747704b`
- graphics direction: headless/offscreen Linux `x86_64` guest flow on Android

The research and forensics docs remain the source of truth for the later Turnip and Xenia milestones.
