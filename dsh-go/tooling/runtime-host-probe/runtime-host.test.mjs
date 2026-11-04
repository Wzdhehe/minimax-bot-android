// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { buildRuntimeEnv } from '../../runtime/minimax-bot-host/runtime-descriptor.mjs';

const here = dirname(fileURLToPath(import.meta.url));
test('real pinned runtime boots with native host SQLite and serves the official Bot across a clean reopen', { timeout: 80000 }, (t) => {
  // Missing preparation is an error, never a successful skip.
  const { bundle } = JSON.parse(readFileSync(join(here, '.build/host-probe.json')));
  const privateRoot = mkdtempSync(join(tmpdir(), 'mbot-real-host-'));
  t.after(() => rmSync(privateRoot, { recursive: true, force: true }));
  const dataDir = join(privateRoot, 'runtime');
  // Explicit fixture paths, no inherited provider credentials, account store,
  // proxy auth, NODE_TEST_CONTEXT or source-repository working directory.
  const env = { ...buildRuntimeEnv(), HOME: privateRoot, MINIMAX_DATA_DIR: dataDir };
  const result = spawnSync(process.execPath, [join(here, 'runtime-host-child.mjs'), bundle, dataDir], {
    cwd: privateRoot, env, encoding: 'utf8', timeout: 70000, maxBuffer: 2 * 1024 * 1024,
  });
  assert.equal(result.status, 0, result.stdout + result.stderr + (result.error?.message ?? ''));
  assert.match(result.stdout, /PASS: real fork factory cold-start\/reopen/);
  assert.match(result.stdout, /projected non-secret provider catalog/);
  assert.match(result.stdout, /unbound model turn refused/);
  assert.ok(!result.stdout.includes('native-broker-placeholder'));
  t.diagnostic(result.stdout.match(/PASS: real fork factory[^\n]*/)[0]);
});
