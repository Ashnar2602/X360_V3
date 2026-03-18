`ubuntu-24.04-amd64-lvp.lock.json` is the pinned Phase 3A guest userspace lock manifest.

It defines:

- the exact `Ubuntu 24.04 amd64` package URLs and SHA-256 hashes used for the generated guest runtime
- the curated file slice extracted from each package
- the active Vulkan ICD baseline for this milestone: `lavapipe`

The generated runtime intentionally extracts only the minimum declared slice needed for:

- glibc dynamic loader bring-up
- dynamic guest ELF execution under FEX
- guest `libvulkan.so.1`
- guest `libvulkan_lvp.so`
- `vkCreateInstance` plus physical-device enumeration

It is not intended to be a general-purpose distro rootfs.
