# Version Strategy

## Goal

Use the most conservative setup possible to recover the previously working prototype, while leaving a clean path to evaluate newer components later.

## FEX strategy

### Active baseline

- `FEX 2601`-family source pin:
  - `49a37c7d6fec7d94923507e5ce10d55c2920e380`

Reason:

- it is the version family remembered as definitely working in the original prototype
- it gives us a stable reference point for later A/B testing
- the repo already carries the Android-specific patch queue needed to build and run this baseline reproducibly

### Modernization track

- evaluate `FEX 2603+` only after the current baseline needs a justified upgrade

Reason:

- newer FEX versions likely contain useful optimizations and fixes
- they may also change JIT, memory, or thunk behavior in ways that complicate regression attribution

## Mesa strategy

### Active packaging model

Bundle two separate `x86_64` Mesa/Turnip runtime trees:

- `mesa25`
- `mesa26`

At launch time, select the Mesa tree based on measured device policy and a manual override.

### Actual measured policy in this repo

- `kalama` / `QCS8550` -> `mesa26`
- `sun` / `CQ8725S` -> `mesa26`
- unknown Qualcomm -> `mesa25`
- non-Qualcomm -> `lavapipe`

Reason:

- `Odin3` requires the `mesa26` path plus the repo-owned UBWC `5.0` / `6.0` fix
- the current visible-frame/player milestone is also validated on `AYN Odin2 Mini` with `mesa26`
- theoretical generation-only rules were useful to start, but the current measured device path now wins

### Important constraint

This should be treated as a live device policy, not a permanent law.

The real policy remains:

- measured defaults
- device-specific allowlist and denylist
- manual override for testing and recovery

### Current Mesa pins

- `mesa25`: `25.3` branch snapshot `7f1ccad77883be68e7750ab30b99b16df02e679d`
- `mesa26`: `main` snapshot `44669146808b74024b9befeb59266db18ae5e165`

### Current Mesa patching

- `mesa26` carries patch set `ubwc5-a830-v1`

Reason:

- Odin3 reports `ubwc_mode = 5`
- the fix belongs in Mesa/Freedreno guest-side, not in the Android wrapper
- the patch makes the KGSL Turnip path accept UBWC `5.0` / `6.0` and defer the final config to Mesa's existing A8xx/A830 device database

## Xenia strategy

### Historical reference

- `canary_experimental@d9747704bedc4e691ba243bf399647b836ce493e`

Use:

- historical forensics anchor
- useful for understanding the previously working prototype
- not the active bring-up target anymore

### Active bring-up baseline

- `canary_experimental@c50b036178108f87cb0acaf3691a7c3caf07820f`
- patch set: `phase5a-framebuffer-polling-v11`

Reason:

- upstream Canary is active and materially newer than the forensic pin
- the goal is a working Android/FEX/Linux bring-up, not archaeological replay at all costs
- the current repo-owned patch queue captures the POSIX/headless fixes, non-fatal cache handling, and framebuffer-polling presentation path needed for the visible-player milestone

### Local development loop

- keep two Xenia build modes:
  - full reproducible build
  - persistent incremental local build

Reason:

- clean validation still needs a deterministic full build path
- local debugging does not need to pay the full setup/build cost every iteration
- the repo now keeps a persistent checkout and CMake/Ninja build directory keyed by revision, patch set, and build profile

### Comparative track

- study `Xenia Edge` as a Linux/Vulkan donor fork
- keep `Canary` as the main base until a measured reason exists to pivot or cherry-pick

Reason:

- Edge is interesting for Linux/Vulkan ideas and newer fixes
- changing emulator base too early would multiply variables while the stack is still being reconstructed

## Practical conclusion

The current version strategy is now:

1. keep the reconstructed FEX `2601` baseline stable
2. keep dual-Mesa support and measured device routing
3. keep modern pinned `Canary` as the active Xenia baseline
4. preserve the historical Xenia pin as a forensic reference, not as the default build target
5. archive successful artifacts and exact hashes whenever a milestone becomes real
