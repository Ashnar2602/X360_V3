# Sources

These are the primary public sources used to anchor the reconstruction notes and the current implementation choices.

## Xenia Canary

- Repository: [xenia-canary/xenia-canary](https://github.com/xenia-canary/xenia-canary)
- Build documentation: [docs/building.md](https://github.com/xenia-canary/xenia-canary/blob/canary_experimental/docs/building.md)
- Quickstart wiki: [Quickstart](https://github.com/xenia-canary/xenia-canary/wiki/Quickstart)
- FAQ wiki: [FAQ](https://github.com/xenia-canary/xenia-canary/wiki/FAQ)
- Release channel: [xenia-canary-releases](https://github.com/xenia-canary/xenia-canary-releases/releases/canary_experimental)

## Xenia Edge

- Repository: [has207/xenia-edge](https://github.com/has207/xenia-edge)
- Build documentation: [docs/building.md](https://github.com/has207/xenia-edge/blob/edge/docs/building.md)
- Releases: [xenia-edge releases](https://github.com/has207/xenia-edge/releases)

Use in this repo:

- comparative Linux/Vulkan reference
- candidate donor for future ideas or cherry-picks
- not the active baseline today

## FEX

- Repository: [FEX-Emu/FEX](https://github.com/FEX-Emu/FEX)
- Source outline: [docs/SourceOutline.md](https://github.com/FEX-Emu/FEX/blob/main/docs/SourceOutline.md)
- RootFS notes: [Development:Setting up RootFS](https://wiki.fex-emu.com/index.php/Development%3ASetting_up_RootFS)
- Thunk setup: [Development:Setting up Thunks](https://wiki.fex-emu.com/index.php/Development%3ASetting_up_Thunks)

## Mesa / Turnip

- Freedreno / Turnip overview: [Mesa Freedreno documentation](https://docs.mesa3d.org/drivers/freedreno.html)
- Mesa options reference: [meson.options](https://raw.githubusercontent.com/mirror/mesa/main/meson.options)
- Perfetto support: [Mesa Perfetto documentation](https://docs.mesa3d.org/perfetto.html)
- u_trace support: [Mesa u_trace documentation](https://docs.mesa3d.org/u_trace.html)

## Vulkan reference

- Headless surface extension: [VK_EXT_headless_surface](https://registry.khronos.org/vulkan/specs/latest/man/html/VK_EXT_headless_surface.html)
- Vulkan loader driver interface: [Loader Driver Interface](https://vulkan.lunarg.com/doc/view/1.4.328.1/windows/LoaderDriverInterface.html)

## Context status

These sources anchor the upstream architecture and current public positioning.

The historical prototype described in this repository remains based on remembered local experimentation and forensic reconstruction.

The active implementation path in this repo now uses:

- pinned-source FEX with repo-owned Android patches
- pinned-source Mesa with repo-owned Turnip fixes
- pinned-source Xenia Canary with repo-owned headless POSIX bring-up patches
