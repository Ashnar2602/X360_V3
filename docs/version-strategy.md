# Version Strategy

## Goal

Keep the stack conservative enough to stay diagnosable, while preserving a path to test newer components later.

## FEX strategy

### Active baseline

- `49a37c7d6fec7d94923507e5ce10d55c2920e380`
- patch set: `android-baseline-v1`

Reason:

- this is the pinned FEX family used throughout the reconstructed stack
- it already carries the Android-specific patch queue needed by this repo

### Upgrade policy

- treat newer FEX revisions as explicit experiments, not silent upgrades

Reason:

- FEX changes can affect JIT, syscall behavior, memory, and thunking
- progression-stall debugging is hard enough without moving the FEX baseline at the same time

## Mesa strategy

### Active packaging model

Bundle two guest Mesa/Turnip trees:

- `mesa25`
- `mesa26`

Select at launch time using measured device policy plus manual override when debugging.

### Current Mesa pins

- `mesa25`: `7f1ccad77883be68e7750ab30b99b16df02e679d`
- `mesa26`: `44669146808b74024b9befeb59266db18ae5e165`

### Current Mesa patching

- patch set: `ubwc5-a830-v1`

Reason:

- Odin3 needs the Mesa-side UBWC fix
- the fix belongs in Mesa/Freedreno, not in app glue

## Xenia strategy

### Historical references

- `canary_experimental@d9747704bedc4e691ba243bf399647b836ce493e`
- `canary_experimental@c50b036178108f87cb0acaf3691a7c3caf07820f`

Use:

- historical forensics anchors only

### Active baseline

- `canary_experimental@553aedebb59340d3106cd979ca7d09cc8e3bd98e`
- patch set: `phase11a-upstreamfirst-v1`

Reason:

- this is the desktop-working Canary family now used by the Android/Linux guest
- rebasing removed the old `c50b...` pin as a primary suspect for Dante's freeze
- the active patch queue keeps Android platform/runtime deltas but returns XAM behavior to upstream-first semantics by default

### XAM policy after Phase 11A

Default policy:

- `xam_media`: upstream-first
- `xam_net`: upstream-first
- `apps/xlivebase_app`: upstream-first
- `xam_content`: upstream-first plus the minimum Android/product delta for DLC visibility and diagnostics

Reason:

- Android/runtime differences should stay explicit in launch args/env and patch-set-scoped platform patches
- silent semantic rewrites in XAM made desktop parity too hard to reason about

### Patch-queue structure

Use:

- `third_party/xenia-patches/<patchSetId>/`

Reason:

- the generator now resolves the patch directory from `patchSetId`
- this keeps historical stacks like `phase4` available while allowing a clean active queue such as `phase11a-upstreamfirst-v1`

## Presentation strategy

### Current stable baseline

- default backend: `FRAMEBUFFER_POLLING`

Reason:

- it is the current stable live-player path in the app
- the packaged Xenia binary includes the polling export fix that restored multi-frame export instead of freezing after frame 1

### Alternate backend kept in-tree

- `FRAMEBUFFER_SHARED_MEMORY`

Reason:

- it remains useful for experimentation and comparison
- it is not the current product default while progression work is still active

### Important rule

- keep presentation changes isolated from progression debugging whenever possible

Reason:

- recent work already proved that display regressions can masquerade as gameplay stalls
- once rendering is live again, guest-side progression must be debugged separately

## Patch DB strategy

### Active baseline

- bundle the official `xenia-canary/game-patches` snapshot into runtime
- keep repo-owned Xenia source patches separate from the official patch DB

Reason:

- the official DB should apply globally for all titles
- local source patches are for runtime/platform recovery and diagnostics, not title-specific patch data

## Development loop strategy

### Preferred daily loop

- persistent incremental Xenia workspace
- debug APK rebuild/install with low-RAM Gradle flags

### Clean validation loop

- full Xenia asset generation and runtime staging when the toolchain is healthy

### Current practical note

After the rebase to `553...`, full guest rebuilds can take a long time and should be judged by actual WSL compile activity, not just wrapper wall-clock time.

If a fresh run is genuinely unhealthy, prefer:

1. a known-good incremental workspace rebuild
2. a debug payload override via `_local/runtime-drop/`

over:

- repeatedly throwing away working binaries
- reintroducing large uncontrolled variables

## Comparative track

- use desktop Canary at the same upstream revision as the parity oracle
- study `Xenia Edge` only as a donor source for ideas or cherry-picks
- keep Canary as the main codebase unless a measured reason exists to pivot

## Practical conclusion

The current version policy is:

1. keep the pinned FEX baseline stable
2. keep dual Mesa support with measured routing
3. keep the Android/Linux guest aligned with the desktop-working `553...` Canary family
4. keep polling as the stable product presentation path for now
5. keep XAM semantics upstream-first by default
6. move slowly, verify on real devices, and avoid changing multiple stack layers at once
