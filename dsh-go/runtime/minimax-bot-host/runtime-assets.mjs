// SPDX-License-Identifier: AGPL-3.0-only
// Build-time packaging of canonical fork resources, never a runtime file API.
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, lstatSync, readFileSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';
import { pathToFileURL } from 'node:url';
import { UPSTREAM_RUNTIME_TREE } from './upstream-runtime-pin.mjs';

const helperPath = 'scripts/lib/local-runtime-assets.mjs';
const legacyPrefix = 'packages/local-runtime/assets/';
const agentPrefix = 'packages/local-runtime-v2/assets/agents/';
const catalogPath = 'packages/local-runtime-v2/assets/models-dev-catalog.json.gz';
function gitBlob(bytes) {
  return createHash(UPSTREAM_RUNTIME_TREE.length === 40 ? 'sha1' : 'sha256')
    .update(`blob ${bytes.length}\0`).update(bytes).digest('hex');
}
function verifyFile(path, expected) {
  if (!lstatSync(path).isFile()) throw new Error('Runtime asset must be a regular file');
  const bytes = readFileSync(path);
  if (gitBlob(bytes) !== expected) throw new Error('Runtime asset differs from the pinned Git tree');
  return { bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') };
}
function filesIn(root) {
  const paths = [];
  function walk(directory) {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const path = join(directory, entry.name);
      if (entry.isDirectory()) walk(path);
      else if (entry.isFile()) paths.push(relative(root, path).replaceAll('\\', '/'));
      else throw new Error('Packaged runtime resources cannot contain symlinks or special files');
    }
  }
  walk(root);
  return paths.sort();
}

/** outputDir must be the new, exclusively owned directory created by the builder. */
export async function stageRuntimeAssets({ source, outputDir }) {
  if (existsSync(join(outputDir, 'assets'))) throw new Error('Runtime assets output must not already exist');
  const listing = execFileSync('git', ['-C', source, 'ls-tree', '-r', '-z', UPSTREAM_RUNTIME_TREE, '--',
    helperPath, legacyPrefix.slice(0, -1), agentPrefix.slice(0, -1), catalogPath], { maxBuffer: 4 * 1024 * 1024 }).toString('utf8');
  const entries = new Map(listing.split('\0').filter(Boolean).map((entry) => {
    const split = entry.indexOf('\t');
    const [mode, kind, blob] = entry.slice(0, split).split(' ');
    if (kind !== 'blob' || !['100644', '100755'].includes(mode)) throw new Error('Pinned runtime resources must be regular files');
    return [entry.slice(split + 1), blob];
  }));
  const helperBlob = entries.get(helperPath);
  if (!helperBlob) throw new Error('Pinned runtime resource copier is missing');
  const helper = verifyFile(join(source, helperPath), helperBlob);
  const expected = new Map();
  const sourceFiles = [];
  for (const [path, blob] of entries) {
    let target;
    if (path.startsWith(legacyPrefix) && !path.startsWith(`${legacyPrefix}agents/`)) target = `assets/${path.slice(legacyPrefix.length)}`;
    if (path.startsWith(agentPrefix)) target = `assets/agents/${path.slice(agentPrefix.length)}`;
    if (path === catalogPath) target = 'assets/models-dev-catalog.json.gz';
    if (target) {
      verifyFile(join(source, path), blob);
      sourceFiles.push(path);
      // V2 Agent/catalog resources replace the legacy location, independently
      // of Git's directory ordering.
      if (!path.startsWith(legacyPrefix) || !expected.has(target)) expected.set(target, { source: path, gitBlob: blob });
    }
  }
  if (!expected.has('assets/models-dev-catalog.json.gz') || ![...expected.keys()].some((path) => path.startsWith('assets/agents/'))) {
    throw new Error('Pinned runtime Agent and model resources are missing');
  }
  const actualSourceFiles = [
    ...filesIn(join(source, legacyPrefix)).map((path) => legacyPrefix + path).filter((path) => !path.startsWith(`${legacyPrefix}agents/`)),
    ...filesIn(join(source, agentPrefix)).map((path) => agentPrefix + path), catalogPath,
  ].sort();
  if (JSON.stringify(actualSourceFiles) !== JSON.stringify(sourceFiles.sort())) {
    throw new Error('Runtime resource source inventory differs from the pinned tree');
  }
  const { copyLocalRuntimeAssets } = await import(pathToFileURL(join(source, helperPath)).href);
  copyLocalRuntimeAssets({ repositoryRoot: source, outputDir, filter: () => true });
  const actual = filesIn(join(outputDir, 'assets')).map((path) => `assets/${path}`);
  const names = [...expected.keys()].sort();
  if (JSON.stringify(actual) !== JSON.stringify(names)) throw new Error('Runtime resource inventory differs from the pinned tree');
  const assets = names.map((path) => ({ path, ...expected.get(path), ...verifyFile(join(outputDir, path), expected.get(path).gitBlob) }));
  return { assetBuilder: { source: helperPath, gitBlob: helperBlob, ...helper }, assets };
}
