# Forensics: 360_Test @ 1be7a167

This note captures what can be stated with high confidence from the historical repository:

- Repository: `Ashnar2602/360_Test`
- Commit: `1be7a167496d038ed4cd180f0532e085c1a40c44`
- Commit message: `MILESTONE: Turnip GPU active -- video output working at 4.6fps avg (60fps burst)`

## Executive Summary

The commit confirms that the project was real and technically sophisticated, but it also shows that the repo at this checkpoint was still in a partially manual and transitional state.

The strongest conclusions are:

- FEX was not hypothetical. The bundled build identifies itself as `FEX-2601-181-g49a37c7`.
- Xenia was a Linux `x86_64` binary based on `canary_experimental@d9747704b`.
- The project used a custom Android app wrapper around FEX and Xenia.
- The visible frame path at this checkpoint was still based on polling `/rootfs/tmp/xenia_fb`, not a final direct swapchain presentation path.
- The GPU milestone came mostly from runtime and Vulkan bridge changes, not from a newly updated Xenia binary.
- The tracked repo does not include a fully self-contained packaged Turnip `x86_64` userspace, which strongly suggests some manual or external packaging step existed outside this commit.

## Confirmed Facts

## FEX

- `app/src/main/jniLibs/arm64-v8a/libFEXCore.so` is an ARM64 Android shared object.
- `app/src/main/jniLibs/arm64-v8a/libFEXLoader.so` is an ARM64 Android PIE executable.
- Embedded strings and logs identify the build as:
  - `FEX-2601-181-g49a37c7`
  - commit `49a37c7`
- `scripts/fex-cmake.log` confirms:
  - `FEX Version: FEX-2601-181-g49a37c7`
  - `FEX Commit: 49a37c7`
- `readelf` notes show Android NDK `r25b` / `8937393`.
- Local patch scripts show FEX needed Android-specific adjustments:
  - `scripts/patch_cmake.py`
  - `scripts/patch_sve.py`
  - `scripts/fix_fex_atomics.py`

## Xenia

- `app/src/main/assets/xenia-canary` is:
  - ELF 64-bit
  - Linux `x86_64`
  - dynamically linked
  - interpreter `/lib64/ld-linux-x86-64.so.2`
- Embedded strings identify the upstream base as:
  - `canary_experimental@d9747704b on Feb 23 2026`
  - `https://github.com/xenia-canary/xenia-canary/commit/d9747704bedc4e691ba243bf399647b836ce493e`
- The binary still depends on desktop Linux libraries:
  - `libgtk-3.so.0`
  - `libgdk-3.so.0`
  - `libX11.so.6`
  - `libxcb.so.1`
  - `libX11-xcb.so.1`
  - `libSDL2-2.0.so.0`
- The binary also contains strings for:
  - `--headless`
  - `gpu = vulkan`
  - framebuffer capture to `/tmp/xenia_fb`
  - `IssueSwap: frame captured {}x{} -> /tmp/xenia_fb`

## Android wrapper

- JNI extracts:
  - rootfs archive
  - `xenia-canary`
  - Xenia config
  - `real_libs.tar`
- The wrapper writes an `ANativeWindow*` pointer to:
  - `{filesDir}/rootfs/tmp/anative_window.ptr`
- The wrapper configures FEX/Xenia runtime with:
  - `GDK_BACKEND=offscreen`
  - `SDL_VIDEODRIVER=dummy`
  - `SDL_AUDIODRIVER=dummy`
  - `DBUS_SESSION_BUS_ADDRESS=unix:path=/nonexistent-dbus-socket`
  - `NO_AT_BRIDGE=1`
  - `FEX_DISABLESANDBOX=1`

## Frame output path

- `EmulatorViewModel.kt` polls:
  - `{filesDir}/rootfs/tmp/xenia_fb`
- Poll interval is `200 ms`.
- The file is parsed as:
  - width
  - height
  - raw pixel buffer
- The visible UI frame is drawn as a Compose `Image` overlay.

This means the working video output at this checkpoint was still based on headless capture and file polling.

## The Most Important Architectural Insight

The project was not using a single simple path. It had at least three graphics ideas in flight at the same time:

1. a headless frame capture path via `/tmp/xenia_fb`
2. a planned or partial `xcb -> android_surface` Vulkan bridge path
3. a Turnip ICD selection path intended to move away from CPU-rendered `lvp`

That explains why the repo can look internally inconsistent while still reflecting something that worked in practice.

## What Changed In This Milestone

Compared with the earlier CPU-rendered milestone `fe5afd7`, this commit adds:

- `turnip_icd.json`
- `xcb_bridge_icd.json`
- `libvulkan_shim.c`
- `libxcb_stub.c`
- `vulkan_xcb_bridge.c`
- `libvulkan-host.so`
- symlink logic for `libvulkan.so.1 -> libvulkan_shim.so`
- symlink logic for `libxcb.so.1 -> libxcb_stub.so`
- environment switch:
  - from `lvp_icd.json`
  - to `turnip_icd.json`

It also removes the comment about `FEX_THUNKHOSTLIBS` being needed and instead notes:

- `FEX_THUNKHOSTLIBS` removed
- `Turnip` intended to be used directly

## Why This Matters

`app/src/main/assets/xenia-canary` was last changed at `fe5afd7`, not at `1be7a16`.

That means the jump to the Turnip milestone was driven primarily by wrapper/runtime/Vulkan path changes, not by a new Xenia build in this specific commit.

## The Strongest Contradiction In The Repo

The repo tracks `turnip_icd.json` with:

- `/usr/lib/x86_64-linux-gnu/libvulkan_freedreno.so`

However:

- `rootfs.tar.xz` in this commit does not contain `libvulkan_freedreno.so`
- `real_libs.tar` does not contain it either
- the tracked `mesa-vulkan-drivers_25.2.8-0ubuntu0.24.04.1_amd64.deb` does not contain `libvulkan_freedreno.so`

This is the single strongest sign that the actually working Turnip userspace was provided by an external or manual packaging step not fully captured in Git at this checkpoint.

## What The Mesa Artifact Tells Us

The repo contains:

- `mesa-vulkan-drivers_25.2.8-0ubuntu0.24.04.1_amd64.deb`

But inspecting it shows only drivers such as:

- `libvulkan_lvp.so`
- `libvulkan_radeon.so`
- `libvulkan_intel.so`
- `libvulkan_nouveau.so`

and not `libvulkan_freedreno.so`.

This strongly supports your memory that the useful Turnip path was not coming from a stock amd64 distro package and had to be compiled or sourced separately.

## What The Vulkan Bridge Files Prove

### `libvulkan_shim.c`

- ARM64 Android shim
- intercepts `vkCreateXcbSurfaceKHR`
- converts it to `vkCreateAndroidSurfaceKHR`
- reads `ANativeWindow` pointer from file
- tries direct hardware ICD first:
  - `/vendor/lib64/hw/vulkan.adreno.so`
  - `/vendor/lib64/hw/vulkan.pastel.so`
  - fallback `/system/lib64/libvulkan.so`

### `libxcb_stub.c`

- ARM64 stub for `libxcb.so.1`
- satisfies FEX host Vulkan thunk expectations
- prevents `X11Manager` from aborting due to missing X server

### `vulkan_xcb_bridge.c`

- intended as an `x86_64` guest-side Vulkan bridge
- injects `VK_KHR_xcb_surface`
- maps it toward Android surface creation through the thunk chain
- expects `libvulkan-guest.so` from FEX guest thunks

## Important Interpretation

These files prove that you had already identified the correct conceptual solution to the desktop-X11 problem:

- keep Xenia thinking in terms of `xcb`
- translate surface creation into Android-native presentation
- fake just enough X11/XCB presence to keep the stack alive

## Why The Repo Still Looks Incomplete

At this commit, the actual visible UI still uses framebuffer polling from `/tmp/xenia_fb`, and the tracked assets do not fully include the Turnip guest userspace needed by `turnip_icd.json`.

The most likely explanation is:

- the repo captured the working direction
- some runtime packaging steps were manual or local-only
- the real device state was ahead of what this commit fully automated

## Reconstructed Timeline From Git History

- `4a467e6`
  - app scaffold built
  - FEX 2601 integration underway
  - rootfs and real library packaging entering the project
- `5559571`
  - Xenia confirmed running in background under FEX
  - no correct Vulkan video output yet
- `fe5afd7`
  - video output visible
  - explicitly described as still CPU-rendered
  - this is the last commit that changed the bundled `xenia-canary` asset
- `d4a0159`
  - initial `vulkan_xcb_bridge` and `libvulkan-host.so` recovery
- `1be7a16`
  - switch from `lvp` to `turnip`
  - add ARM64 shim and XCB stub
  - milestone claims GPU active with low average output rate

## Practical Implications For Reconstruction

### Things we now know with much more certainty

- baseline FEX should be tied to `49a37c7` rather than only "some 2601"
- the old prototype used a Linux `x86_64` Xenia binary with GTK/X11 dependencies still present
- `headless/offscreen` meant runtime suppression plus capture/bridge logic, not a dependency-free Xenia build
- the repo milestone still relied on framebuffer capture polling, so direct present was likely not finished here
- the real Turnip userspace bundle was not fully preserved in tracked files

### Things that likely existed outside the repo

- a manually built or separately packaged `x86_64` Turnip/Freedreno userspace
- installation logic for:
  - `libvulkan_freedreno.so`
  - matching ICD JSON placement
  - possibly `libvulkan_xcb_bridge.so`
- additional runtime assembly steps on-device or before APK packaging

## Best Next Use Of This Evidence

For reconstruction, this commit should be treated as:

- a high-value architecture snapshot
- a partial packaging snapshot
- not yet a fully reproducible source-of-truth build

It is still extremely valuable because it tells us where the real complexity lived:

- FEX Android enablement
- Xenia runtime suppression and frame capture
- Vulkan surface translation
- missing Turnip userspace packaging
