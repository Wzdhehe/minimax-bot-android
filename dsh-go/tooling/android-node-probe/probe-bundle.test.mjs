// SPDX-License-Identifier: AGPL-3.0-only
import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { createServer, connect } from 'node:net';
import { once } from 'node:events';
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildRuntimeEnv } from '../../runtime/minimax-bot-host/runtime-descriptor.mjs';

const root = fileURLToPath(new URL('.', import.meta.url));
const header = 'HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nX-Mbot-Broker-Protocol: 1\r\nConnection: close\r\n\r\n';

test('the exact APK script consumes inherited Unix FDs and reports privately without filesystem modules', { timeout: 12_000 }, async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'mbot-node-bundle-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  execFileSync('python3', [join(root, 'prepare-probe.py'), '--bundle-only', '--bundle-output', directory]);
  const fresh = await readFile(join(directory, 'node-fd-probe.cjs'), 'utf8');
  let script;
  try { script = await readFile(join(root, 'build/verified-assets/node-fd-probe.cjs'), 'utf8'); }
  catch { script = fresh; }
  // Do not overwrite the prepared APK asset (which may include the pinned
  // SQLite wrapper). Model transport must still be today's exact source.
  assert.equal(script.replace(/^const sqliteWrapper = .*;$/m, 'const sqliteWrapper = "__MBOT_SQLITE_WRAPPER__";'), fresh);
  const marker = join(directory, 'marker');
  await writeFile(marker, 'non-sensitive marker');
  const servers = [], owners = [], peers = [];
  const responses = [];
  let ready;
  const requestsReceived = new Promise((resolve) => { ready = resolve; });
  let sawCancellation = false;
  let child;
  try {
    for (let index = 0; index < 3; index++) {
      const path = join(directory, `socket-${index}`);
      const server = createServer((socket) => {
        peers.push(socket);
        socket.on('error', () => {});
        if (index === 1) socket.once('close', () => { sawCancellation = true; });
        socket.once('data', () => {
          responses.push(() => {
            if (index === 1) socket.write(header);
            else socket.end(header + (index === 0 ? 'data: {"delta":"hello"}\n\n' : 'data: {"error":"provider_sensitive_response"}\n\n') + 'data: [DONE]\n\n');
          });
          if (responses.length === 3) ready();
        });
      });
      servers.push(server); server.listen(path); await once(server, 'listening');
      const owner = connect(path); owners.push(owner); owner.on('error', () => {});
      await once(owner, 'connect'); owner.pause();
      await new Promise((resolve, reject) => owner.write('owner request', (error) => error ? reject(error) : resolve()));
    }
    await requestsReceived;
    child = spawn(process.execPath, ['--no-warnings', '-e', script, '3', '4', '5', '6', marker], {
      stdio: ['ignore', 'pipe', 'pipe', ...owners, 'pipe'], env: buildRuntimeEnv(process.env),
    });
    const exited = once(child, 'exit');
    let result = '', stdout = '', stderr = '';
    child.stdio[6].setEncoding('utf8').on('data', (chunk) => { result += chunk; });
    child.stdout.setEncoding('utf8').on('data', (chunk) => { stdout += chunk; });
    child.stderr.setEncoding('utf8').on('data', (chunk) => { stderr += chunk; });
    await once(child, 'spawn'); owners.forEach((socket) => socket.destroy());
    // A parent net.Socket can prebuffer bytes outside the FD before spawn.
    // Native PFDs do not do this; release fixture responses after FD transfer.
    responses.forEach((respond) => respond());
    const [code] = await exited;
    assert.equal(code, 0, 'fixed Node probe exited with an error');
    assert.equal(stdout, ''); assert.equal(stderr, '');
    const report = JSON.parse(result);
    assert.equal(report.normal.code, 'done');
    assert.deepEqual(report.normal.events, [{ delta: 'hello' }]);
    assert.equal(report.cancelled.code, 'broker_cancelled');
    assert.equal(report.echoed.code, 'provider_sensitive_response');
    assert.equal(sawCancellation, true);
    assert.equal(report.environment.GH_TOKEN, undefined);
    assert.equal(report.environment.GITHUB_TOKEN, undefined);
    // A host child has the same UID and can read this file. Android-specific
    // denial/UID assertions belong only to IsolatedNodeModelTest on a device.
    assert.equal(report.uid, process.getuid());
    assert.equal(report.privateDenied, false);
  } finally {
    child?.kill(); owners.forEach((socket) => socket.destroy()); peers.forEach((socket) => socket.destroy());
    await Promise.all(servers.map((server) => new Promise((resolve) => server.close(resolve))));
    await rm(directory, { recursive: true, force: true });
  }
});
