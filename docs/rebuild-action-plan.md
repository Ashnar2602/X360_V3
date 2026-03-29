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
- persist title-content metadata and progression/freeze diagnostics

## Active operational baseline

### Stable product-side choices right now

- app package: `emu.x360.mobile.dev`
- default player backend: `FRAMEBUFFER_POLLING`
- alternate backend still present for research/debug: `FRAMEBUFFER_SHARED_MEMORY`
- Xenia source pin: `553aedebb59340d3106cd979ca7d09cc8e3bd98e`
- Xenia patch set: `phase11a-upstreamfirst-v1`

### Why polling is the default again

The shared-memory path is still implemented, but the current stable live-player baseline has been moved back to polling while progression work continues. That is intentional and should be reflected in any future debugging.

### Important recent fixes and shifts

- the polling exporter regression that froze after the first frame is fixed in the packaged runtime
- the active Xenia guest has been rebased to the desktop-working `553...` Canary family
- the XAM layer is now upstream-first by default:
  - `xam_media` baseline is upstream
  - `xam_net` baseline is upstream
  - `xlivebase_app` baseline is upstream
  - `xam_content` keeps only the minimum Android/product delta for DLC visibility and diagnostics

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
- `third_party/xenia-patches/<patchSetId>/`

`patchSetId` now drives the patch-directory lookup. The build is no longer hardwired to `third_party/xenia-patches/phase4`.

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

### Current practical note on long Xenia rebuilds

The guest rebuild can genuinely take a long time after a source-pin jump, especially after rebasing to a newer upstream revision. A `generateDebugXeniaBringupAssets` run is only "stuck" if the guest build has already produced:

- `xenia-build-result.txt`
- `build/bin/Linux/Release/xenia_canary`
- `build/bin/Linux/Release/xenia-content-tool`

and the remaining process tree is only wrapper/watcher noise.

If those markers are still missing and `clang++` / `ninja` are active inside WSL, the guest rebuild is still real work.

### Current practical validation points

After a successful build, verify:

- `app/build/generated/xeniaBringupAssets/debug/payload/config/xenia-source-lock.json`
- `app/build/generated/xeniaBringupAssets/debug/payload/config/xenia-build-metadata.json`
- `app/build/generated/xeniaBringupAssets/debug/rootfs/opt/x360-v3/xenia/bin/xenia-canary`
- `app/build/generated/xeniaBringupAssets/debug/rootfs/opt/x360-v3/xenia/bin/xenia-content-tool`

For the current Phase 11A baseline, the payload should report:

- `sourceRevision = 553aedebb59340d3106cd979ca7d09cc8e3bd98e`
- `patchSetId = phase11a-upstreamfirst-v1`
- `executableSha256 = 6ae5bc7384ca5b59cac2b03035dcf26b9d3f39e5d6efe788c6795859601a5526`

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
- patch DB match/apply state
- movie/audio loading diagnostics
- freeze-lab evidence and desktop-parity comparisons

Important current note:

- Phase 11A intentionally removed the default synthetic offline behavior previously added in `xam_net` and `xlivebase_app`
- any future offline fallback should be explicit and launch-controlled, not silently buried inside XAM behavior

## Guardrails for future work

1. Use `emu.x360.mobile.dev` only.
2. Prefer low-RAM Gradle invocations with `--no-daemon --max-workers=1`.
3. Do not assume `filesDir` edits are durable if the APK payload is still older.
4. Do not delete `_tmp*` evidence casually.
5. Keep Android platform deltas explicit; do not hide them inside silent XAM semantic rewrites.
6. Prefer small, isolated changes and verify with real device logs after each one.
