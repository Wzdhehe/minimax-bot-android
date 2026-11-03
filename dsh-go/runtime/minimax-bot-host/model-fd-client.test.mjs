// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import test from 'node:test';
import { createServer, connect } from 'node:net';
import { once } from 'node:events';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { streamNativeModelFd } from './model-fd-client.mjs';
import { buildRuntimeEnv } from './runtime-descriptor.mjs';
import { mkdtempSync, openSync, fstatSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

const ok = 'HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nX-Mbot-Broker-Protocol: 1\r\nConnection: close\r\n\r\n';
const frames = 'data: {"delta":"first"}\n\ndata: {"delta":"second"}\n\ndata: [DONE]\n\n';
const helper = fileURLToPath(new URL('./model-fd-reader.helper.mjs', import.meta.url));

/** A trusted parent authenticates once, then only inherits the connected FD.
 * This is real Node TCP/FD/SSE behavior, not Android/JNI/SELinux evidence. */
async function run(response, mode = '', { split = false, unix = false } = {}) {
  const session = 'parent-only-session-capability';
  let authenticated = false;
  let upstream;
  let sawClose;
  const peerClosed = new Promise((resolve) => { sawClose = resolve; });
  const server = createServer((socket) => {
    upstream = socket;
    socket.on('error', () => {});
    socket.once('close', sawClose);
    let request = '';
    socket.on('data', (bytes) => {
      request += bytes.toString();
      if (!request.endsWith('\r\n\r\n')) return;
      authenticated = request.includes(`Authorization: Bearer ${session}\r\n`);
      if (response !== null) {
        if (split) {
          const boundary = response.indexOf('\r\n\r\n') + 2;
          socket.write(response.slice(0, boundary));
          setTimeout(() => { if (!socket.destroyed) socket.end(response.slice(boundary)); }, 15);
        } else socket.end(response);
      }
    });
  });
  const directory = unix ? mkdtempSync(join(tmpdir(), 'mbot-fd-unix-')) : null;
  const socketPath = directory && join(directory, 'model.sock');
  if (unix) server.listen(socketPath);
  else server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const owner = unix ? connect(socketPath) : connect(server.address().port, '127.0.0.1');
  owner.on('error', () => {});
  await once(owner, 'connect');
  owner.pause();
  owner.write(`POST /v1/model/stream HTTP/1.1\r\nAuthorization: Bearer ${session}\r\n\r\n`);
  // Do not let the parent consume response bytes before the child inherits FD3.
  const child = spawn(process.execPath, [helper, mode], {
    stdio: ['ignore', 'pipe', 'pipe', owner], env: buildRuntimeEnv(process.env),
  });
  const childDone = once(child, 'exit');
  let stdout = '', stderr = '';
  child.stdout.setEncoding('utf8').on('data', (chunk) => { stdout += chunk; });
  child.stderr.setEncoding('utf8').on('data', (chunk) => { stderr += chunk; });
  const watchdog = setTimeout(() => child.kill('SIGKILL'), 5000);
  try {
    await once(child, 'spawn');
    owner.destroy();
    const [code] = await childDone;
    assert.equal(code, 0, stderr);
    await peerClosed;
    assert.equal(authenticated, true);
    assert.equal(stdout.includes(session), false);
    assert.equal(stderr.includes(session), false);
    return { ...JSON.parse(stdout), stderr };
  } finally {
    clearTimeout(watchdog);
    child.kill(); owner.destroy(); upstream?.destroy();
    await new Promise((resolve) => server.close(resolve));
    if (directory) rmSync(directory, { recursive: true, force: true });
  }
}

test('preconnected FD consumes split HTTP/SSE and awaits each callback', { timeout: 8000 }, async () => {
  const result = await run(ok + frames, 'slow-events', { split: true });
  assert.equal(result.code, 'done');
  assert.deepEqual(result.events, [{ delta: 'first' }, { delta: 'second' }]);
});

test('the same reader consumes an inherited Unix socket FD without a path or endpoint', { timeout: 8000 }, async () => {
  const result = await run(ok + frames, '', { unix: true });
  assert.equal(result.code, 'done');
  assert.deepEqual(result.events, [{ delta: 'first' }, { delta: 'second' }]);
});

test('FD framing rejects truncated headers, oversized headers, wrong content type and missing DONE', { timeout: 15000 }, async () => {
  for (const [response, code] of [
    ['HTTP/1.1 200 OK\r\n', 'broker_stream_failed'],
    [ok.replace('Connection: close', 'X-Long: ' + 'x'.repeat(17 * 1024)), 'broker_invalid_response'],
    [ok.replace('text/event-stream', 'application/json') + frames, 'broker_invalid_response'],
    [ok.replace('X-Mbot-Broker-Protocol: 1\r\n', '') + frames, 'broker_invalid_response'],
    [ok + 'data: {"delta":"partial"}\n\n', 'broker_stream_failed'],
    [ok + 'data: not-json\n\n', 'broker_invalid_response'],
    [ok.replace('Connection: close', 'Transfer-Encoding: chunked') + frames, 'broker_invalid_response'],
  ]) assert.equal((await run(response)).code, code);
});

test('FD cancellation and deadlines close quiet upstream; same-packet events stop on abort', { timeout: 12000 }, async () => {
  assert.equal((await run(null, 'abort-header')).code, 'broker_cancelled');
  assert.equal((await run(null, 'deadline')).code, 'broker_stream_failed');
  const result = await run(ok + frames, 'cancel-event');
  assert.equal(result.code, 'broker_cancelled');
  assert.deepEqual(result.events, [{ delta: 'first' }]);
  const blocked = await run(ok + frames, 'blocked-callback');
  assert.equal(blocked.code, 'broker_stream_failed');
  assert.deepEqual(blocked.events, [{ delta: 'first' }]);
});

test('FD errors never surface HTTP bodies, arbitrary error codes or callback diagnostics', { timeout: 12000 }, async () => {
  const injected = 'unsafe-value-must-not-be-reported';
  const rejected = await run('HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain\r\n\r\n' + injected);
  assert.equal(rejected.code, 'broker_rejected');
  assert.equal(JSON.stringify(rejected).includes(injected), false);
  const untrustedCode = await run(ok + `data: {"error":"${injected}"}\n\ndata: [DONE]\n\n`);
  assert.equal(untrustedCode.code, 'broker_unavailable');
  assert.equal(JSON.stringify(untrustedCode).includes(injected), false);
  const callback = await run(ok + frames, 'callback-failure');
  assert.equal(callback.code, 'broker_stream_failed');
  assert.equal(JSON.stringify(callback).includes('must-not-appear'), false);
});

test('FD adapter rejects stdin/stdout/stderr and invalid deadlines before taking ownership', async () => {
  for (const fd of [-1, 0, 1, 2, 3.1, '3']) {
    await assert.rejects(streamNativeModelFd({ fd }), { code: 'broker_request_invalid' });
  }
  await assert.rejects(streamNativeModelFd({ fd: 3, timeoutMs: 0 }), { code: 'broker_request_invalid' });
});

test('a descriptor that cannot be adopted as a socket is closed without exposing platform text', async () => {
  const directory = mkdtempSync(join(tmpdir(), 'mbot-fd-invalid-'));
  try {
    const fd = openSync(join(directory, 'not-a-socket'), 'w+');
    await assert.rejects(streamNativeModelFd({ fd }), { code: 'broker_unavailable' });
    assert.throws(() => fstatSync(fd), { code: 'EBADF' });
  } finally { rmSync(directory, { recursive: true, force: true }); }
});
