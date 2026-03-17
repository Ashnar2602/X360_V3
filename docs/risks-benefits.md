# Risks And Benefits

## Why this architecture is attractive

- It avoids Wine and the Windows graphics stack entirely.
- It keeps Xenia on a Linux `x86_64` target, which is much closer to FEX's intended execution model.
- It allows Vulkan to stay central instead of translating Direct3D through additional layers.
- It lines up with a path that already worked in practice on Snapdragon 8 Gen 2 hardware.
- It can preserve upstream Xenia logic more effectively than a direct ARM64 Android port of the whole emulator core.

## Primary technical benefits

### Cleaner compatibility story

Using Linux `x86_64` under FEX is conceptually simpler than `Windows x86_64 -> Wine -> graphics translation -> Android`.

### Better control of presentation

A headless/offscreen path makes it possible to own display handoff in the Android app, instead of depending on desktop Linux window systems that are awkward on Android.

### Better focus for optimization

The remembered synchronous callback bottleneck suggests there was already a real path to better performance once CPU/GPU synchronization overhead was reduced.

## Primary risks

### Double translation cost

The emulator itself already manages guest execution. Running the host build through FEX adds another JIT layer. This can still be viable, but it will amplify sensitivity to synchronization stalls, instruction cache pressure, and thread scheduling.

### KGSL backpressure and resource lifetime

If the stack bypasses normal desktop presentation, then it must explicitly control queue depth, fences, semaphores, image lifetime, and retirement. Otherwise, KGSL pressure can accumulate until output stalls or appears frozen.

### Vulkan loader and ICD packaging complexity

Bundling a `x86_64` Vulkan userspace stack inside an Android app is powerful, but fragile. Loader lookup, driver selection, and dynamic library resolution all become part of the app contract.

### Debuggability

A custom stack across Android app glue, translated `x86_64` userspace, Mesa, and Xenia presentation means failures can masquerade as each other. Startup and telemetry need to be separated cleanly by layer.

## Likely failure modes to watch first

- Vulkan instance creates but no images ever appear
- a few frames render and then the queue stops making visible forward progress
- memory growth or descriptor/image accumulation causes later stalls
- fences or semaphores are never retired on one path
- headless rendering succeeds but Android-side display copy/present gets out of sync
- FEX runtime works for simple Linux ELF targets but breaks under Xenia's thread, signal, or memory behavior

## What the KGSL clue tells us

The remembered freeze-after-seconds or freeze-after-minutes issue is an important architectural clue.

The best interpretation is:

- GPU work submission was real and functional
- the render path could sustain enough activity to create pressure
- the system likely lacked the correct backpressure or resource reclamation strategy

That is a good sign for feasibility. It points to an integration bug, not a dead-end design.

## Best leverage points for reconstruction

- rebuild the exact presentation model before chasing game-specific fixes
- verify repeated Vulkan submit/retire behavior before visible UI polish
- isolate FEX runtime validation from Xenia validation
- treat Mesa packaging and ICD discovery as first-class parts of the project, not setup details
