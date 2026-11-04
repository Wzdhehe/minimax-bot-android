<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Real runtime host probe (Linux only)

This acceptance tool boots the **real pinned WebUI/local-runtime-v2 factory**, its original SQLite/session/gateway implementations and the vendored Bot JSON service. It does not inject a replacement runtime factory, gateway or session executor. It sends no model turn and uses no provider credential or account store.

Install dependencies with lifecycle scripts disabled. The 40 direct versions must match the one unambiguous resolution in the pinned upstream `pnpm-lock.yaml`; `prepare.mjs` refuses missing/extra/different direct dependencies. The npm lockfile fixes this probe's transitive dependencies and integrity hashes. It does **not** claim to reproduce the upstream pnpm peer/transitive layout byte for byte.

```bash
# From this repository's root. Use the esbuild JS API, not bin/esbuild.
npm ci --ignore-scripts --no-audit --no-fund --prefix dsh-go/tooling/runtime-host-probe
node dsh-go/runtime/minimax-bot-host/build-runtime-bundle.mjs \
  --source /path/to/pinned/minimax-code \
  --esbuild-module /path/to/esbuild/lib/main.js \
  --out-dir /tmp/mbot-real-host-new
curl -fL --retry 2 --max-time 120 \
  https://github.com/WiseLibs/better-sqlite3/releases/download/v12.11.1/better-sqlite3-v12.11.1-node-v137-linux-x64.tar.gz \
  -o /tmp/mbot-sqlite-host-node137.tar.gz
node dsh-go/tooling/runtime-host-probe/prepare.mjs \
  /path/to/pinned/minimax-code /tmp/mbot-real-host-new /tmp/mbot-sqlite-host-node137.tar.gz
npm test --prefix dsh-go/tooling/runtime-host-probe
```

`prepare.mjs` checks the source pin, actual bundle inputs, artifact/resources, dependency inventory/versions and official SQLite release SHA256 before loading any native code. The exact reviewed tar member is read into its npm package; unknown existing binaries are refused. It supports **Linux x64, Node ABI137 (Node24)** only. This native addon, the npm install and the generated `.build` files are host acceptance inputs, **never Android assets**. Nothing is uploaded or published.

The test creates a fresh private HOME/data directory and a credential-filtered child environment, outside the source checkout. It checks:

- Cold-start and clean reopen of the real runtime/SQLite migrations and lifecycle.
- The built-in Bot caller reaches the **real** SessionSystem missing-row lookup: `SESSION_NOT_FOUND`, rather than an unregistered/unbound gateway failure.
- Actual Bot HTTP authentication, Bot creation and persisted state after reopening.
- Shared close completion and rejection of retained Agent ports after shutdown.
- Natural process exit; child timeouts/nonzero exits are failures. Missing preparation is an error, never a skip.

Only Bot JSON APIs are exercised. The old Mini App browser client is not packaged or tested; the product uses Flutter UI. With an empty provider configuration the exploratory `agent.models()` call currently returns `INVALID_ARGUMENTS`: native metadata/model resolver assembly is still needed. There is no successful model request, Keystore/Android FD transport, shell/tool execution, plugin installation or Android/ARM64/device result in this probe.

Canonical upstream assets are copied by the pinned `copyLocalRuntimeAssets` helper. The build verifies source inventory (including ignored-file rejection), exact output inventory and every copied byte against the pinned Git blobs; each output has a SHA256 entry in the bundle manifest. Native/third-party packages, dynamic worker entry points and remaining resource lookups still need separate Android adaptation. The default App launcher stays DSH; do not use a same-UID DSH/PRoot launch to turn this host probe into a product.
