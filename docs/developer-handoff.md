# Developer Hand-Off

## Purpose

This document is for a new developer picking up the workspace and needing to understand:

- what this repo actually does
- how the runtime is built and installed
- where Xenia, FEX, Mesa, and the Android glue live
- how to make changes safely
- how to debug real device issues without fighting the wrong layer

## First facts to know

- active app package: `emu.x360.mobile.dev`
- active repo root in this workspace: `c:\\Progetti\\Ibrido\\X360_V3\\X360_V3`
- active date context for this hand-off: March 25, 2026
- current stable player default: `FRAMEBUFFER_POLLING`
- shared-memory player path still exists, but it is not the current default baseline

Do not accidentally work on another historical package name. Earlier mistakes came from patching/building the wrong app on device.

## High-level architecture

The app is not a native ARM64 Xenia port.

It is:

1. Android app shell
2. FEX host on Android arm64
3. Linux `x86_64` guest runtime slice
4. guest Vulkan/Mesa selection
5. Linux `x86_64` Xenia Canary inside the guest runtime
6. Android-owned player/presentation/input shell around it

## Module map

### `app/`

Main Android app module.

Responsibilities:

- UI shell
- library management
- player session lifecycle
- runtime install/refresh
- launch contracts
- device logs and diagnostics
- instrumented tests

Important files/directories to know:

- `app/src/main/kotlin/emu/x360/mobile/dev/`
- `app/src/main/kotlin/emu/x360/mobile/dev/bootstrap/`
- `app/src/androidTest/kotlin/emu/x360/mobile/dev/`

### `runtime-core/`

Pure Kotlin contracts and runtime model layer.

Responsibilities:

- runtime manifest codec
- runtime installer
- directories model
- metadata models
- game library models
- patch DB/content/progression diagnostic models

### `native-bridge/`

JNI/CMake layer for Android-native helpers used by the app.

Use this when:

- you need FD/process plumbing that Kotlin alone cannot do safely
- you need a small native helper for launch/session integration

### `buildSrc/`

This is the build pipeline heart of the repo.

Responsibilities:

- FEX source prep/build
- Mesa asset generation
- Xenia asset generation
- runtime payload staging
- incremental workspace management

If you need to understand where the APK assets come from, start here.

## Source pins and patch queues

### FEX

- source lives in `third_party/FEX/`
- pinned to `49a37c7d6fec7d94923507e5ce10d55c2920e380`
- patch queue: `third_party/fex-patches/android/`

### Mesa

- lock data: `fixtures/mesa-runtime/`
- repo patches: `third_party/mesa-patches/`
- active patch set in practice: `ubwc5-a830-v1`

### Xenia

- source lock: `fixtures/xenia-runtime/xenia-source-lock.json`
- official game patch DB lock: `fixtures/xenia-runtime/xenia-game-patches-lock.json`
- repo-owned Xenia source patches: `third_party/xenia-patches/phase4/`
- current Xenia patch set: `phase10c-triage-v2`

## Runtime payload lifecycle

### What gets packaged

The APK contains:

- `assets/runtime-payload/runtime-manifest.json`
- `assets/runtime-payload/files/...`

That payload includes FEX, guest runtime bits, Mesa trees, Xenia, config, metadata, and helper binaries.

### How it gets installed

At runtime the app:

1. reads the manifest
2. filters assets by target runtime phase
3. copies them under app-owned `filesDir`
4. verifies checksums
5. stores a manifest fingerprint in an install marker

### Why this matters

If you patch a binary directly inside `filesDir` on the device:

- that can help for a quick experiment
- but it is not a durable source-of-truth change

If the APK payload still contains the old asset, the runtime can later reinstall the old one.

For durable fixes, update the packaged payload.

## Runtime overlay order

For debug builds, the staged payload is assembled in this order:

1. `app/src/main/mock-runtime`
2. generated FEX assets
3. generated Vulkan guest assets
4. generated Turnip assets
5. generated Xenia assets
6. optional `_local/runtime-drop/`

If you need to override one runtime file safely in a debug build, `_local/runtime-drop/` is the intended mechanism.

## Build commands you will actually use

Use low-RAM flags unless you have a reason not to:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :runtime-core:test :app:testDebugUnitTest
.\gradlew.bat --no-daemon --max-workers=1 :app:assembleDebug :app:installDebug :app:installDebugAndroidTest
```

If you specifically need Xenia runtime regeneration:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:generateDebugXeniaBringupAssets
```

If you want the full connected suite:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:connectedDebugAndroidTest
```

## Xenia incremental workspace model

Debug Xenia builds use a persistent workspace cache under:

- `app/build/xeniaDevWorkspaces/debug/<workspace-key>/checkout`

This is intentional. It avoids redoing a full checkout/setup on every iteration.

### Good workflow

1. make a small patch change in `third_party/xenia-patches/phase4`
2. rebuild Xenia assets if the generator is healthy
3. otherwise use the existing known-good incremental workspace
4. verify the rebuilt `xenia_canary`
5. make sure the APK payload actually packages that binary

## Known generator pitfall

Fresh `:app:generateDebugXeniaBringupAssets` is not always reliable today.

Observed failure classes include:

- missing/odd submodule state in a fresh Xenia workspace
- shader compile failure around `guest_output_ffx_cas_resample.ps.xesl`

This does not mean the repo is broken. It means the current fresh-workspace Xenia path is less reliable than the already-proven incremental workspace path.

### Current safe workaround

When you already have a known-good rebuilt `xenia_canary`:

1. inject it into the debug runtime payload
2. update payload metadata/checksums if needed
3. rebuild/install the APK without forcing a fresh Xenia generator run

The clean way to do that long-term is `_local/runtime-drop/`.

## Device workflow

### Install and relaunch

```powershell
adb -s <serial> shell am force-stop emu.x360.mobile.dev
adb -s <serial> shell monkey -p emu.x360.mobile.dev -c android.intent.category.LAUNCHER 1
```

### Check what Xenia binary is actually installed

```powershell
adb -s <serial> exec-out run-as emu.x360.mobile.dev sha256sum /data/user/0/emu.x360.mobile.dev/files/rootfs/opt/x360-v3/xenia/bin/xenia-canary
```

### Read latest logs

```powershell
adb -s <serial> exec-out run-as emu.x360.mobile.dev sh -c "ls -1t /data/user/0/emu.x360.mobile.dev/files/logs/app | sed -n 1p"
adb -s <serial> exec-out run-as emu.x360.mobile.dev sh -c "ls -1t /data/user/0/emu.x360.mobile.dev/files/logs/guest | sed -n 1p"
adb -s <serial> exec-out run-as emu.x360.mobile.dev sh -c "ls -1t /data/user/0/emu.x360.mobile.dev/files/logs/fex | sed -n 1p"
```

Then `cat` the session log you care about.

### Typical serials in this workspace

Recent mapping has been:

- `944cf170` = Odin2 Mini
- `df9a023b` = Odin3

Treat that as a convenience, not a permanent law. Always confirm with `adb devices`.

## How to debug by layer

### App/UI layer

Look here when:

- menus are wrong
- player state is wrong
- settings are not persisted
- overlays or pause menu misbehave

Main places:

- `PlayerActivity.kt`
- `PlayerSessionController.kt`
- `MainActivity.kt`
- `UserPreferencesStore.kt`

### Runtime orchestration layer

Look here when:

- the wrong backend launches
- the wrong paths/env vars/args are passed
- runtime install/refresh behavior is odd
- logs/diagnostics bundles look incomplete

Main places:

- `AppRuntimeManager.kt`
- `XeniaBringupModel.kt`
- `PresentationPerformanceSupport.kt`
- `ProgressionDiagnosticsSupport.kt`

### Xenia guest layer

Look here when:

- the emulator is alive but behavior inside the title is wrong
- content/profile/XAM/XLive/XNet issues appear
- frame export behavior is suspicious

Main places:

- `third_party/xenia-patches/phase4/`
- guest logs under `files/logs/guest`
- Xenia source workspace under `app/build/xeniaDevWorkspaces/.../checkout`

### FEX / host layer

Look here when:

- guest process dies before meaningful Xenia logs
- syscall issues or Android seccomp issues appear
- process launch or FD passing is wrong

Main places:

- `files/logs/fex`
- `native-bridge/`
- FEX patch queue and source

## Current verified state

Today the stack can:

- render visible Dante frames on both validated devices
- accept controller input
- load the official patch DB
- create session-scoped progression diagnostics

## Current blocker

The main open problem is progression after boot/render/input.

The rule for future work is:

- do not assume every freeze is graphics
- do not assume every freeze is guest-side either
- use the live player diagnostics bundle and logs first

Recent example:

- a major freeze class turned out to be polling exporter dedupe incorrectly freezing after frame 1
- that was a display/export problem, not a guest crash

## Known pitfalls

1. Working on the wrong Android package.
2. Assuming device `filesDir` edits are durable.
3. Letting Gradle use too much RAM.
4. Deleting `_tmp*` diagnostics casually.
5. Making multiple stack-layer changes at once and losing attribution.

## Recommended first steps for a new developer

1. Read [README.md](/c:/Progetti/Ibrido/X360_V3/X360_V3/README.md).
2. Read [docs/bringup-plan.md](/c:/Progetti/Ibrido/X360_V3/X360_V3/docs/bringup-plan.md).
3. Read [docs/rebuild-action-plan.md](/c:/Progetti/Ibrido/X360_V3/X360_V3/docs/rebuild-action-plan.md).
4. Build/install with the low-RAM commands.
5. Verify the installed `xenia-canary` hash on device.
6. Reproduce on a real device before changing code.
7. Change one subsystem at a time and re-check `app`, `guest`, and `fex` logs.
