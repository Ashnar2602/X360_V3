# Device Recovery Playbook

## Purpose

This document describes how to recover as much useful material as possible from a still-working Android device that can run the historical Xenia Canary + FEX + Vulkan stack.

The goal is not only to save the APK. The goal is to preserve the actual runtime truth of the project:

- packaged binaries
- extracted rootfs
- Mesa or Turnip userspace pieces that may never have been committed
- FEX configuration
- runtime caches
- logs
- filesystem layout and symlink structure

If a working build still exists on a device, that device is more valuable than the incomplete historical repository.

## Objectives

### Primary objectives

- recover the exact APK that was installed
- recover the app data directory
- recover the extracted `rootfs`
- recover all Vulkan ICD JSON files, shared libraries, shims, and symlinks
- recover FEX config and runtime artifacts
- recover logs and caches that reveal the real runtime path

### Secondary objectives

- fingerprint exact versions of FEX, Xenia, Mesa, and helper libraries
- identify packaging steps that were manual and never committed
- preserve enough material to reconstruct a working project from scratch

## Constraints

### Do not disturb the working setup before extraction

- do not update the app
- do not reinstall the app
- do not clear app data
- do not run cleanup tools
- do not let the package manager optimize, migrate, or replace files if it can be avoided
- do not rename files inside the app data directory

### Preserve filesystem semantics

- prefer `tar` archives over zip files
- preserve symlinks, timestamps, and directory layout
- avoid copying files one by one unless no better option exists

### Preserve both static and runtime state

- the APK alone is not enough
- the rootfs alone is not enough
- logs and config files may explain missing packaging steps

## Recovery Priority

### Minimum salvage set

- installed APK
- entire app private data directory
- extracted rootfs
- external app files directory if it exists
- a logcat session captured while launching a known-working title

### Full forensic set

- everything above
- package metadata from `dumpsys package`
- device metadata from `getprop`
- file inventories
- symlink inventories
- SHA-256 hashes of recovered archives
- screenshots or video proving the build still works

## Expected Package Name

The historical project used:

- `emu.x360.mobile`

If that package name changed on the working device, discover the actual name first.

Suggested checks:

```bash
adb shell pm list packages | grep -i x360
adb shell pm path emu.x360.mobile
```

## Before Touching Anything

Record the environment first.

Suggested commands:

```bash
adb devices
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.hardware
adb shell getprop ro.board.platform
adb shell getprop ro.boot.hardware.sku
adb shell dumpsys SurfaceFlinger | head -100
adb shell pm path emu.x360.mobile
adb shell dumpsys package emu.x360.mobile > dumpsys-package.txt
```

Also note manually:

- device model
- whether the device is rooted
- Android version
- whether the build currently launches and renders correctly
- which game still works

## Best-Case Extraction Path

The best case is:

- the app is debuggable and `run-as` works

If `run-as` works, we can preserve private app data without root.

Test:

```bash
adb shell run-as emu.x360.mobile id
```

If that succeeds, use the `run-as` method below.

If it fails, use:

- root shell if available
- otherwise recover at least the APK, external files, and whatever can be exported manually

## Artifacts To Recover

### 1. APK and split APKs

Recover the installed package exactly as it exists on the device.

Suggested commands:

```bash
adb shell pm path emu.x360.mobile
```

For each returned `package:` path:

```bash
adb pull /data/app/.../base.apk
adb pull /data/app/.../split_config....apk
```

Also record:

```bash
adb shell dumpsys package emu.x360.mobile > dumpsys-package.txt
```

### 2. Private app data

Priority locations:

- `/data/user/0/emu.x360.mobile/files`
- `/data/user/0/emu.x360.mobile/cache`
- `/data/user/0/emu.x360.mobile/no_backup`
- `/data/user/0/emu.x360.mobile/code_cache`

If `run-as` works, prefer a single tar stream:

```bash
adb exec-out run-as emu.x360.mobile sh -c 'cd /data/user/0/emu.x360.mobile && tar -cpf - files cache no_backup code_cache 2>/dev/null' > emu.x360.mobile-private.tar
```

If root is available:

```bash
adb shell su -c 'cd /data/user/0/emu.x360.mobile && tar -cpf /sdcard/emu.x360.mobile-private.tar files cache no_backup code_cache'
adb pull /sdcard/emu.x360.mobile-private.tar
```

### 3. Extracted rootfs

This is one of the most important artifacts.

Likely paths:

- `/data/user/0/emu.x360.mobile/files/rootfs`
- `/data/user/0/emu.x360.mobile/files/rootfs/tmp`
- `/data/user/0/emu.x360.mobile/files/rootfs/usr/lib/x86_64-linux-gnu`
- `/data/user/0/emu.x360.mobile/files/rootfs/usr/share/vulkan/icd.d`

If `run-as` works:

```bash
adb exec-out run-as emu.x360.mobile sh -c 'cd /data/user/0/emu.x360.mobile/files && tar -cpf - rootfs 2>/dev/null' > rootfs.tar
```

If root is available:

```bash
adb shell su -c 'cd /data/user/0/emu.x360.mobile/files && tar -cpf /sdcard/rootfs.tar rootfs'
adb pull /sdcard/rootfs.tar
```

### 4. Unextracted packaged assets

We also want any original packaged assets that were copied to `files/` on first run.

Look for:

- `xenia-canary`
- `rootfs.tar.gz`
- `rootfs.tar.xz`
- `rootfs.tar.gz.bin`
- `real_libs.tar`
- `turnip_icd.json`
- `xcb_bridge_icd.json`
- config files

Suggested inventory:

```bash
adb shell run-as emu.x360.mobile sh -c 'cd /data/user/0/emu.x360.mobile/files && find . -maxdepth 3 -type f | sort'
```

### 5. External app storage

If the app used external storage, also recover:

- `/sdcard/Android/data/emu.x360.mobile/files`
- `/sdcard/Android/media/emu.x360.mobile`

Suggested commands:

```bash
adb pull /sdcard/Android/data/emu.x360.mobile
adb pull /sdcard/Android/media/emu.x360.mobile
```

### 6. Runtime logs

Capture logcat during a known-good launch.

Suggested commands:

```bash
adb logcat -c
adb logcat > working-session-logcat.txt
```

Then:

- launch the app
- initialize the emulator
- launch a known working title
- let it run for at least 1-3 minutes
- stop logcat

Priority tags and strings to look for later:

- `FEX`
- `Xenia`
- `VkShim`
- `XCB_BRIDGE`
- `Turnip`
- `TU_DEBUG`
- `vkCreateXcbSurfaceKHR`
- `anative_window.ptr`
- `/tmp/xenia_fb`

### 7. Directory and symlink inventories

We need a textual record in addition to the archive.

Suggested commands:

```bash
adb shell run-as emu.x360.mobile sh -c 'cd /data/user/0/emu.x360.mobile/files/rootfs && find . -printf \"%M %u %g %s %TY-%Tm-%Td %TH:%TM %p -> %l\\n\" | sort' > rootfs-inventory.txt
adb shell run-as emu.x360.mobile sh -c 'cd /data/user/0/emu.x360.mobile/files && find . -printf \"%M %u %g %s %TY-%Tm-%Td %TH:%TM %p -> %l\\n\" | sort' > files-inventory.txt
```

If `find -printf` is unavailable on-device, use plain `find` and `ls -lR`:

```bash
adb shell run-as emu.x360.mobile sh -c 'cd /data/user/0/emu.x360.mobile/files/rootfs && ls -lR' > rootfs-ls.txt
adb shell run-as emu.x360.mobile sh -c 'cd /data/user/0/emu.x360.mobile/files && ls -lR' > files-ls.txt
```

### 8. Checksums

Generate hashes after pulling files to the workstation.

Suggested commands:

```bash
sha256sum *.apk *.tar *.txt > SHA256SUMS.txt
```

## Critical Files To Look For

These are especially valuable:

- `rootfs/usr/lib/x86_64-linux-gnu/libvulkan_freedreno.so`
- `rootfs/usr/lib/x86_64-linux-gnu/libvulkan_xcb_bridge.so`
- `rootfs/usr/share/vulkan/icd.d/turnip_icd.json`
- `rootfs/usr/share/vulkan/icd.d/xcb_bridge_icd.json`
- `rootfs/tmp/anative_window.ptr`
- `rootfs/tmp/xenia_fb`
- `.config/fex-emu/Config.json`
- any `libvulkan-guest.so`
- any `GuestThunks` tree
- any manually copied Mesa or LLVM libraries

If those files are present on the working device, they may close the biggest gap left by the historical repo.

## Practical Command Sets

### Option A: `run-as` works

```bash
adb shell pm path emu.x360.mobile > pm-path.txt
adb shell dumpsys package emu.x360.mobile > dumpsys-package.txt
adb exec-out run-as emu.x360.mobile sh -c 'cd /data/user/0/emu.x360.mobile && tar -cpf - files cache no_backup code_cache 2>/dev/null' > emu.x360.mobile-private.tar
adb shell run-as emu.x360.mobile sh -c 'cd /data/user/0/emu.x360.mobile/files && ls -lR' > files-ls.txt
adb shell run-as emu.x360.mobile sh -c 'cd /data/user/0/emu.x360.mobile/files/rootfs && ls -lR' > rootfs-ls.txt
adb logcat -d > initial-logcat.txt
```

### Option B: rooted device

```bash
adb shell su -c 'tar -cpf /sdcard/emu.x360.mobile-private.tar -C /data/user/0/emu.x360.mobile files cache no_backup code_cache'
adb pull /sdcard/emu.x360.mobile-private.tar
adb shell su -c 'ls -lR /data/user/0/emu.x360.mobile/files > /sdcard/files-ls.txt'
adb shell su -c 'ls -lR /data/user/0/emu.x360.mobile/files/rootfs > /sdcard/rootfs-ls.txt'
adb pull /sdcard/files-ls.txt
adb pull /sdcard/rootfs-ls.txt
```

## Manual Evidence Worth Capturing

- screenshot of title screen or in-game frame
- short video showing the app still rendering correctly
- screenshot of app version/about screen if present
- screenshot of device info page

This helps later when correlating binary artifacts with observed behavior.

## Things That Must Not Be Forgotten

- preserve the extracted rootfs exactly as it exists
- preserve symlinks
- preserve both APK and private files
- preserve logs from a real working launch
- preserve any file named like:
  - `rootfs.tar.*`
  - `real_libs.tar`
  - `turnip_icd.json`
  - `xcb_bridge_icd.json`
  - `xenia-canary`
  - `Config.json`

## Recovery Success Criteria

The recovery is considered successful if we obtain:

- the installed APK
- a tarball of private app data
- a tarball or full copy of the extracted rootfs
- a file inventory
- a working-session logcat
- enough material to verify whether the missing Turnip userspace and Vulkan bridge files were present on-device

## Why This Matters

The historical repository proved the architecture, but it did not preserve every runtime artifact needed to reproduce the working state.

A functioning device can still contain:

- the exact missing Turnip userspace
- symlink arrangements
- config files
- post-install runtime mutations
- caches or logs that explain the final working behavior

That makes device recovery the highest-value next forensic step.
