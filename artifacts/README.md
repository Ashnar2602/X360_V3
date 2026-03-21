# Archived Artifacts

This directory exists so successful milestone outputs do not live only inside `app/build/`.

Current archived milestone:

- `phase4a-vulkan-init`
- `phase5b-visible-player`

Contents preserved there:

- built `xenia-canary` binary for the successful Phase 4A bring-up
- built FEX host binaries used by the same milestone
- exact source locks and build metadata for Xenia and FEX
- `app-debug-androidTest.apk`
- `artifact-manifest.json` with sizes and `sha256` values

For `phase5b-visible-player`, the archive preserves the same class of artifacts for the first milestone where:

- the product shell exists
- the fullscreen player exists
- Dante produces visible frames on both validated devices

Important note:

- the main `app-debug.apk` from this milestone is larger than GitHub's `100 MB` per-file limit
- it is therefore not committed directly, but its exact size and `sha256` are captured in the manifest so the successful package state is still pinned and auditable
