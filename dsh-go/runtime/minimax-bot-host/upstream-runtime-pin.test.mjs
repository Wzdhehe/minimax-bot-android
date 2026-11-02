// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Wzdhehe
//
// Guards the upstream runtime pin. The pin must stay consistent across the
// pin file, the pin document and the alias mapping the smoke derives from it.
//
// Run with: node --test runtime/minimax-bot-host/upstream-runtime-pin.test.mjs

import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';

import {
  UPSTREAM_RUNTIME_COMMIT,
  UPSTREAM_RUNTIME_COMMIT_DATE,
  UPSTREAM_RUNTIME_CONTRACT_ALIASES,
  UPSTREAM_RUNTIME_CONTRACT_FILES,
  UPSTREAM_RUNTIME_LICENSE,
  UPSTREAM_RUNTIME_PIN,
  UPSTREAM_RUNTIME_REPOSITORY,
  UPSTREAM_RUNTIME_REPOSITORY_URL,
  UPSTREAM_RUNTIME_UPSTREAM,
  UPSTREAM_RUNTIME_UPSTREAM_URL,
  upstreamRuntimeEsbuildAliases,
} from './upstream-runtime-pin.mjs';
import { checkUpstreamRuntimePin, pinDocumentPath, readDocumentPin } from './upstream-runtime-pin-check.mjs';

test('the pin names one upstream commit with a date and a license', () => {
  assert.match(UPSTREAM_RUNTIME_COMMIT, /^[0-9a-f]{40}$/);
  assert.match(UPSTREAM_RUNTIME_COMMIT_DATE, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/);
  assert.equal(UPSTREAM_RUNTIME_LICENSE, 'MIT');
  assert.equal(UPSTREAM_RUNTIME_REPOSITORY, 'mcode-webui/minimax-code');
  assert.equal(UPSTREAM_RUNTIME_REPOSITORY_URL, `https://github.com/${UPSTREAM_RUNTIME_REPOSITORY}`);
  assert.equal(UPSTREAM_RUNTIME_UPSTREAM, 'MiniMax-AI/minimax-code');
  assert.equal(UPSTREAM_RUNTIME_UPSTREAM_URL, `https://github.com/${UPSTREAM_RUNTIME_UPSTREAM}`);
  assert.ok(UPSTREAM_RUNTIME_COMMIT.startsWith(UPSTREAM_RUNTIME_COMMIT.slice(0, 7)));
});

test('the pin document repeats the pin verbatim', () => {
  assert.ok(existsSync(pinDocumentPath), `pin document is missing: ${pinDocumentPath}`);
  const documentPin = readDocumentPin(readFileSync(pinDocumentPath, 'utf8'));
  assert.ok(documentPin, 'pin document has no readable pin block');
  for (const [field, expected] of Object.entries({
    repository: UPSTREAM_RUNTIME_REPOSITORY,
    repositoryUrl: UPSTREAM_RUNTIME_REPOSITORY_URL,
    upstream: UPSTREAM_RUNTIME_UPSTREAM,
    upstreamUrl: UPSTREAM_RUNTIME_UPSTREAM_URL,
    commit: UPSTREAM_RUNTIME_COMMIT,
    commitDate: UPSTREAM_RUNTIME_COMMIT_DATE,
    license: UPSTREAM_RUNTIME_LICENSE,
  })) {
    assert.equal(documentPin[field], expected, `pin document '${field}' does not match the pin file`);
  }
});

test('the pin document names the pinned commit in prose too', () => {
  const documentText = readFileSync(pinDocumentPath, 'utf8');
  assert.ok(
    documentText.includes(UPSTREAM_RUNTIME_COMMIT),
    'pin document does not mention the pinned commit',
  );
  assert.ok(
    documentText.includes(UPSTREAM_RUNTIME_COMMIT_DATE),
    'pin document does not mention the pinned commit date',
  );
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
  assert.equal(result.pin.commit, UPSTREAM_RUNTIME_COMMIT);
});

test('the checker rejects a source that is not at the pinned commit', () => {
  const result = checkUpstreamRuntimePin({ sourceDir: join(import.meta.dirname, 'nonexistent-webui-checkout') });
  assert.equal(result.ok, false);
  assert.ok(result.errors.some((error) => error.includes('does not exist')));
});

test('the pin object is a frozen summary of the exported constants', () => {
  assert.equal(UPSTREAM_RUNTIME_PIN.commit, UPSTREAM_RUNTIME_COMMIT);
  assert.equal(UPSTREAM_RUNTIME_PIN.commitDate, UPSTREAM_RUNTIME_COMMIT_DATE);
  assert.equal(UPSTREAM_RUNTIME_PIN.license, UPSTREAM_RUNTIME_LICENSE);
  assert.equal(UPSTREAM_RUNTIME_PIN.repository, UPSTREAM_RUNTIME_REPOSITORY);
  assert.ok(Object.isFrozen(UPSTREAM_RUNTIME_PIN));
});
