// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { closeSync, constants, mkdtempSync, openSync, readFileSync, rmSync } from 'node:fs';
import { createServer, connect } from 'node:net';
import { once } from 'node:events';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { buildRuntimeEnv } from '../../runtime/minimax-bot-host/runtime-descriptor.mjs';
import { writeRuntimeDescriptor } from '../../runtime/minimax-bot-host/runtime-descriptor.mjs';
import { UPSTREAM_RUNTIME_COMMIT } from '../../runtime/minimax-bot-host/upstream-runtime-pin.mjs';

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
  assert.match(result.stdout, /descriptor read-back by path and FD/);
  assert.match(result.stdout, /unbound model turn refused/);
  assert.ok(!result.stdout.includes('native-broker-placeholder'));
  t.diagnostic(result.stdout.match(/PASS: real fork factory[^\n]*/)[0]);
});

test('the bound FD starter boots the actual runtime and authenticates Bot requests without guest listening', { timeout: 30_000 }, async (t) => {
  const { bundle } = JSON.parse(readFileSync(join(here, '.build/host-probe.json')));
  const root = mkdtempSync(join(tmpdir(), 'mbot-real-fd-start-'));
  const peers = [], owners = [];
  const blocker = createServer();
  const unix = createServer((socket) => { peers.push(socket); socket.on('error', () => {}); });
  let child, descriptorFd;
  t.after(async () => {
    child?.kill('SIGKILL');
    owners.forEach((socket) => socket.destroy()); peers.forEach((socket) => socket.destroy());
    if (descriptorFd !== undefined) closeSync(descriptorFd);
    if (unix.listening) await new Promise((resolve) => unix.close(resolve));
    if (blocker.listening) await new Promise((resolve) => blocker.close(resolve));
    rmSync(root, { recursive: true, force: true });
  });
  blocker.listen(0, '127.0.0.1'); await once(blocker, 'listening');
  const port = blocker.address().port;
  unix.listen(join(root, 'handoff.sock')); await once(unix, 'listening');
  for (let index = 0; index < 2; index++) {
    const accepted = once(unix, 'connection');
    const owner = connect(join(root, 'handoff.sock')); owners.push(owner);
    owner.on('error', () => {});
    await once(owner, 'connect'); await accepted;
  }
  const path = writeRuntimeDescriptor({ dir: join(root, 'descriptor'), allowedRoots: [root], descriptor: {
    entryPath: join(bundle, 'runtime-host.partial.mjs'), pinCommit: UPSTREAM_RUNTIME_COMMIT, port, token: 'a'.repeat(64),
    catalog: { providers: [{ providerId: 'probe', name: 'Probe', apiFormat: 'openai-completions', enabled: true, models: ['probe-model'] }], defaultProviderId: 'probe', defaultModelId: 'probe-model' },
  } });
  descriptorFd = openSync(path, constants.O_RDONLY | constants.O_NOFOLLOW);
  const dataDir = join(root, 'runtime');
  child = spawn(process.execPath, [join(here, 'runtime-start-child.mjs'), bundle, dataDir], {
    cwd: root, env: { ...buildRuntimeEnv(), HOME: root, MINIMAX_DATA_DIR: dataDir },
    stdio: ['pipe', 'pipe', 'pipe', descriptorFd, ...peers, 'pipe'],
  });
  const exited = once(child, 'exit');
  let stderr = '', stdout = '';
  child.stderr.setEncoding('utf8').on('data', (chunk) => { stderr += chunk; });
  child.stdout.setEncoding('utf8').on('data', (chunk) => { stdout += chunk; });
  // Read readiness on its private FD, never by recognizing stdout log text.
  const ready = once(child.stdio[6], 'data');
  await once(child, 'spawn');
  closeSync(descriptorFd); descriptorFd = undefined;
  peers.forEach((socket) => socket.destroy());
  assert.equal((await Promise.race([ready, exited.then(() => { throw Error('real FD startup failed'); })]))[0].toString(), 'R');
  for (let index = 0; index < owners.length; index++) {
    const owner = owners[index];
    let body = '';
    owner.setEncoding('utf8').on('data', (chunk) => { body += chunk; });
    const closed = once(owner, 'close');
    const auth = index ? `Authorization: Bearer ${'a'.repeat(64)}\r\n` : '';
    owner.write(`GET /api/state HTTP/1.1\r\nHost: 127.0.0.1:${port}\r\n${auth}Connection: close\r\n\r\n`);
    await closed;
    assert.match(body, index ? /^HTTP\/1\.1 200/ : /^HTTP\/1\.1 403/);
    if (index) assert.ok(body.includes('"bots"'));
    assert.ok(!body.includes('a'.repeat(64)));
  }
  child.stdin.end('close\n');
  const [code] = await exited;
  assert.equal(code, 0, stderr);
  assert.match(stdout, /PASS: real pinned runtime FD startup/);
  assert.ok(!stdout.includes('native-broker-placeholder'));
  assert.equal(stderr, '');
});
