// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fstatSync, openSync } from 'node:fs';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { Agent, createServer as createHttpServer, request as httpRequest } from 'node:http';
import { createServer, connect } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { createBotFdTransport } from './bot-fd-transport.mjs';
import { buildRuntimeEnv } from './runtime-descriptor.mjs';

test('an unbound or invalid transferred FD is refused and closed', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'mbot-bot-fd-invalid-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const path = join(root, 'ordinary-file');
  await writeFile(path, 'fixture');
  const transport = createBotFdTransport();
  let fd = openSync(path, 'r');
  assert.throws(() => transport.accept(fd), (error) => error.code === 'bot_transport_unavailable');
  assert.throws(() => fstatSync(fd), (error) => error.code === 'EBADF');
  await transport.listenServer(createHttpServer());
  fd = openSync(path, 'r');
  assert.throws(() => transport.accept(fd), (error) => error.code === 'bot_transport_invalid');
  assert.throws(() => fstatSync(fd), (error) => error.code === 'EBADF');
  assert.throws(() => transport.accept(1), (error) => error.code === 'bot_transport_invalid');
  transport.close();
  transport.close();
  fd = openSync(path, 'r');
  assert.throws(() => transport.accept(fd), (error) => error.code === 'bot_transport_unavailable');
  assert.throws(() => fstatSync(fd), (error) => error.code === 'EBADF');
  await assert.rejects(transport.listenServer(createHttpServer()), (error) => error.code === 'bot_transport_unavailable');
});

test('the real Bot serves authenticated HTTP and SSE over granted FDs without opening a listener', { timeout: 20_000 }, async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'mbot-bot-fd-'));
  const clients = [], accepted = [];
  let child;
  let phase = 'setup';
  const diagnostic = setTimeout(() => t.diagnostic(`waiting for ${phase}`), 5000);
  let allAccepted;
  const connectionsReady = new Promise((resolve) => { allAccepted = resolve; });
  const unix = createServer((socket) => {
    accepted.push(socket);
    if (accepted.length === 6) allAccepted();
  });
  const blocker = createServer();
  t.after(async () => {
    clearTimeout(diagnostic);
    if (phase !== 'done') t.diagnostic(`ended during ${phase}`);
    child?.kill('SIGKILL'); clients.forEach((socket) => socket.destroy()); accepted.forEach((socket) => socket.destroy());
    if (unix.listening) await new Promise((resolve) => unix.close(resolve));
    if (blocker.listening) await new Promise((resolve) => blocker.close(resolve));
    await rm(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 20 });
  });
  blocker.listen(0, '127.0.0.1');
  await once(blocker, 'listening');
  const port = blocker.address().port;
  unix.listen(join(root, 'handoff.sock'));
  await once(unix, 'listening');
  for (let index = 0; index < 6; index++) {
    const client = connect(join(root, 'handoff.sock'));
    clients.push(client); client.on('error', () => {});
    await once(client, 'connect');
  }
  await connectionsReady;
  const helper = fileURLToPath(new URL('./bot-fd-test-child.mjs', import.meta.url));
  child = spawn(process.execPath, [helper, root, String(port), String(clients.length)], {
    stdio: ['pipe', 'pipe', 'pipe', ...accepted], env: buildRuntimeEnv(),
  });
  const lines = createInterface({ input: child.stdout });
  let stderr = '';
  child.stderr.setEncoding('utf8').on('data', (chunk) => { stderr += chunk; });
  const exited = once(child, 'exit');
  const ready = once(lines, 'line');
  await once(child, 'spawn');
  accepted.forEach((socket) => socket.destroy());
  phase = 'child ready';
  const firstLine = await Promise.race([
    ready.then(([line]) => line),
    exited.then(([code, signal]) => { throw new Error(`fixture exited before ready (${code ?? signal}): ${stderr}`); }),
  ]);
  assert.equal(firstLine, 'ready');

  function request(index, path, { method = 'GET', headers = {}, body } = {}) {
    // Grant each preconnected FD only when its request is about to start;
    // the real Bot remains alive while subsequent connections arrive.
    child.stdin.write('grant\n');
    const agent = new Agent();
    agent.createConnection = () => clients[index];
    return new Promise((resolve, reject) => {
      const req = httpRequest({ host: '127.0.0.1', port, path, method, agent, headers }, (response) => resolve({ response, agent }));
      req.once('error', (error) => reject(new Error(`${phase}: ${error.code ?? 'request_failed'}; ${stderr}`)));
      req.end(body);
    });
  }
  async function read({ response, agent }) {
    const chunks = [];
    try { for await (const chunk of response) chunks.push(chunk); }
    catch (error) { throw new Error(`${phase}: ${error.code ?? 'response_failed'}; ${stderr}`); }
    agent.destroy();
    return { status: response.statusCode, body: Buffer.concat(chunks).toString('utf8') };
  }
  const auth = { Authorization: `Bearer ${'a'.repeat(64)}` };
  phase = 'anonymous response';
  assert.equal((await read(await request(0, '/api/state'))).status, 403);
  phase = 'origin response';
  assert.equal((await read(await request(1, '/api/state', { headers: { ...auth, Origin: 'https://example.invalid' } }))).status, 403);
  phase = 'host response';
  assert.equal((await read(await request(2, '/api/state', { headers: { ...auth, Host: 'example.invalid' } }))).status, 403);
  phase = 'authorized state';
  const state = await read(await request(3, '/api/state', { headers: auth }));
  assert.equal(state.status, 200);
  assert.ok(JSON.parse(state.body).bots.length > 0);
  assert.ok(!state.body.includes('a'.repeat(64)));
  phase = 'event stream';
  const { response: events, agent: eventsAgent } = await request(4, '/api/events', { headers: auth });
  assert.equal(events.statusCode, 200);
  let received = '';
  let helloResolve, changedResolve;
  const hello = new Promise((resolve) => { helloResolve = resolve; });
  const changed = new Promise((resolve) => { changedResolve = resolve; });
  events.on('data', (chunk) => {
    received += chunk.toString();
    if (received.includes('"type":"hello"')) helloResolve();
    if (received.includes('"type":"state.changed"')) changedResolve();
  });
  phase = 'event hello';
  await hello;
  phase = 'create bot';
  const created = await read(await request(5, '/api/bots', { method: 'POST',
    headers: { ...auth, 'content-type': 'application/json' }, body: JSON.stringify({ name: 'FD Bot' }) }));
  assert.equal(created.status, 200);
  assert.equal(JSON.parse(created.body).bot.name, 'FD Bot');
  phase = 'state change event';
  await changed;
  assert.ok(!received.includes('a'.repeat(64)));
  let closeError;
  events.on('error', (error) => { closeError = error; });
  const closed = new Promise((resolve) => events.once('close', resolve));
  phase = 'shutdown';
  child.stdin.end('close\n');
  await closed;
  if (closeError) assert.equal(closeError.code, 'ECONNRESET');
  eventsAgent.destroy();
  phase = 'child exit';
  const [exitCode] = await exited;
  assert.equal(exitCode, 0, stderr);
  assert.equal(stderr, '');
  phase = 'done';
});
