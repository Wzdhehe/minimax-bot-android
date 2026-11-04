// SPDX-License-Identifier: AGPL-3.0-only
// Linux host acceptance only. No host native module is ever an Android input.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, lstatSync, mkdirSync, readFileSync, realpathSync, symlinkSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import YAML from 'yaml';
import { checkUpstreamRuntimePin } from '../../runtime/minimax-bot-host/upstream-runtime-pin-check.mjs';
import { UPSTREAM_RUNTIME_PIN } from '../../runtime/minimax-bot-host/upstream-runtime-pin.mjs';

const root = dirname(fileURLToPath(import.meta.url));
const [sourceInput, bundleInput, archiveInput, ...extra] = process.argv.slice(2);
if (!sourceInput || !bundleInput || !archiveInput || extra.length) throw new Error('Expected <pinned-checkout> <built-bundle-directory> <verified-sqlite-archive>');
if (process.platform !== 'linux' || process.arch !== 'x64' || process.versions.modules !== '137') {
  throw new Error('This probe requires Linux x64 and Node module ABI137; it cannot prepare Android');
}
const source = realpathSync(sourceInput), bundle = realpathSync(bundleInput), archive = resolve(archiveInput);
const checked = checkUpstreamRuntimePin({ sourceDir: source, bundleMetafile: join(bundle, 'metafile.json') });
assert.deepEqual(checked.errors, [], 'Pinned runtime inputs must verify before probe preparation');
const manifest = JSON.parse(readFileSync(join(bundle, 'manifest.json')));
assert.equal(manifest.upstream.commit, UPSTREAM_RUNTIME_PIN.commit);
assert.equal(manifest.upstream.tree, UPSTREAM_RUNTIME_PIN.tree);
const hash = (data) => createHash('sha256').update(data).digest('hex');
assert.equal(manifest.artifact.path, 'runtime-host.partial.mjs');
assert.equal(hash(readFileSync(join(bundle, manifest.artifact.path))), manifest.artifact.sha256);
for (const asset of manifest.assets) {
  assert.ok(asset.path.startsWith('assets/') && !asset.path.split('/').includes('..'));
  assert.equal(hash(readFileSync(join(bundle, asset.path))), asset.sha256);
}
const packageName = (specifier) => specifier.startsWith('@') ? specifier.split('/').slice(0, 2).join('/') : specifier.split('/')[0];
const expectedDependencies = [...new Set([...manifest.externalPackages.map(packageName), 'better-sqlite3', 'bindings'])].sort();
const dependencies = JSON.parse(readFileSync(join(root, 'package.json'))).dependencies;
assert.deepEqual(Object.keys(dependencies).sort(), expectedDependencies, 'Probe must account for every external and reviewed opaque dependency');
const upstreamLock = YAML.parse(readFileSync(join(source, 'pnpm-lock.yaml'), 'utf8'));
for (const [name, version] of Object.entries(dependencies)) {
  const versions = new Set();
  for (const importer of Object.values(upstreamLock.importers)) {
    for (const field of ['dependencies', 'optionalDependencies']) {
      const resolved = importer[field]?.[name]?.version;
      if (typeof resolved === 'string' && !resolved.startsWith('link:')) versions.add(resolved.split('(')[0]);
    }
  }
  if (!versions.size) for (const key of Object.keys(upstreamLock.packages)) {
    if (key.startsWith(`${name}@`) && !key.includes('(')) versions.add(key.slice(name.length + 1));
  }
  assert.deepEqual([...versions], [version], `Dependency ${name} must match the one unambiguous version in the pinned lock`);
}
// Official 12.11.1 release API digest, Linux/x64/Node137. No install scripts.
const sqliteArchiveSha256 = '99c43785639d5d3690c396ba245ee680ac8b469a46b19233a3546f0eb7f8e312';
assert.equal(dependencies['better-sqlite3'], '12.11.1');
assert.equal(hash(readFileSync(archive)), sqliteArchiveSha256, 'SQLite archive SHA256 mismatch');
const member = 'build/Release/better_sqlite3.node';
const members = execFileSync('tar', ['-tzf', archive], { encoding: 'utf8' }).trim().split('\n');
assert.deepEqual(members, [member]);
const nativeBytes = execFileSync('tar', ['-xOzf', archive, member], { maxBuffer: 32 * 1024 * 1024 });
const nodeModules = realpathSync(join(root, 'node_modules'));
const sqliteRoot = join(nodeModules, 'better-sqlite3');
assert.equal(JSON.parse(readFileSync(join(sqliteRoot, 'package.json'))).version, dependencies['better-sqlite3']);
const nativeFile = join(sqliteRoot, member);
mkdirSync(dirname(nativeFile), { recursive: true });
if (existsSync(nativeFile)) assert.equal(hash(readFileSync(nativeFile)), hash(nativeBytes), 'Refusing to replace an unknown host SQLite binary');
else writeFileSync(nativeFile, nativeBytes, { flag: 'wx', mode: 0o600 });
const bundleModules = join(bundle, 'node_modules');
if (existsSync(bundleModules)) {
  assert.ok(lstatSync(bundleModules).isSymbolicLink());
  assert.equal(realpathSync(bundleModules), nodeModules);
} else symlinkSync(nodeModules, bundleModules, 'dir');
const require = createRequire(import.meta.url);
const Database = require('better-sqlite3');
const database = new Database(':memory:');
try { assert.equal(database.prepare('SELECT 1 AS ready').get().ready, 1); }
finally { database.close(); }
mkdirSync(join(root, '.build'), { recursive: true });
writeFileSync(join(root, '.build/host-probe.json'), `${JSON.stringify({ bundle, source, sqliteArchiveSha256, nodeAbi: process.versions.modules }, null, 2)}\n`);
console.log(`PASS: pinned runtime, ${expectedDependencies.length} direct dependency versions, resources and Linux-only SQLite verified; host probe prepared`);
