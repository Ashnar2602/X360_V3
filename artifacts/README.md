# Archived Artifacts

This directory exists so successful milestone outputs do not live only inside `app/build/`.

Currently archived milestones:

- `phase4a-vulkan-init`
- `phase5b-visible-player`

Those archives preserve:

- built `xenia-canary`
- built FEX host binaries
- metadata and source locks
- `app-debug-androidTest.apk`
- `artifact-manifest.json` with file size and `sha256`

## Current live workspace vs archive state

The live workspace is now well beyond the last archived milestone.

What exists in the live workspace but is not yet archived as a dedicated milestone set:

- input bridge into headless Xenia
- global official patch DB staging
- title-content UI/pipeline
- progression diagnostics bundle and stall classification
- current Xenia patch set `phase10c-triage-v2`
- current stable product baseline with `FRAMEBUFFER_POLLING` as default player backend

Important note:

- the main `app-debug.apk` is still not committed because it exceeds GitHub's `100 MB` per-file limit
- when a new milestone is considered stable, archive the binaries plus `artifact-manifest.json` instead of trying to commit the APK directly

## Recommendation for next archive

The next archive should be created only after the current progression-stall milestone is stabilized, so the archived package reflects:

- live visible rendering on both validated devices
- controller input
- patch DB presence
- a trustworthy post-boot progression baseline
