# Rebuild Action Plan

## Current status

This repo is a real Android wrapper project, not a docs-only reconstruction.

Today it can:

- build and package a real FEX host baseline from vendored source
- install a deterministic runtime payload under `filesDir`
- launch Linux `x86_64` guest processes on Android arm64
- stage dual Mesa guest trees and route devices branch-aware
- build and package pinned-source Linux `x86_64` Xenia Canary
- boot titles from no-copy ISO references
- render visible fullscreen frames in the product player
- pass controller input into headless Xenia
- load the official global game patch DB
- persist title-content metadata and progression diagnostics

## Active operational baseline

### Stable product-side choices right now

- app package: `emu.x360.mobile.dev`
- default player backend: `FRAMEBUFFER_POLLING`
- alternate backend still present for research/debug: `FRAMEBUFFER_SHARED_MEMORY`
- Xenia source pin: `c50b036178108f87cb0acaf3691a7c3caf07820f`
- Xenia patch set: `phase10c-triage-v2`

### Why polling is the default again

The shared-memory path is still implemented, but the current stable live-player baseline has been moved back to polling while progression work continues. That is intentional and should be reflected in any future debugging.

### Important recent fix

The polling exporter previously froze after the first frame because the guest-side export dedupe logic incorrectly treated later frames as duplicates.

That symptom is now fixed in the active runtime payload:

- `export_duplicate_skip` no longer dominates guest logs
- `FRAME_STREAM_ACTIVE` is reached again on both validated devices
- `exportFrameCount` and `lastFrameIndex` advance in app logs

## Runtime payload model

The APK packages `assets/runtime-payload/...`.

The app installer:

1. reads `runtime-manifest.json`
2. filters assets by runtime phase
3. copies them into `filesDir`
4. verifies per-file checksums
5. writes an install marker with a manifest fingerprint

Operational consequence:

- editing the runtime under `filesDir` is not durable by itself
- if the APK payload still contains old bits, the app can later reinstall them
- durable fixes must reach the packaged runtime payload

## Generated asset pipeline

The runtime overlay order is:

1. `app/src/main/mock-runtime`
2. generated FEX guest assets
3. generated Vulkan guest assets
4. generated Turnip guest assets
5. generated Xenia assets
6. optional `_local/runtime-drop/` overlay for debug builds only

Key tasks:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :runtime-core:test :app:testDebugUnitTest
.\gradlew.bat --no-daemon --max-workers=1 :app:assembleDebug :app:installDebug :app:installDebugAndroidTest
.\gradlew.bat --no-daemon --max-workers=1 :app:generateDebugXeniaBringupAssets
```

## Xenia build workflow

### Normal path

`GenerateXeniaBringupAssetsTask` uses:

- `fixtures/xenia-runtime/xenia-source-lock.json`
- `fixtures/xenia-runtime/xenia-game-patches-lock.json`
- `third_party/xenia-patches/phase4`

It clones/prepares a workspace under:

- `app/build/xeniaDevWorkspaces/debug/<workspace-key>/checkout`

Then it builds and stages the guest runtime under:

- `app/build/generated/xeniaBringupAssets/debug/`

### Persistent incremental workspaces

Debug builds default to incremental mode. The workspace key depends on:

- source revision
- patch set
- build profile
- relevant build defines / environment

This is the preferred path for day-to-day Xenia iteration.

### Current known pitfall

Fresh `generateDebugXeniaBringupAssets` can currently fail in a newly prepared workspace because of unrelated Xenia build issues, including:

- submodule oddities in a fresh checkout
- shader-generation failure around `guest_output_ffx_cas_resample.ps.xesl`

This does not invalidate the whole repo. It means the "clean new Xenia workspace" path is currently less reliable than the already-proven persistent workspace path.

### Current practical workaround

If a known-good incremental Xenia workspace already exists and contains the wanted binary:

1. rebuild there incrementally
2. inject that binary into the runtime payload used by the APK
3. rebuild/install the app without rerunning the failing Xenia generator

The safer long-lived version of this workflow is:

- place the override under `_local/runtime-drop/`

The short-term emergency version is:

- patch the already-generated `app/build/generated/runtimePayload/...` payload and its manifest checksums

## Device workflow

### Install/update app

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:assembleDebug :app:installDebug :app:installDebugAndroidTest
```

### Force-stop and relaunch

```powershell
adb -s <serial> shell am force-stop emu.x360.mobile.dev
adb -s <serial> shell monkey -p emu.x360.mobile.dev -c android.intent.category.LAUNCHER 1
```

### Read logs from app-private storage

```powershell
adb -s <serial> exec-out run-as emu.x360.mobile.dev sh -c "ls -1t /data/user/0/emu.x360.mobile.dev/files/logs/guest | sed -n 1p"
adb -s <serial> exec-out run-as emu.x360.mobile.dev cat /data/user/0/emu.x360.mobile.dev/files/logs/app/<session>.app.log
adb -s <serial> exec-out run-as emu.x360.mobile.dev cat /data/user/0/emu.x360.mobile.dev/files/logs/guest/<session>.guest.log
adb -s <serial> exec-out run-as emu.x360.mobile.dev cat /data/user/0/emu.x360.mobile.dev/files/logs/fex/<session>.fex.log
```

### Verify installed Xenia binary hash

```powershell
adb -s <serial> exec-out run-as emu.x360.mobile.dev sha256sum /data/user/0/emu.x360.mobile.dev/files/rootfs/opt/x360-v3/xenia/bin/xenia-canary
```

## Current investigation focus

The next real work item is progression recovery, not initial rendering.

Areas already instrumented and worth checking first:

- content root resolution
- profile/save root resolution
- XAM content APIs
- XLive/XNet offline stubs
- patch DB match/apply state
- live player session diagnostics bundle

## Guardrails for future work

1. Use `emu.x360.mobile.dev` only.
2. Prefer low-RAM Gradle invocations with `--no-daemon --max-workers=1`.
3. Do not assume `filesDir` edits are durable if the APK payload is still older.
4. Do not delete `_tmp*` evidence casually.
5. Prefer small, isolated changes and verify with real device logs after each one.
