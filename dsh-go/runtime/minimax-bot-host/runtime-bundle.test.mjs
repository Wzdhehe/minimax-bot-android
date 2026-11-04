// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { checkUpstreamRuntimePin } from './upstream-runtime-pin-check.mjs';
import { UPSTREAM_RUNTIME_COMMIT } from './upstream-runtime-pin.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const source = process.env.WEBUI_SOURCE && resolve(process.env.WEBUI_SOURCE);
const esbuildModule = process.env.ESBUILD_MODULE && resolve(process.env.ESBUILD_MODULE);
const skip = !source || !esbuildModule ? 'Set WEBUI_SOURCE and ESBUILD_MODULE to run the real pinned build' : false;
function runBuild(outDir) {
  return spawnSync(process.execPath, [join(here, 'build-runtime-bundle.mjs'),
    '--source', source, '--esbuild-module', esbuildModule, '--out-dir', outDir],
  { encoding: 'utf8', timeout: 120_000, maxBuffer: 1024 * 1024 });
}

test('the pinned full host and actual Bot graph compile without installing or launching the runtime', { skip }, (t) => {
  const root = mkdtempSync(join(tmpdir(), 'bot-runtime-bundle-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const outDir = join(root, 'artifact');
  const result = runBuild(outDir);
  assert.equal(result.status, 0, result.stderr || result.error?.message);
  const manifest = JSON.parse(readFileSync(join(outDir, 'manifest.json'), 'utf8'));
  assert.equal(manifest.upstream.commit, UPSTREAM_RUNTIME_COMMIT);
  assert.equal(manifest.status, 'build-graph-only');
  assert.equal(manifest.androidReady, false);
  const bytes = readFileSync(join(outDir, manifest.artifact.path));
  assert.equal(bytes.length, manifest.artifact.bytes);
  assert.equal(createHash('sha256').update(bytes).digest('hex'), manifest.artifact.sha256);
  for (const path of ['packages/webui/src/runtime/assembly.ts', 'packages/local-runtime-v2/src/services.ts',
    'packages/local-runtime-v2/src/service/miniapp/agent/session-ports.ts']) {
    assert.ok(manifest.upstreamInputs.includes(path), `Missing the real implementation: ${path}`);
  }
  const metafile = JSON.parse(readFileSync(join(outDir, 'metafile.json'), 'utf8'));
  assert.ok(Object.hasOwn(metafile.inputs, resolve(here, '../vendor/minimax-bot/miniapp/node/server.mjs')));
  const services = metafile.inputs[join(source, 'packages/local-runtime-v2/src/services.ts')];
  assert.ok(services.imports.some((entry) => resolve(source, entry.path) === join(here, 'runtime-miniapp-index.mjs')));
  assert.equal(manifest.hostAdaptations[0].importer, 'packages/local-runtime-v2/src/services.ts');
  assert.equal(manifest.hostAdaptations[0].adapter, 'runtime-miniapp-index.mjs');
  assert.ok(manifest.externalPackages.includes('drizzle-orm/better-sqlite3'));
  assert.ok(manifest.knownOpaqueLoads.some((entry) => entry.startsWith('better-sqlite3')));
  assert.ok(manifest.nodeBuiltins.includes('node:child_process'));
  assert.ok(manifest.nodeBuiltins.includes('node:fs'));
  assert.ok(!manifest.externalPackages.some((entry) => entry.startsWith('@mavis/')));
  // Check the expanded graph through the REAL checker, not just its helper.
  // A synthetic untracked input must fail even with the checkout left clean.
  const badMetaPath = join(root, 'tampered-inputs.json');
  metafile.inputs[join(source, '.git/config')] = { bytes: 0, imports: [] };
  writeFileSync(badMetaPath, JSON.stringify(metafile));
  const rejected = checkUpstreamRuntimePin({ sourceDir: source, bundleMetafile: badMetaPath });
  assert.equal(rejected.ok, false);
  assert.ok(rejected.errors.some((error) => error.includes('not in the pinned tree') && error.includes('.git/config')));
  assert.equal(checkUpstreamRuntimePin({ sourceDir: source }).ok, true);
});

test('a pre-existing output directory is never overwritten', { skip }, (t) => {
  const root = mkdtempSync(join(tmpdir(), 'bot-runtime-output-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const outDir = join(root, 'artifact');
  mkdirSync(outDir);
  writeFileSync(join(outDir, 'keep'), 'caller-owned');
  const result = runBuild(outDir);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Output directory must not already exist/);
  assert.equal(readFileSync(join(outDir, 'keep'), 'utf8'), 'caller-owned');
  assert.equal(existsSync(join(outDir, 'manifest.json')), false);
});

// The checkout must stay untouched even if an output parent is an alias to it.
test('an output parent symlink cannot write generated files into the pinned checkout', { skip }, (t) => {
  const root = mkdtempSync(join(tmpdir(), 'bot-runtime-parent-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const alias = join(root, 'source-alias');
  symlinkSync(source, alias, 'dir');
  const target = `mbot-forbidden-build-${process.pid}`;
  const result = runBuild(join(alias, target));
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Output directory must be outside the upstream checkout/);
  assert.equal(existsSync(join(source, target)), false);
});
