## Mesa Turnip Runtime Sources

This directory pins the official Mesa source snapshots used to build the
guest-side Turnip runtime bundles for Phase 3B.

The lock manifest is repo-owned and records:

- the branch id exposed by the app (`mesa25`, `mesa26`)
- the exact Mesa version label
- the official source origin URL
- the pinned source ref and commit when using git snapshots
- the expected `sha256` when using release archives
- the install profile used by the WSL build pipeline
- the patch set id applied during asset generation

These sources are not checked into Git as full tarballs. They are fetched or
archived during the generated asset step, then built into isolated guest
runtime trees under:

- `rootfs/opt/x360-v3/mesa/mesa25`
- `rootfs/opt/x360-v3/mesa/mesa26`

Repo-owned Mesa patches live under:

- `third_party/mesa-patches/mesa25`
- `third_party/mesa-patches/mesa26`

The current `mesa26` bundle carries patch set `ubwc5-a830-v1`, which teaches
the KGSL Turnip path to accept UBWC `5.0` / `6.0` and defer the final UBWC
config to Mesa's existing A8xx/A830 device database.
