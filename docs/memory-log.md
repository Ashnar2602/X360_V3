# Memory Log

Use this file to capture remembered details as soon as they resurface.

Rule of thumb:

- mark direct memory as `remembered`
- mark deduction as `inferred`
- mark uncertain ideas as `speculative`

## Recovered details

### 2026-03-17

- `remembered`: test devices were Ayn Odin 2 and Ayn Odin 2 Mini
- `remembered`: both devices used Snapdragon 8 Gen 2 with Adreno 740
- `remembered`: both devices had 12 GB RAM
- `remembered`: games that worked included Dante's Inferno and Forza Horizon
- `remembered`: performance was around 25-30 FPS before async callback work
- `remembered`: FEX version was 2601
- `remembered`: Winlator was not used
- `remembered`: FEX was integrated directly to translate Linux `x86_64` ELF to ARM64
- `remembered`: Xenia likely ran in a headless/offscreen GDK path
- `remembered`: Turnip was compiled as `x86_64` and used directly in the app
- `remembered`: an early graphics freeze was likely related to KGSL saturation
- `remembered`: X11/Wayland was likely bypassed entirely through a headless/offscreen present path

## Open prompts for later recovery

- exact Xenia Canary branch or commit family
- exact Mesa/Turnip branch or commit family
- how Vulkan ICD discovery was wired
- whether a compact RootFS existed
- whether SDL or any display shim still existed in the flow
- where frontbuffer/offscreen output was handed to Android UI
- which specific synchronous callbacks were still pending async conversion
