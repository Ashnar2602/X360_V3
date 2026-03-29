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

The live workspace is now far beyond the last archived milestone.

What exists in the live workspace but is not yet archived as a dedicated milestone set:

- input bridge into headless Xenia
- global official patch DB staging
- title-content UI/pipeline
- progression diagnostics bundle and stall classification
- freeze-lab diagnostics and movie/audio deep-debug plumbing
- DLC visibility policy per game
- Xenia rebase to `553aedebb59340d3106cd979ca7d09cc8e3bd98e`
- current Xenia patch set `phase11a-upstreamfirst-v1`
- current stable product baseline with `FRAMEBUFFER_POLLING` as default player backend

Important note:

- the main `app-debug.apk` is still not committed because it exceeds GitHub's `100 MB` per-file limit
- when a new milestone is considered stable, archive the binaries plus `artifact-manifest.json` instead of trying to commit the APK directly

## Recommendation for next archive

The next archive should be created only after the current progression-stall milestone is stabilized, so the archived package reflects:

- live visible rendering on both validated devices
- controller input
- patch DB presence
- the rebased `553...` upstream-first Xenia guest
- a trustworthy post-boot progression baseline
