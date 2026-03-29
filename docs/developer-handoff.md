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
- active date context for this hand-off: March 29, 2026
- current stable player default: `FRAMEBUFFER_POLLING`
- shared-memory player path still exists, but it is not the current default baseline
- active Xenia source revision: `553aedebb59340d3106cd979ca7d09cc8e3bd98e`
- active Xenia patch set: `phase11a-upstreamfirst-v1`

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
- freeze-lab and debug tooling
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
- patch DB/content/progression/freeze diagnostic models

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
- repo-owned Xenia source patches live under `third_party/xenia-patches/<patchSetId>/`
- current Xenia patch set: `phase11a-upstreamfirst-v1`

### What changed in Phase 11A

The Android/Linux guest was rebased from the older `c50b...` family to the desktop-working Canary family:

- `553aedebb59340d3106cd979ca7d09cc8e3bd98e`

The important design shift is:

- Android platform/runtime differences stay
- XAM semantics move back to upstream-first by default

In practice:

- `xam_media` is now upstream-first
- `xam_net` is now upstream-first
- `apps/xlivebase_app` is now upstream-first
- `xam_content` keeps only the minimum Android/product delta for:
  - DLC visibility policy
  - content-root integration
  - diagnostics/tracing

This matters because older local XAM behavior was a real source of drift versus the Windows desktop build that already works with Dante.

### Current Phase 11A patch-set composition

The active queue is intentionally split by purpose, even though Git stores it as a flat patch series.

Retained in the active queue:

- Android/headless presentation support
- `FRAMEBUFFER_POLLING` and `FRAMEBUFFER_SHARED_MEMORY` guest plumbing
- `android_shared_memory` HID support
- Linux/posix runtime fixes needed by the Android guest
- content-tool packaging and runtime integration
- content-root aliases and title-content support
- diagnostics-only tracing for progression, freeze lab, VFS, and movie/audio work
- minimal `X360_MARKETPLACE_CONTENT_POLICY` support

Intentionally not carried forward as default behavior:

- old synthetic offline `xam_net` behavior
- old synthetic offline `xlivebase` behavior
- earlier custom `XamContentOpenFile` semantic changes
- older "just make it continue" XAM/content hacks that were useful for triage but too divergent from desktop parity

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

1. make a small patch change in `third_party/xenia-patches/<patchSetId>`
2. rebuild Xenia assets if the generator is healthy
3. otherwise use the existing known-good incremental workspace
4. verify the rebuilt `xenia_canary`
5. make sure the APK payload actually packages that binary

### Patch-queue layering rule

When you change Xenia behavior, classify the delta first:

- `Platform-required Android change`
- `Diagnostics-only`
- `Behavior change still required`
- `Hack to delete`

The current Phase 11A queue was built with exactly that rule.

The intended long-term baseline is:

- keep Android-specific presentation/input/runtime deltas
- keep diagnostics
- avoid silent behavior drift in XAM unless a regression is proven and documented

## Known generator pitfall

Fresh `:app:generateDebugXeniaBringupAssets` is not always "hung" just because it runs a long time.

After the Phase 11A rebase, the full guest rebuild can take a long time legitimately. Check what is really happening:

- if WSL still has active `clang++`, `ninja`, or `cmake --build`, the rebuild is still real work
- if the workspace has already produced:
  - `xenia-build-result.txt`
  - `build/bin/Linux/Release/xenia_canary`
  - `build/bin/Linux/Release/xenia-content-tool`
  and only wrapper/watchers remain, the guest build is effectively done

### Current safe validation points

The generated payload should expose:

- `app/build/generated/xeniaBringupAssets/debug/payload/config/xenia-source-lock.json`
- `app/build/generated/xeniaBringupAssets/debug/payload/config/xenia-build-metadata.json`

For the current baseline, expect:

- `sourceRevision = 553aedebb59340d3106cd979ca7d09cc8e3bd98e`
- `patchSetId = phase11a-upstreamfirst-v1`
- `xenia-canary` SHA-256 `6ae5bc7384ca5b59cac2b03035dcf26b9d3f39e5d6efe788c6795859601a5526`

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
- `UiModels.kt`

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
- content/profile/XAM/media/XLive/XNet issues appear
- frame export behavior is suspicious

Main places:

- `third_party/xenia-patches/<patchSetId>/`
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
- create session-scoped progression diagnostics and freeze-lab evidence bundles
- package and install the rebased `553...` Xenia guest on both validated devices

## Current blocker

The main open problem is progression after boot/render/input.

The rule for future work is:

- do not assume every freeze is graphics
- do not assume every freeze is guest-side either
- use live player diagnostics, freeze-lab bundles, and desktop parity traces first

Current Phase 11A interpretation:

- old-pin drift and silent XAM semantic divergence are no longer the leading suspects
- the remaining blocker is likely deeper in loading/cutscene/media or guest-environment parity

## Known pitfalls

1. Working on the wrong Android package.
2. Assuming device `filesDir` edits are durable.
3. Letting Gradle use too much RAM.
4. Mistaking a real long guest rebuild for a stuck wrapper.
5. Deleting `_tmp*` diagnostics casually.
6. Making multiple stack-layer changes at once and losing attribution.

## Recommended first steps for a new developer

1. Read [README.md](/c:/Progetti/Ibrido/X360_V3/X360_V3/README.md).
2. Read [docs/bringup-plan.md](/c:/Progetti/Ibrido/X360_V3/X360_V3/docs/bringup-plan.md).
3. Read [docs/rebuild-action-plan.md](/c:/Progetti/Ibrido/X360_V3/X360_V3/docs/rebuild-action-plan.md).
4. Verify `fixtures/xenia-runtime/xenia-source-lock.json` before assuming the active guest revision.
5. Build/install with the low-RAM commands.
6. Verify the installed `xenia-canary` hash on device.
7. Reproduce on a real device before changing code.
8. Change one subsystem at a time and re-check `app`, `guest`, and `fex` logs.
