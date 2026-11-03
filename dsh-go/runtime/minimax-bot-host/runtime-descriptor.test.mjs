// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { chmodSync, mkdirSync, mkdtempSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  RUNTIME_DESCRIPTOR_FILENAME,
  assertDescriptorPathAllowed,
  buildRuntimeEnv,
  createRuntimeDescriptor,
  readRuntimeDescriptor,
  writeRuntimeDescriptor,
} from './runtime-descriptor.mjs';

const PIN = '97818b2890b4acae1c6bd1200a226ff506680628';

function makeDirs() {
  const root = mkdtempSync(join(tmpdir(), 'mbot-descriptor-'));
  const priv = join(root, 'private');
  const workspace = join(root, 'private', 'workspace');
  return { root, priv, workspace };
}

test('a descriptor needs an absolute entry, a pinned commit, a port and a token', () => {
  const good = createRuntimeDescriptor({ entryPath: '/data/app/private/runtime/entry.mjs', pinCommit: PIN, port: 8080, token: 'b'.repeat(64) });
  assert.equal(good.pinCommit, PIN);
  assert.throws(() => createRuntimeDescriptor({ entryPath: 'relative.mjs', pinCommit: PIN, port: 8080, token: 'b'.repeat(64) }), /absolute/);
  assert.throws(() => createRuntimeDescriptor({ entryPath: '/x', pinCommit: '97818b2', port: 8080, token: 'b'.repeat(64) }), /pinned/);
  assert.throws(() => createRuntimeDescriptor({ entryPath: '/x', pinCommit: PIN, port: 0, token: 'b'.repeat(64) }), /port/);
  assert.throws(() => createRuntimeDescriptor({ entryPath: '/x', pinCommit: PIN, port: 80, token: 'short' }), /token/);
});

test('the descriptor round-trips through the private directory with owner-only permissions', () => {
  const { priv } = makeDirs();
  const descriptor = createRuntimeDescriptor({ entryPath: join(priv, 'runtime', 'entry.mjs'), pinCommit: PIN, port: 9123, token: 'c'.repeat(64) });
  const path = writeRuntimeDescriptor({ dir: priv, descriptor, allowedRoots: [priv] });
  assert.equal(path, join(priv, RUNTIME_DESCRIPTOR_FILENAME));
  assert.deepEqual(readRuntimeDescriptor({ path, allowedRoots: [priv] }), descriptor);
  const mode = statSync(path).mode & 0o777;
  assert.equal(mode, 0o600);
});

test('a descriptor from the workspace, from outside the private roots or behind a symlink is rejected', () => {
  const { root, priv, workspace } = makeDirs();
  const descriptor = createRuntimeDescriptor({ entryPath: join(priv, 'runtime', 'entry.mjs'), pinCommit: PIN, port: 9123, token: 'd'.repeat(64) });
  const path = writeRuntimeDescriptor({ dir: priv, descriptor, allowedRoots: [priv] });

  // A descriptor that lives inside the agent workspace is never trusted.
  assert.throws(() => assertDescriptorPathAllowed(join(workspace, RUNTIME_DESCRIPTOR_FILENAME), { allowedRoots: [priv], workspaceRoot: workspace }), (error) => error.code === 'descriptor_path_rejected');
  // A descriptor outside the private roots is not the host's file.
  assert.throws(() => assertDescriptorPathAllowed(join(root, 'elsewhere.json'), { allowedRoots: [priv] }), (error) => error.code === 'descriptor_path_rejected');
  // A symlink in the private directory pointing at an outside file is refused.
  const link = join(priv, 'link.json');
  symlinkSync(join(root, 'elsewhere.json'), link);
  assert.throws(() => readRuntimeDescriptor({ path: link, allowedRoots: [priv] }), (error) => error.code === 'descriptor_not_a_regular_file');
  // A group or world readable descriptor is refused.
  chmodSync(path, 0o644);
  assert.throws(() => readRuntimeDescriptor({ path, allowedRoots: [priv] }), (error) => error.code === 'descriptor_permissions');
});

test('the descriptor file is never written into the workspace', () => {
  const { priv, workspace } = makeDirs();
  const descriptor = createRuntimeDescriptor({ entryPath: join(priv, 'entry.mjs'), pinCommit: PIN, port: 9123, token: 'e'.repeat(64) });
  assert.throws(() => writeRuntimeDescriptor({ dir: workspace, descriptor, allowedRoots: [priv], workspaceRoot: workspace }), (error) => error.code === 'descriptor_path_rejected');
});

test('a malformed descriptor file is rejected instead of being half-trusted', () => {
  const { priv } = makeDirs();
  const path = join(priv, RUNTIME_DESCRIPTOR_FILENAME);
  mkdirSync(priv, { recursive: true });
  writeFileSync(path, JSON.stringify({ entryPath: 'relative', pinCommit: PIN, port: 1, token: 'f'.repeat(64) }));
  chmodSync(path, 0o600);
  assert.throws(() => readRuntimeDescriptor({ path, allowedRoots: [priv] }), (error) => error.code === 'descriptor_invalid');
});

test('the runtime environment is built from an allowlist, so no key or token rides along', () => {
  const env = buildRuntimeEnv({
    baseEnv: {
      PATH: '/usr/bin',
      HOME: '/data/app',
      MINIMAX_API_KEY: 'super-secret-key',
      OPENAI_API_KEY: 'super-secret-key',
      SESSION_TOKEN: 'token-value',
      DB_PASSWORD: 'hunter2',
    },
    extra: { NODE_PATH: '/data/app/node_modules', MINIMAX_API_KEY: 'super-secret-key' },
  });
  assert.deepEqual(env, { PATH: '/usr/bin', HOME: '/data/app', NODE_PATH: '/data/app/node_modules' });
  assert.ok(!JSON.stringify(env).includes('super-secret-key'));
  assert.ok(!JSON.stringify(env).includes('token-value'));
});
