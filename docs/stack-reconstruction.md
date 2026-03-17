# Stack Reconstruction

## Confirmed from memory

These details come directly from prior hands-on work and should be treated as the strongest evidence available right now.

- Test devices were [Ayn Odin 2](https://www.ayntec.com/products/odin-2) and [Ayn Odin 2 Mini](https://www.ayntec.com/products/odin2-mini), both with Snapdragon 8 Gen 2 and Adreno 740.
- Both devices had 12 GB RAM.
- At least `Dante's Inferno` and `Forza Horizon` booted and were playable enough to validate rendering and partial input.
- Performance was around 25-30 FPS with synchronous callbacks still present.
- FEX version used was remembered as `2601`, not the newer `2603`.
- FEX was integrated directly into the runtime flow and translated Linux `x86_64` ELF to ARM64.
- Winlator was not part of the stack.
- Xenia Canary likely ran in a headless or offscreen GDK path.
- Turnip was compiled as `x86_64` and used directly in the app path.
- One early graphics issue caused output to freeze after some time, likely tied to KGSL pressure or insufficient frame/resource retirement.

## Most likely runtime pipeline

The highest-confidence reconstruction is:

`Xenia Canary Linux x86_64`
`-> custom headless/offscreen presentation path`
`-> Vulkan loader + Turnip x86_64 userspace`
`-> KGSL kernel interface`
`-> Adreno 740`
`-> Android app-owned display path`

CPU execution path:

`Xbox 360 guest PPC`
`-> Xenia guest translation and emulation`
`-> host code emitted/managed for x86_64 build`
`-> FEX 2601`
`-> ARM64 host execution`

## Why this pipeline fits the evidence

- No Winlator implies this was not a `Wine + Box64/Box86` style deployment.
- FEX is designed around Linux `x86`/`x86_64` userspace execution on `AArch64` hosts, which matches the remembered direct ELF translation path.
- A headless or offscreen presentation path explains how the stack could avoid X11/Wayland entirely.
- A custom presentation path also explains why KGSL saturation or stuck output could occur: without normal swapchain backpressure, frames and resources can pile up if retirement is not explicit.
- Compiling Turnip as `x86_64` strongly suggests the guest Vulkan userspace stack existed inside the translated environment instead of relying entirely on host-side Vulkan thunk forwarding.

## Strong inferences

These are not directly remembered as facts, but they are the most plausible explanation of the behavior described.

- The working prototype probably used a small custom Linux userspace rather than a general-purpose root filesystem.
- The Android app likely owned the real window/display surface, while Xenia rendered to offscreen images or frontbuffer-like resources.
- The freeze was likely a queue depth, fence, timeline, or resource lifetime problem rather than a fundamental inability to issue Vulkan work.
- The missing async callback work could have removed a major CPU-side stall source, which aligns with the expected performance uplift remembered.

## Plausible integration points by component

### Xenia Canary

- Linux `x86_64` build
- Vulkan backend
- custom presentation or frontbuffer extraction path
- headless/offscreen mode instead of standard desktop WSI
- reduced dependency on GTK/X11/Wayland during the rendering path

### FEX 2601

- Linux ELF `x86_64` loader path
- JIT translation from `x86_64` to `ARM64`
- signal and memory mapping behavior compatible with Xenia's runtime needs
- enough library/runtime compatibility to support the Vulkan userspace path

### Mesa/Turnip

- `x86_64` userspace build bundled with the app
- Turnip selected as Vulkan driver
- KGSL as kernel-facing path for Adreno
- loader/ICD arrangement compatible with translated `x86_64` userspace

### Android host app

- orchestrates startup
- exposes or owns the visible display path
- may shuttle frames or images from Xenia's offscreen output to the Android surface
- likely manages packaging and lookup of translated userspace assets

## Main unknowns we still need to pin down

- how much of Mesa was truly guest-side `x86_64` versus host-side bridging
- whether a full RootFS existed or only a compact curated userspace
- the exact presentation handoff from Xenia output to Android display
- whether SDL, EGL, or a custom display path was involved at all
- how Vulkan loader selection and ICD discovery were wired
- which Xenia branch or commit family was used when the prototype worked
- whether any Android-specific libc or linker accommodations were required for FEX 2601

## Signals that we are rebuilding the right architecture

- a translated Linux `x86_64` binary boots under FEX without Winlator
- Vulkan instance/device creation succeeds through the packaged path
- offscreen rendering works before any visible presentation path exists
- queue submission works repeatedly without the old KGSL freeze pattern
- the same games that previously worked reach equivalent or earlier milestones
