## Mesa Patch Queue

This directory contains repo-owned patch queues applied to the pinned Mesa
source snapshots during `GenerateTurnipMesaAssetsTask`.

- `mesa25/`: branch-local patch queue for the `mesa25` guest bundle
- `mesa26/`: branch-local patch queue for the `mesa26` guest bundle

Only `*.patch` files are applied, in lexicographic order, after the Mesa
source archive is extracted and before the WSL `meson+ninja` build begins.
