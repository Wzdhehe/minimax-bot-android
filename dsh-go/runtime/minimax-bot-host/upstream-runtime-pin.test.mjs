// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Wzdhehe
//
// Guards the upstream runtime pin.
//
// The positive cases alone would only prove that the pin is self-consistent.
// The negative cases are the ones that matter: they fail when the checker
// stops enforcing something, so a broken checker cannot keep the suite green.
//
// Run with: node --test runtime/minimax-bot-host/upstream-runtime-pin.test.mjs

import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

import {
  UPSTREAM_RUNTIME_BUNDLE_SHA256,
  UPSTREAM_RUNTIME_COMMIT,
  UPSTREAM_RUNTIME_COMMIT_DATE,
  UPSTREAM_RUNTIME_COMMIT_SUBJECT,
  UPSTREAM_RUNTIME_CONTRACT_ALIASES,
  UPSTREAM_RUNTIME_CONTRACT_FILES,
  UPSTREAM_RUNTIME_LICENSE,
  UPSTREAM_RUNTIME_PIN,
  UPSTREAM_RUNTIME_REPOSITORY,
  UPSTREAM_RUNTIME_REPOSITORY_URL,
  UPSTREAM_RUNTIME_TREE,
  UPSTREAM_RUNTIME_UPSTREAM,
  UPSTREAM_RUNTIME_UPSTREAM_URL,
  upstreamRuntimeEsbuildAliases,
} from './upstream-runtime-pin.mjs';
import {
  checkUpstreamRuntimePin,
  contractIntegrityErrors,
  pinDocumentPath,
  pinnedTreeFileError,
  readDocumentPin,
} from './upstream-runtime-pin-check.mjs';

const here = dirname(fileURLToPath(import.meta.url));

/** Creates a throwaway git checkout with one commit. */
function makeGitFixture(t, files = { 'a.txt': 'a' }) {
  const dir = mkdtempSync(join(tmpdir(), 'pin-fixture-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  execFileSync('git', ['init', '-q'], { cwd: dir });
  execFileSync('git', ['config', 'user.email', 'fixture@example.invalid'], { cwd: dir });
  execFileSync('git', ['config', 'user.name', 'fixture'], { cwd: dir });
  for (const [name, content] of Object.entries(files)) {
    const target = join(dir, name);
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, content);
  }
  execFileSync('git', ['add', '.'], { cwd: dir });
  execFileSync('git', ['commit', '-q', '-m', 'fixture'], { cwd: dir });
  return dir;
}

/**
 * Writes a pin document that keeps the real document's structure and replaces
 * only its json block, so the pin markers stay intact.
 */
function makeDocFixture(t, mutate) {
  const dir = mkdtempSync(join(tmpdir(), 'pin-doc-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const path = join(dir, 'UPSTREAM-RUNTIME.md');
  const real = readFileSync(pinDocumentPath, 'utf8');
  const block = real.match(/```json\s*([\s\S]*?)```/)[1];
  writeFileSync(path, real.replace(block, mutate(block)));
  return path;
}

test('the pin names one upstream commit with a date, a tree and a license', () => {
  assert.match(UPSTREAM_RUNTIME_COMMIT, /^[0-9a-f]{40}$/);
  assert.match(UPSTREAM_RUNTIME_COMMIT_DATE, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/);
  assert.match(UPSTREAM_RUNTIME_TREE, /^[0-9a-f]{40}$/);
  assert.ok(UPSTREAM_RUNTIME_COMMIT_SUBJECT.length > 0);
  assert.equal(UPSTREAM_RUNTIME_LICENSE, 'MIT');
  assert.equal(UPSTREAM_RUNTIME_REPOSITORY, 'mcode-webui/minimax-code');
  assert.equal(UPSTREAM_RUNTIME_REPOSITORY_URL, `https://github.com/${UPSTREAM_RUNTIME_REPOSITORY}`);
  assert.equal(UPSTREAM_RUNTIME_UPSTREAM, 'MiniMax-AI/minimax-code');
  assert.equal(UPSTREAM_RUNTIME_UPSTREAM_URL, `https://github.com/${UPSTREAM_RUNTIME_UPSTREAM}`);
});

test('the pin document repeats the pin verbatim', () => {
  const documentText = readFileSync(pinDocumentPath, 'utf8');
  const result = readDocumentPin(documentText);
  assert.equal(result.error, undefined, `pin document is not readable: ${result.error}`);
  assert.deepEqual(result.pin, UPSTREAM_RUNTIME_PIN);
});

test('the pin document names the pinned commit and date in prose too', () => {
  const documentText = readFileSync(pinDocumentPath, 'utf8');
  assert.ok(documentText.includes(UPSTREAM_RUNTIME_COMMIT), 'the commit is not mentioned in prose');
  assert.ok(documentText.includes(UPSTREAM_RUNTIME_COMMIT_DATE), 'the commit date is not mentioned in prose');
  assert.ok(documentText.includes(UPSTREAM_RUNTIME_TREE), 'the tree is not mentioned in prose');
});

test('the contract aliases and the contract file list agree', () => {
  assert.deepEqual(
    UPSTREAM_RUNTIME_CONTRACT_FILES,
    UPSTREAM_RUNTIME_CONTRACT_ALIASES.map((entry) => entry.path),
  );
  assert.equal(new Set(UPSTREAM_RUNTIME_CONTRACT_FILES).size, UPSTREAM_RUNTIME_CONTRACT_FILES.length);
  for (const { alias, path } of UPSTREAM_RUNTIME_CONTRACT_ALIASES) {
    assert.match(alias, /^@/, `alias must be a package specifier: ${alias}`);
    assert.match(path, /^packages\/.+\.ts$/, `contract path must be a source file: ${path}`);
  }
});

test('the contract list is non-empty and every contract file has a fingerprint', () => {
  assert.ok(UPSTREAM_RUNTIME_CONTRACT_FILES.length > 0, 'the contract list must not be empty');
  for (const path of UPSTREAM_RUNTIME_CONTRACT_FILES) {
    assert.ok(UPSTREAM_RUNTIME_BUNDLE_SHA256[path], `contract file has no fingerprint: ${path}`);
  }
  assert.ok(Object.keys(UPSTREAM_RUNTIME_BUNDLE_SHA256).length >= UPSTREAM_RUNTIME_CONTRACT_FILES.length);
});

test('an emptied contract list is rejected', () => {
  const errors = contractIntegrityErrors({
    contractFiles: [],
    bundleSha256: UPSTREAM_RUNTIME_BUNDLE_SHA256,
    aliases: [],
  });
  assert.ok(errors.some((error) => error.includes('contract file list is empty')));
});

test('a contract file without a fingerprint is rejected', () => {
  const errors = contractIntegrityErrors({
    contractFiles: [...UPSTREAM_RUNTIME_CONTRACT_FILES, 'packages/shared/src/not-pinned.ts'],
    bundleSha256: UPSTREAM_RUNTIME_BUNDLE_SHA256,
    aliases: UPSTREAM_RUNTIME_CONTRACT_ALIASES,
  });
  assert.ok(errors.some((error) => error.includes('no pinned fingerprint')));
});

test('the esbuild alias arguments follow the pin', () => {
  const args = upstreamRuntimeEsbuildAliases('/tmp/webui');
  assert.equal(args.length, UPSTREAM_RUNTIME_CONTRACT_ALIASES.length);
  for (const [index, { alias, path }] of UPSTREAM_RUNTIME_CONTRACT_ALIASES.entries()) {
    assert.equal(args[index], `--alias:${alias}=/tmp/webui/${path}`);
  }
});

test('the checker reports a consistent pin when no source is given', () => {
  const result = checkUpstreamRuntimePin();
  assert.deepEqual(result.errors, []);
  assert.equal(result.ok, true);
});

test('the checker rejects a missing source', () => {
  const result = checkUpstreamRuntimePin({ sourceDir: join(here, 'no-such-webui-checkout') });
  assert.equal(result.ok, false);
  assert.ok(result.errors.some((error) => error.includes('does not exist')));
});

test('the checker rejects a source that is not at the pinned commit', (t) => {
  const source = makeGitFixture(t);
  const result = checkUpstreamRuntimePin({ sourceDir: source });
  assert.equal(result.ok, false);
  assert.ok(
    result.errors.some((error) => error.includes(`expected ${UPSTREAM_RUNTIME_COMMIT}`)),
    `expected a commit mismatch, got: ${result.errors.join(' | ')}`,
  );
});

test('the checker rejects a source whose working tree was modified', (t) => {
  const source = makeGitFixture(t);
  writeFileSync(join(source, 'a.txt'), 'modified after the commit');
  const result = checkUpstreamRuntimePin({ sourceDir: source });
  assert.equal(result.ok, false);
  assert.ok(
    result.errors.some((error) => error.includes('local modifications')),
    `expected a dirty working tree error, got: ${result.errors.join(' | ')}`,
  );
});

test('the checker rejects upstream files whose bytes do not match the pin', (t) => {
  const source = makeGitFixture(t, {
    'packages/shared/src/miniapp-surface.ts': 'not the pinned upstream file',
  });
  const result = checkUpstreamRuntimePin({ sourceDir: source });
  assert.equal(result.ok, false);
  assert.ok(
    result.errors.some((error) => error.includes('does not match the pinned bytes')),
    `expected a fingerprint mismatch, got: ${result.errors.join(' | ')}`,
  );
});

test('an upstream file outside the fingerprint list is accepted when the pinned tree holds the same bytes', (t) => {
  const source = makeGitFixture(t, { 'packages/extra/runtime.ts': 'export const extra = 1;\n' });
  const tree = execFileSync('git', ['rev-parse', 'HEAD^{tree}'], { cwd: source, encoding: 'utf8' }).trim();
  assert.equal(pinnedTreeFileError({ source, tree, path: 'packages/extra/runtime.ts' }), null);
});

test('an upstream file that the pinned tree does not hold is refused', (t) => {
  const source = makeGitFixture(t, { 'packages/extra/runtime.ts': 'export const extra = 1;\n' });
  const tree = execFileSync('git', ['rev-parse', 'HEAD^{tree}'], { cwd: source, encoding: 'utf8' }).trim();
  const error = pinnedTreeFileError({ source, tree, path: 'packages/extra/other.ts' });
  assert.ok(error && error.includes('not in the pinned tree'), `expected a tree refusal, got: ${error}`);
});

test('an upstream file whose bytes changed after the commit is refused', (t) => {
  const source = makeGitFixture(t, { 'packages/extra/runtime.ts': 'export const extra = 1;\n' });
  const tree = execFileSync('git', ['rev-parse', 'HEAD^{tree}'], { cwd: source, encoding: 'utf8' }).trim();
  writeFileSync(join(source, 'packages/extra/runtime.ts'), 'export const extra = 2;\n');
  const error = pinnedTreeFileError({ source, tree, path: 'packages/extra/runtime.ts' });
  assert.ok(error && error.includes('does not match the pinned tree bytes'), `expected a byte refusal, got: ${error}`);
});

test('an upstream file is not verified without a source checkout', () => {
  const error = pinnedTreeFileError({ source: '', tree: UPSTREAM_RUNTIME_TREE, path: 'packages/shared/src/miniapp-surface.ts' });
  assert.ok(error && error.includes('without a source checkout'), `expected a missing-source refusal, got: ${error}`);
});

test('the checker rejects a pin document with a changed field', (t) => {
  const docPath = makeDocFixture(t, (block) =>
    block.replace(UPSTREAM_RUNTIME_COMMIT, 'a'.repeat(40)));
  const result = checkUpstreamRuntimePin({ docPath });
  assert.equal(result.ok, false);
  assert.ok(
    result.errors.some((error) => error.includes("pin document 'commit'")),
    `expected a commit field error, got: ${result.errors.join(' | ')}`,
  );
});

test('the checker rejects a pin document with an extra field', (t) => {
  const docPath = makeDocFixture(t, (block) =>
    block.replace('{\n', '{\n  "sneaky": "value",\n'));
  const result = checkUpstreamRuntimePin({ docPath });
  assert.equal(result.ok, false);
  assert.ok(
    result.errors.some((error) => error.includes("extra field 'sneaky'")),
    `expected an extra field error, got: ${result.errors.join(' | ')}`,
  );
});

test('the checker rejects a pin document with a repeated key', (t) => {
  const docPath = makeDocFixture(t, (block) =>
    block.replace(`"commit": "${UPSTREAM_RUNTIME_COMMIT}"`, `"commit": "${'b'.repeat(40)}", "commit": "${UPSTREAM_RUNTIME_COMMIT}"`));
  const result = checkUpstreamRuntimePin({ docPath });
  assert.equal(result.ok, false);
  assert.ok(
    result.errors.some((error) => error.includes("repeats the key 'commit'")),
    `expected a repeated key error, got: ${result.errors.join(' | ')}`,
  );
});

test('the checker rejects a pin document without a pin block', (t) => {
  const dir = mkdtempSync(join(tmpdir(), 'pin-doc-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const docPath = join(dir, 'UPSTREAM-RUNTIME.md');
  writeFileSync(docPath, '# 上游 Runtime 钉点\n\n这里没有 pin 块。\n');
  const result = checkUpstreamRuntimePin({ docPath });
  assert.equal(result.ok, false);
  assert.ok(
    result.errors.some((error) => error.includes('no pin:begin / pin:end block')),
    `expected a missing block error, got: ${result.errors.join(' | ')}`,
  );
});

test('the checker rejects a malformed pin block', (t) => {
  const docPath = makeDocFixture(t, () => '<!-- pin:begin -->\n```json\n{ not json\n```\n<!-- pin:end -->\n');
  const result = checkUpstreamRuntimePin({ docPath });
  assert.equal(result.ok, false);
  assert.ok(
    result.errors.some((error) => error.includes('not valid json')),
    `expected a malformed block error, got: ${result.errors.join(' | ')}`,
  );
});

test('importing the checker instead of running it fails loudly', () => {
  const result = spawnSync(
    process.execPath,
    ['--input-type=module', '-e', `await import(${JSON.stringify(join(here, 'upstream-runtime-pin-check.mjs'))});`],
    { encoding: 'utf8' },
  );
  assert.notEqual(result.status, 0, 'importing the checker must not exit 0 in silence');
  assert.match(result.stderr, /FAIL/);
});
