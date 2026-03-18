Guest test fixtures live here and are intentionally tiny so each milestone can prove one layer at a time.

- `hello_x86_64.c` / `hello_x86_64`
  - static Linux `x86_64` ELF for the Phase 2 FEX baseline
  - prints `X360_FEX_HELLO_OK`, echoes cwd and argv, writes `/tmp/fex_hello_ok.json`, exits `0`
- `dyn_hello_x86_64.c` / `dyn_hello_x86_64`
  - dynamic Linux `x86_64` ELF for the Phase 3A glibc/loader baseline
  - prints `X360_DYN_HELLO_OK`, echoes cwd and argv, writes `/tmp/fex_dyn_hello_ok.json`, exits `0`
- `vulkan_probe_x86_64.c` / `vulkan_probe_x86_64`
  - dynamic Linux `x86_64` Vulkan probe for the Phase 3A guest loader baseline
  - creates a Vulkan instance, enumerates physical devices, writes `/tmp/fex_vulkan_probe.json`
