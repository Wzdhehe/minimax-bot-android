// SPDX-License-Identifier: AGPL-3.0-only
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));

/** Exact pinned import seam; no source rewriting or unbound gateway clone. */
export function runtimeBuildWiring(source) {
  const adaptation = Object.freeze({
    importer: 'packages/local-runtime-v2/src/services.ts',
    specifier: './service/miniapp/index.js',
    adapter: 'runtime-miniapp-index.mjs',
  });
  const sqliteAdaptation = Object.freeze({
    importer: 'packages/local-runtime-v2/src/infra/db/client.ts',
    specifier: 'node:module',
    adapter: 'runtime-sqlite-module.mjs',
  });
  const legacySqliteAdaptation = Object.freeze({
    importer: 'packages/local-runtime/src/persistence/better-sqlite3-loader.ts',
    specifier: 'node:module',
    adapter: 'runtime-sqlite-module.mjs',
  });
  const sqliteFsAdaptations = ['packages/local-runtime/src/persistence/db.ts',
    'packages/local-runtime-v2/src/infra/db/client.ts'].map((importer) => Object.freeze({
    importer, specifier: 'node:fs', adapter: 'runtime-sqlite-fs.mjs',
  }));
  const snapshotAdaptation = Object.freeze({ importer: 'packages/local-runtime-v2/src/infra/db/backup.ts',
    specifier: 'node:fs/promises', adapter: 'runtime-sqlite-snapshot-fs.mjs' });
  return {
    adaptations: [adaptation, sqliteAdaptation, legacySqliteAdaptation, ...sqliteFsAdaptations, snapshotAdaptation],
    alias: {
      '@mcode-runtime/assembly': join(source, 'packages/webui/src/runtime/assembly.ts'),
      '@mcode-runtime/agent-gateway': join(source, 'packages/local-runtime-v2/src/service/miniapp/agent/gateway.ts'),
      '@mcode-runtime/miniapp-index': join(source, 'packages/local-runtime-v2/src/service/miniapp/index.ts'),
    },
    plugin: {
      name: 'builtin-bot-runtime-gateway',
      setup(build) {
        build.onResolve({ filter: /^\.\/service\/miniapp\/index\.js$/ }, (input) => {
          if (input.importer !== join(source, adaptation.importer)) return undefined;
          return { path: join(here, adaptation.adapter) };
        });
        build.onResolve({ filter: /^node:module$/ }, (input) => {
          if (![sqliteAdaptation, legacySqliteAdaptation].some((entry) => input.importer === join(source, entry.importer))) return undefined;
          return { path: join(here, sqliteAdaptation.adapter) };
        });
        build.onResolve({ filter: /^node:fs$/ }, (input) => {
          if (!sqliteFsAdaptations.some((entry) => input.importer === join(source, entry.importer))) return undefined;
          return { path: join(here, 'runtime-sqlite-fs.mjs') };
        });
        build.onResolve({ filter: /^node:fs\/promises$/ }, (input) => {
          if (input.importer !== join(source, snapshotAdaptation.importer)) return undefined;
          return { path: join(here, snapshotAdaptation.adapter) };
        });
      },
    },
  };
}
