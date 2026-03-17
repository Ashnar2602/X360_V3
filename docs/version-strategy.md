# Version Strategy

## Goal

Use the most conservative setup possible to recover the previously working prototype, while leaving a clean path to evaluate newer components later.

## FEX strategy

### Baseline

- Start from `FEX 2601`

Reason:

- it is the version remembered as definitely working in the original prototype
- it reduces the number of moving parts during reconstruction
- it gives us a stable reference point for later A/B testing

### Modernization track

- Evaluate `FEX 2603` only after the `2601` baseline is reproducible

Reason:

- `2603` likely contains useful optimizations and fixes
- it may also change JIT, code cache, thunk, or memory behavior in ways that alter runtime stability
- introducing it before baseline recovery would make regression attribution much harder

## Mesa strategy

## Proposed approach

Bundle two separate `x86_64` Mesa/Turnip runtime trees:

- `Mesa 25.x`
- `Mesa 26.x`

At launch time, select the Mesa tree based on GPU family and an override policy.

## Why this is viable

Vulkan loader behavior supports selecting driver manifests at runtime through driver manifest configuration such as `VK_DRIVER_FILES` or `VK_ICD_FILENAMES`, provided the corresponding libraries are discoverable by the runtime loader as well.

This means the app can package more than one driver/runtime layout and choose which one a guest process should see.

## Recommended policy

### Initial automatic policy

- default to `Mesa 25.x` for Adreno `6xx` and `7xx`
- default to `Mesa 26.x` for Adreno `8xx`

### Important constraint

This should be treated as a starting heuristic, not as a permanent rule.

The real policy should become:

- series-based default
- device-specific allowlist and denylist
- manual override for testing and recovery

## Why a pure series split may be too coarse

- some `7xx` devices may behave better on `26.x`
- some early `8xx` targets may still expose issues that make `25.x` worth testing if support exists there
- game-specific regressions may cut across series boundaries

## Packaging requirements

To avoid collisions, each Mesa tree should be isolated as a self-contained runtime bundle, including at minimum:

- Vulkan driver manifest JSON
- Turnip shared libraries
- required Mesa dependencies
- any helper libraries needed by the selected userspace path

The selected process environment should point only at one Mesa tree at a time.

## Selection requirements

At process launch, the app should determine:

- GPU family
- specific device override rules
- whether the user has forced a preferred Mesa branch

Then it should expose only the chosen stack to the translated `x86_64` guest process.

## Recommended override controls

- `auto`
- `mesa25`
- `mesa26`

This is useful both for debugging and for field reports.

## Risk tradeoffs

### Benefits

- wider device coverage without forcing one Mesa branch to fit every Adreno generation
- easier bisecting of graphics regressions
- controlled migration path toward newer GPUs

### Costs

- larger app size
- more packaging complexity
- more QA combinations
- need for careful library and manifest separation

## Practical conclusion

Yes, a unified app with dual Mesa bundles is a sensible plan.

For this project, the safest rollout is:

1. recover the prototype with `FEX 2601`
2. keep dual-Mesa support in the design from the start
3. treat `Mesa 25.x` as the first baseline for Adreno `7xx`
4. add `Mesa 26.x` as the modern branch for Adreno `8xx`
5. keep an override path so the defaults can be corrected by real test data
