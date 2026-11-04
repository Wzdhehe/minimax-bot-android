// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { test } from 'node:test';
import { checkUpstreamRuntimePin } from './upstream-runtime-pin-check.mjs';
import { upstreamRuntimeEsbuildAliases } from './upstream-runtime-pin.mjs';
import { runtimeBuildWiring } from './runtime-build-plugin.mjs';
import { buildRuntimeEnv } from './runtime-descriptor.mjs';

const source = process.env.WEBUI_SOURCE && resolve(process.env.WEBUI_SOURCE);
const esbuildModule = process.env.ESBUILD_MODULE && resolve(process.env.ESBUILD_MODULE);
const here = dirname(fileURLToPath(import.meta.url));
test('trusted runtime session lifecycle through the actual pinned gateway', {
  skip: !source || !esbuildModule ? 'Set WEBUI_SOURCE and ESBUILD_MODULE for the real gateway cases' : false,
}, async (t) => {
  assert.equal(checkUpstreamRuntimePin({ sourceDir: source }).ok, true);
  const esbuild = await import(pathToFileURL(esbuildModule).href);
  assert.equal(esbuild.version, JSON.parse(readFileSync(join(source, 'package.json'))).devDependencies.esbuild);
  const root = mkdtempSync(join(tmpdir(), 'mbot-runtime-session-tests-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const file = join(root, 'cases.mjs');
  const contractAliases = Object.fromEntries(upstreamRuntimeEsbuildAliases(source).map((argument) => {
    const value = argument.slice('--alias:'.length); const split = value.indexOf('=');
    return [value.slice(0, split), value.slice(split + 1)];
  }));
  const built = await esbuild.build({ absWorkingDir: here, entryPoints: [join(here, 'runtime-host-session-cases.mjs')],
    outfile: file, bundle: true, format: 'esm', platform: 'node', target: 'node24', metafile: true,
    alias: { ...contractAliases, ...runtimeBuildWiring(source).alias }, logLevel: 'silent' });
  const metafile = join(root, 'metafile.json');
  // The checker resolves against its caller cwd, while esbuild used `here`.
  built.metafile.inputs = Object.fromEntries(Object.entries(built.metafile.inputs).map(([path, input]) => [resolve(here, path), input]));
  writeFileSync(metafile, JSON.stringify(built.metafile));
  const verified = checkUpstreamRuntimePin({ sourceDir: source, bundleMetafile: metafile });
  assert.deepEqual(verified.errors, []);
  const result = spawnSync(process.execPath, ['--test', '--test-reporter=tap', '--test-timeout=15000', file], {
    encoding: 'utf8', timeout: 20000, maxBuffer: 1024 * 1024, env: buildRuntimeEnv(),
  });
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.match(result.stdout, /# tests [1-9][0-9]*/);
  assert.match(result.stdout, /# fail 0/);
  assert.match(result.stdout, /# skipped 0/);
  t.diagnostic(result.stdout.trim());
});
