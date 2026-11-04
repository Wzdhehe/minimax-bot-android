// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import { after, test } from 'node:test';
import { chmodSync, fstatSync, linkSync, mkdirSync, mkdtempSync, openSync, readFileSync, rmSync, statSync, symlinkSync, unlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  RUNTIME_DESCRIPTOR_FILENAME,
  assertDescriptorPathAllowed,
  buildRuntimeEnv,
  catalogFromDescriptor,
  createRuntimeDescriptor,
  readRuntimeDescriptor,
  readRuntimeDescriptorFd,
  writeRuntimeDescriptor,
} from './runtime-descriptor.mjs';

const fixtureRoots = [];
after(() => { for (const root of fixtureRoots) rmSync(root, { recursive: true, force: true }); });

const PIN = '97818b2890b4acae1c6bd1200a226ff506680628';

function makeDirs() {
  const root = mkdtempSync(join(tmpdir(), 'mbot-descriptor-'));
  fixtureRoots.push(root);
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

test('a transferred descriptor is read by FD using its trusted owner UID and then closed', () => {
  const { priv } = makeDirs();
  const descriptor = createRuntimeDescriptor({ entryPath: '/private/entry.mjs', pinCommit: PIN, port: 43117,
    token: 'a'.repeat(64), catalog: { providers: [{ providerId: 'p', models: ['m'] }] } });
  const path = writeRuntimeDescriptor({ dir: priv, descriptor, allowedRoots: [priv] });
  const fd = openSync(path, 'r');
  assert.deepEqual(readRuntimeDescriptorFd({ fd, expectedOwnerUid: process.getuid() }), descriptor);
  assert.throws(() => fstatSync(fd), (error) => error.code === 'EBADF');
});

test('a transferred FD keeps its original generation after atomic descriptor replacement', () => {
  const { priv } = makeDirs();
  const old = createRuntimeDescriptor({ entryPath: '/private/entry.mjs', pinCommit: PIN, port: 43117, token: 'a'.repeat(64) });
  const path = writeRuntimeDescriptor({ dir: priv, descriptor: old, allowedRoots: [priv] });
  const fd = openSync(path, 'r');
  const newer = createRuntimeDescriptor({ ...old, port: 43118, token: 'b'.repeat(64) });
  writeRuntimeDescriptor({ dir: priv, descriptor: newer, allowedRoots: [priv] });
  assert.deepEqual(readRuntimeDescriptorFd({ fd, expectedOwnerUid: process.getuid() }), old);
  assert.deepEqual(readRuntimeDescriptor({ path, allowedRoots: [priv] }), newer);
});

test('transferred descriptors reject incorrect ownership, unsafe modes, extra fields and oversized data', () => {
  const { priv } = makeDirs();
  const descriptor = createRuntimeDescriptor({ entryPath: '/private/entry.mjs', pinCommit: PIN, port: 43117, token: 'a'.repeat(64) });
  const path = writeRuntimeDescriptor({ dir: priv, descriptor, allowedRoots: [priv] });
  const uid = process.getuid();
  let fd = openSync(path, 'r');
  assert.throws(() => readRuntimeDescriptorFd({ fd, expectedOwnerUid: uid + 1 }), (error) => error.code === 'descriptor_permissions');
  assert.throws(() => fstatSync(fd), (error) => error.code === 'EBADF');
  chmodSync(path, 0o644);
  fd = openSync(path, 'r');
  assert.throws(() => readRuntimeDescriptorFd({ fd, expectedOwnerUid: uid }), (error) => error.code === 'descriptor_permissions');
  chmodSync(path, 0o600);
  writeFileSync(path, JSON.stringify({ ...descriptor, apiKey: 'fixture-must-not-cross' }));
  fd = openSync(path, 'r');
  assert.throws(() => readRuntimeDescriptorFd({ fd, expectedOwnerUid: uid }), (error) => error.code === 'descriptor_invalid');
  writeFileSync(path, JSON.stringify({ ...descriptor, catalog: { providers: [{ providerId: 'p', baseUrl: 'https://example.invalid' }] } }));
  fd = openSync(path, 'r');
  assert.throws(() => readRuntimeDescriptorFd({ fd, expectedOwnerUid: uid }), (error) => error.code === 'descriptor_invalid');
  writeFileSync(path, Buffer.from([0xff, 0xfe]));
  fd = openSync(path, 'r');
  assert.throws(() => readRuntimeDescriptorFd({ fd, expectedOwnerUid: uid }), (error) => error.code === 'descriptor_invalid');
  assert.throws(() => fstatSync(fd), (error) => error.code === 'EBADF');
  writeFileSync(path, Buffer.alloc(1024 * 1024 + 1, 0x20));
  fd = openSync(path, 'r');
  assert.throws(() => readRuntimeDescriptorFd({ fd, expectedOwnerUid: uid }), (error) => error.code === 'descriptor_invalid');
  fd = openSync(priv, 'r');
  assert.throws(() => readRuntimeDescriptorFd({ fd, expectedOwnerUid: uid }), (error) => error.code === 'descriptor_not_a_regular_file');
  const alias = join(priv, 'hardlink.json');
  linkSync(path, alias);
  fd = openSync(path, 'r');
  assert.throws(() => readRuntimeDescriptorFd({ fd, expectedOwnerUid: uid }), (error) => error.code === 'descriptor_not_a_regular_file');
  unlinkSync(alias);
});

test('the descriptor carries an optional loopback broker session for model requests', () => {
  const { priv } = makeDirs();
  const broker = { baseUrl: 'http://127.0.0.1:43117', token: 'a'.repeat(64) };
  const withBroker = createRuntimeDescriptor({ entryPath: '/x/entry.mjs', pinCommit: PIN, port: 8080, token: 'b'.repeat(64), broker });
  assert.deepEqual(withBroker.broker, broker);
  const path = writeRuntimeDescriptor({ dir: priv, descriptor: withBroker, allowedRoots: [priv] });
  assert.deepEqual(readRuntimeDescriptor({ path, allowedRoots: [priv] }), withBroker);
  // A descriptor without a broker session stays valid: the guest may run
  // without model requests.
  const without = createRuntimeDescriptor({ entryPath: '/x/entry.mjs', pinCommit: PIN, port: 8080, token: 'b'.repeat(64) });
  assert.equal(without.broker, null);
  // A broker that is not loopback, or whose token is not a session token, is
  // refused before anything is written.
  assert.throws(() => createRuntimeDescriptor({ entryPath: '/x', pinCommit: PIN, port: 8080, token: 'b'.repeat(64), broker: { baseUrl: 'http://10.0.0.1:80', token: 'a'.repeat(64) } }), /loopback broker/);
  assert.throws(() => createRuntimeDescriptor({ entryPath: '/x', pinCommit: PIN, port: 8080, token: 'b'.repeat(64), broker: { baseUrl: 'http://127.0.0.1:80', token: 'short' } }), /broker session token/);
});

test('the descriptor carries a non-secret provider catalog', () => {
  const descriptor = createRuntimeDescriptor({
    entryPath: '/data/app/private/runtime/entry.mjs', pinCommit: PIN, port: 8080, token: 'b'.repeat(64),
    catalog: {
      providers: [{ providerId: 'minimax', name: 'MiniMax', apiFormat: 'openai-completions', enabled: true, models: ['m1'] }],
      defaultProviderId: 'minimax', defaultModelId: 'm1',
    },
  });
  assert.equal(descriptor.catalog.providers[0].providerId, 'minimax');
  assert.equal(descriptor.catalog.defaultModelId, 'm1');
  assert.equal(Object.isFrozen(descriptor.catalog), true);
  assert.equal(Object.isFrozen(descriptor.catalog.providers), true);
  assert.equal(Object.isFrozen(descriptor.catalog.providers[0]), true);
  // A descriptor without a catalog stays valid: the guest may run without one.
  assert.equal(createRuntimeDescriptor({ entryPath: '/x/entry.mjs', pinCommit: PIN, port: 8080, token: 'b'.repeat(64) }).catalog, null);
});

test('the descriptor refuses a catalog that carries a credential or a real endpoint', () => {
  const base = { entryPath: '/x/entry.mjs', pinCommit: PIN, port: 8080, token: 'b'.repeat(64) };
  assert.throws(() => createRuntimeDescriptor({ ...base, catalog: { providers: [{ providerId: 'p', apiKey: 'sk-live' }] } }), /descriptor_catalog_contains_credential/);
  assert.throws(() => createRuntimeDescriptor({ ...base, catalog: { providers: [{ providerId: 'p', options: { api_key: 'sk-live' } }] } }), /descriptor_catalog_contains_credential/);
  assert.throws(() => createRuntimeDescriptor({ ...base, catalog: { providers: [{ providerId: 'p', baseUrl: 'https://api.minimax.io/v1' }] } }), /descriptor_catalog_forbidden_field/);
  assert.throws(() => createRuntimeDescriptor({ ...base, catalog: { providers: [{ providerId: 'p', hasApiKey: 'yes' }] } }), /descriptor_catalog_contains_credential/);
  assert.throws(() => createRuntimeDescriptor({ ...base, catalog: 'not-a-catalog' }), /descriptor_catalog_invalid/);
  // The non-secret presence flag is allowed.
  const ok = createRuntimeDescriptor({ ...base, catalog: { providers: [{ providerId: 'p', hasApiKey: true }] } });
  assert.equal(ok.catalog.providers[0].hasApiKey, true);
});

test('the guest reads the catalog from the descriptor it validated itself', () => {
  const descriptor = createRuntimeDescriptor({
    entryPath: '/x/entry.mjs', pinCommit: PIN, port: 8080, token: 'b'.repeat(64),
    catalog: {
      providers: [{ providerId: 'minimax', apiFormat: 'openai-completions', enabled: true, models: ['m1'] }],
      defaultProviderId: 'minimax', defaultModelId: 'm1',
    },
  });
  assert.deepEqual(catalogFromDescriptor(descriptor), descriptor.catalog);
  // A descriptor without a catalog leaves the guest without one.
  assert.equal(catalogFromDescriptor(null), null);
  assert.equal(catalogFromDescriptor(undefined), null);
  assert.equal(catalogFromDescriptor(createRuntimeDescriptor({ entryPath: '/x/entry.mjs', pinCommit: PIN, port: 8080, token: 'b'.repeat(64) })), null);
  // A hand-built descriptor cannot widen the boundary.
  assert.throws(() => catalogFromDescriptor({ ...descriptor, catalog: { providers: [{ providerId: 'p', apiKey: 'sk-live' }] } }), /descriptor_catalog_contains_credential/);
  assert.throws(() => catalogFromDescriptor({ ...descriptor, catalog: { providers: [{ providerId: 'p', baseUrl: 'https://api.minimax.io/v1' }] } }), /descriptor_catalog_forbidden_field/);
});

test('the descriptor round-trips through the private directory with owner-only permissions', () => {
  const { priv } = makeDirs();
  const descriptor = createRuntimeDescriptor({
    entryPath: join(priv, 'runtime', 'entry.mjs'), pinCommit: PIN, port: 9123, token: 'c'.repeat(64),
    catalog: {
      providers: [{ providerId: 'minimax', apiFormat: 'openai-completions', enabled: true, models: ['m1'] }],
      defaultProviderId: 'minimax', defaultModelId: 'm1',
    },
  });
  const path = writeRuntimeDescriptor({ dir: priv, descriptor, allowedRoots: [priv] });
  assert.equal(path, join(priv, RUNTIME_DESCRIPTOR_FILENAME));
  assert.deepEqual(readRuntimeDescriptor({ path, allowedRoots: [priv] }), descriptor);
  assert.equal(readRuntimeDescriptor({ path, allowedRoots: [priv] }).catalog.defaultModelId, 'm1');
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


test('Node rejects broker ports and token lengths the Kotlin descriptor writer cannot issue', () => {
  const valid = { entryPath: '/private/entry.mjs', pinCommit: PIN, port: 43117, token: 'a'.repeat(64),
    broker: { baseUrl: 'http://127.0.0.1:43118', token: 'b'.repeat(64) } };
  for (const baseUrl of ['http://127.0.0.1:0', 'http://127.0.0.1:65536', 'http://127.0.0.1:99999', 'http://127.0.0.1:00080']) {
    assert.throws(() => createRuntimeDescriptor({ ...valid, broker: { ...valid.broker, baseUrl } }), /loopback broker/);
  }
  assert.throws(() => createRuntimeDescriptor({ ...valid, token: 'a'.repeat(65) }), /session token/);
  assert.throws(() => createRuntimeDescriptor({ ...valid, broker: { ...valid.broker, token: 'b'.repeat(65) } }), /broker session token/);
});


test('publishing a descriptor replaces a planted leaf symlink without writing its workspace target', () => {
  const { priv, workspace } = makeDirs();
  mkdirSync(workspace, { recursive: true });
  const project = join(workspace, 'project.txt');
  writeFileSync(project, 'must stay unchanged', { mode: 0o600 });
  const target = join(priv, RUNTIME_DESCRIPTOR_FILENAME);
  symlinkSync(project, target);
  const descriptor = createRuntimeDescriptor({ entryPath: '/private/entry.mjs', pinCommit: PIN, port: 43117, token: 'a'.repeat(64) });
  writeRuntimeDescriptor({ dir: priv, descriptor, allowedRoots: [priv], workspaceRoot: workspace });
  assert.equal(readFileSync(project, 'utf8'), 'must stay unchanged');
  assert.deepEqual(readRuntimeDescriptor({ path: target, allowedRoots: [priv], workspaceRoot: workspace }), descriptor);
});

test('a parent symlink cannot redirect descriptor reads or writes into the workspace or outside private roots', () => {
  const { root, priv, workspace } = makeDirs();
  mkdirSync(workspace, { recursive: true });
  const outside = join(root, 'outside');
  mkdirSync(outside);
  const descriptor = createRuntimeDescriptor({ entryPath: '/private/entry.mjs', pinCommit: PIN, port: 43117, token: 'a'.repeat(64) });
  for (const [name, directory] of [['workspace-link', workspace], ['outside-link', outside]]) {
    const alias = join(priv, name);
    symlinkSync(directory, alias, 'dir');
    const target = join(directory, RUNTIME_DESCRIPTOR_FILENAME);
    const initial = JSON.stringify(descriptor);
    writeFileSync(target, initial, { mode: 0o600 });
    const guard = { allowedRoots: [priv], workspaceRoot: workspace };
    assert.throws(() => readRuntimeDescriptor({ path: join(alias, RUNTIME_DESCRIPTOR_FILENAME), ...guard }),
      (error) => error.code === 'descriptor_path_rejected');
    assert.throws(() => writeRuntimeDescriptor({ dir: alias, descriptor, ...guard }),
      (error) => error.code === 'descriptor_path_rejected');
    assert.equal(readFileSync(target, 'utf8'), initial);
  }
});

test('writer validates and projects descriptor fields before serializing anything', () => {
  const { priv } = makeDirs();
  const descriptor = createRuntimeDescriptor({ entryPath: '/private/entry.mjs', pinCommit: PIN, port: 43117, token: 'a'.repeat(64) });
  const path = writeRuntimeDescriptor({ dir: priv, descriptor: { ...descriptor, apiKey: 'test-key-must-not-be-written' }, allowedRoots: [priv] });
  assert.ok(!readFileSync(path, 'utf8').includes('test-key-must-not-be-written'));
  assert.deepEqual(readRuntimeDescriptor({ path, allowedRoots: [priv] }), descriptor);
});


test('extra environment values use the same allowlist and cannot introduce a secret-bearing configuration variable', () => {
  const env = buildRuntimeEnv({ baseEnv: { PATH: '/usr/bin', HOME: '/private' }, extra: {
    CONFIG_JSON: '{"apiKey":"fixture-secret-must-not-inherit"}',
    NODE_EXTRA: 'fixture-secret-must-not-inherit',
    LANG: 'zh_CN.UTF-8',
  } });
  assert.deepEqual(env, { PATH: '/usr/bin', HOME: '/private', LANG: 'zh_CN.UTF-8' });
});
