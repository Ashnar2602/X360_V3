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

### Historical reference

- `canary_experimental@d9747704bedc4e691ba243bf399647b836ce493e`

Use:

- historical forensics anchor only

### Active baseline

- `canary_experimental@c50b036178108f87cb0acaf3691a7c3caf07820f`
- patch set: `phase10c-triage-v2`

Reason:

- this is the active pinned Xenia baseline used by the current app/runtime
- the repo-owned patch queue now includes:
  - headless bring-up fixes
  - cache handling fixes
  - input bridge support
  - patch DB/content/progression instrumentation
  - polling/shared-memory presentation-related fixes

## Presentation strategy

### Current stable baseline

- default backend: `FRAMEBUFFER_POLLING`

Reason:

- it is the current stable live-player path in the app
- the latest packaged Xenia binary includes the polling export fix that restored multi-frame export instead of freezing after frame 1

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
- local source patches are for runtime/platform recovery, not title-specific patch data

## Development loop strategy

### Preferred daily loop

- persistent incremental Xenia workspace
- debug APK rebuild/install with low-RAM Gradle flags

### Clean validation loop

- full Xenia asset generation and runtime staging when the toolchain is healthy

### Current practical note

If a fresh Xenia workspace fails for unrelated generator/build reasons, prefer:

1. a known-good incremental workspace rebuild
2. a debug payload override via `_local/runtime-drop/`

over:

- repeatedly throwing away working binaries
- reintroducing large uncontrolled variables

## Comparative track

- study `Xenia Edge` only as a donor source for ideas or cherry-picks
- keep Canary as the main codebase unless a measured reason exists to pivot

## Practical conclusion

The current version policy is:

1. keep the pinned FEX baseline stable
2. keep dual Mesa support with measured routing
3. keep modern pinned Canary as the active Xenia baseline
4. keep polling as the stable product presentation path for now
5. move slowly, verify on real devices, and avoid changing multiple stack layers at once
