# Xenia Canary Android via FEX Research

This repository documents the reconstruction of a previously working stack:

`Xenia Canary Linux x86_64 -> FEX 2601 -> Mesa/Turnip x86_64 -> KGSL -> Adreno 740`

The goal is not to port Xenia Canary natively to Android/ARM64 first. The goal is to recreate a pipeline where the Linux `x86_64` build of Xenia Canary runs through FEX on Android, without Wine and without a conventional desktop Linux window system.

Current scope:

- document what was previously known to work
- separate confirmed facts from technical inference
- define a bring-up plan to rebuild and test the stack
- identify the likely bottlenecks and integration points

Out of scope for now:

- writing emulator code
- implementing Android glue code
- making upstream claims without local verification

## What is currently remembered

- Devices:
  - Ayn Odin 2
  - Ayn Odin 2 Mini
- SoC:
  - Snapdragon 8 Gen 2
  - Adreno 740
- RAM:
  - 12 GB
- Known working titles:
  - Dante's Inferno
  - Forza Horizon
- Performance observed:
  - roughly 25-30 FPS stable before async callback work
- Runtime path:
  - no Winlator
  - FEX 2601 integrated directly in the app flow
  - Linux `x86_64` ELF translated to ARM64
  - Xenia Canary likely running in a headless/offscreen GDK path
  - Turnip compiled as `x86_64` and bundled in the app flow
- Early failure mode remembered:
  - graphics output could freeze after seconds or minutes, likely due to KGSL saturation or missing backpressure/resource retirement in the present path

## Repository layout

- [docs/stack-reconstruction.md](C:\Users\stall\Documents\Playground\docs\stack-reconstruction.md)
- [docs/risks-benefits.md](C:\Users\stall\Documents\Playground\docs\risks-benefits.md)
- [docs/bringup-plan.md](C:\Users\stall\Documents\Playground\docs\bringup-plan.md)
- [docs/version-strategy.md](C:\Users\stall\Documents\Playground\docs\version-strategy.md)
- [docs/forensics-360-test-1be7a16.md](C:\Users\stall\Documents\Playground\docs\forensics-360-test-1be7a16.md)
- [docs/sources.md](C:\Users\stall\Documents\Playground\docs\sources.md)

## Working assumptions

- The successful prototype was closer to a custom Linux userspace-on-Android flow than to a Wine-based compatibility stack.
- The graphics path likely bypassed X11 and Wayland entirely.
- The most important reconstruction target is the exact integration between Xenia presentation, Vulkan loader/ICD selection, Turnip, and KGSL lifetime management.

## Next outcome this repo should enable

This repo should let us answer, in a reproducible way:

- which parts must be rebuilt first
- which parts can be mocked or stubbed during bring-up
- which tests prove that the pipeline is alive before game boot
- which signs indicate we are rebuilding the same architecture that previously worked

## Current versioning direction

- FEX baseline: `2601`
- FEX modernization track: evaluate `2603` only after baseline recovery is stable
- Mesa baseline strategy: support two packaged Vulkan/Mesa stacks and select one at runtime
