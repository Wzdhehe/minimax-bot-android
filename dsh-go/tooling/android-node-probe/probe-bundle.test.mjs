// SPDX-License-Identifier: AGPL-3.0-only
import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createServer, connect } from 'node:net';
import { once } from 'node:events';
import { mkdtemp, readFile, readdir, writeFile, rm } from 'node:fs/promises';
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
  const hash = (value) => createHash('sha256').update(value).digest('hex');
  assert.equal(hash(script.replace(/^const sqliteWrapper = .*;$/m, 'const sqliteWrapper = "__MBOT_SQLITE_WRAPPER__";')),
    hash(fresh), 'prepared probe asset is stale');
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
    // Embedded modules can exceed Linux's per-argument exec limit. Native
    // node::Start has no kernel exec here; use stdin only in this host fixture.
    child = spawn(process.execPath, ['--no-warnings', '-e', "eval(require('node:fs').readFileSync(0, 'utf8'))", '3', '4', '5', '6', marker], {
      stdio: ['pipe', 'pipe', 'pipe', ...owners, 'pipe'], env: buildRuntimeEnv(process.env),
    });
    child.stdin.end(script);
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

test('the packaged scoped-model block routes two turns over actual inherited FDs', { timeout: 12_000 }, async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'mbot-node-scoped-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  execFileSync('python3', [join(root, 'prepare-probe.py'), '--bundle-only', '--bundle-output', directory]);
  const script = await readFile(join(directory, 'node-fd-probe.cjs'), 'utf8');
  const moduleLine = /^const nativeBindingModuleUrl = (.+);$/m.exec(script);
  assert.ok(moduleLine);
  const moduleUrl = JSON.parse(moduleLine[1]);
  const start = script.indexOf("    probePhase = 'scoped_model_route';");
  const end = script.indexOf('    const cancelledId =', start);
  assert.ok(start >= 0 && end > start);
  const scopedBlock = script.slice(start, end);
  const childScript = `
    const nativeBindingModuleUrl = ${JSON.stringify(moduleUrl)};
    const providerId = 'host-fixture';
    let probePhase, count = 0;
    setTimeout(() => process.stderr.write('scoped fixture timed out after ' + count + ' open(s)'), 7000).unref();
    const report = {};
    const binding = {
      async open(id, body) {
        const payload = JSON.parse(body);
        if (!id || payload.providerId !== providerId || payload.modelId !== 'normal' || count >= 2) throw Error('bad_request');
        return 3 + count++;
      },
      cancel() {},
    };
    (async () => { ${scopedBlock}
      process.stdout.write(JSON.stringify({ scoped: report.scopedModelRoute, count }));
    })().catch(() => { process.exitCode = 1; });
  `;
  const servers = [], owners = [], peers = [];
  let child;
  const timeout = setTimeout(() => { child?.kill('SIGKILL'); peers.forEach((socket) => socket.destroy()); }, 10_000);
  try {
    for (let index = 0; index < 2; index++) {
      const path = join(directory, `scoped-${index}`);
      const server = createServer((socket) => {
        peers.push(socket);
        socket.on('error', () => {});
      });
      servers.push(server); server.listen(path); await once(server, 'listening');
      const owner = connect(path); owners.push(owner); owner.on('error', () => {});
      await once(owner, 'connect'); owner.pause();
    }
    child = spawn(process.execPath, ['--no-warnings', '-e', "eval(require('node:fs').readFileSync(0, 'utf8'))"], {
      stdio: ['pipe', 'pipe', 'pipe', ...owners], env: buildRuntimeEnv(process.env),
    });
    child.stdin.end(childScript);
    const exited = once(child, 'exit');
    let stdout = '', stderr = '';
    child.stdout.setEncoding('utf8').on('data', (chunk) => { stdout += chunk; });
    child.stderr.setEncoding('utf8').on('data', (chunk) => { stderr += chunk; });
    await once(child, 'spawn'); owners.forEach((socket) => socket.destroy());
    // These are preauthenticated, already-issued model channels: the reader
    // consumes their responses and intentionally sends no HTTP request.
    for (const socket of peers) socket.end(header +
      'data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"lookup","arguments":"{}"}}]}}]}\n\n' +
      'data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}\n\ndata: [DONE]\n\n');
    const [code] = await exited;
    assert.equal(code, 0, stderr);
    assert.equal(stderr, '');
    const report = JSON.parse(stdout);
    assert.equal(report.count, 2);
    assert.equal(report.scoped.unboundStatus, 400);
    for (const name of ['first', 'second']) {
      assert.deepEqual(report.scoped[name], { status: 200, toolCalls: true, done: true });
    }
  } finally {
    clearTimeout(timeout); child?.kill(); owners.forEach((socket) => socket.destroy()); peers.forEach((socket) => socket.destroy());
    await Promise.all(servers.map((server) => new Promise((resolve) => server.close(resolve))));
  }
});

test('the packaged Bot transport block serves two granted duplex FDs without listening', { timeout: 12_000 }, async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'mbot-node-bot-fd-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  execFileSync('python3', [join(root, 'prepare-probe.py'), '--bundle-only', '--bundle-output', directory]);
  const script = await readFile(join(directory, 'node-fd-probe.cjs'), 'utf8');
  const moduleLine = /^const botTransportModuleUrl = (.+);$/m.exec(script);
  const ingressLine = /^const botIngressModuleUrl = (.+);$/m.exec(script);
  assert.ok(moduleLine);
  assert.ok(ingressLine);
  const moduleUrl = JSON.parse(moduleLine[1]);
  const ingressUrl = JSON.parse(ingressLine[1]);
  const start = script.indexOf("    probePhase = 'bot_http_fd';");
  const end = script.indexOf("    probePhase = 'model_dynamic';", start);
  assert.ok(start >= 0 && end > start);
  const block = script.slice(start, end);
  const childScript = `
    const botTransportModuleUrl = ${JSON.stringify(moduleUrl)};
    const botIngressModuleUrl = ${JSON.stringify(ingressUrl)};
    const fs = require('node:fs');
    const result = '5';
    let next = 3;
    let cancelled = false, cancelPending;
    const control = new (require('node:net').Socket)({ fd: 5, readable: true, writable: true });
    process._linkedBinding = (name) => {
      if (name !== 'mbot_bot_ingress') throw Error('unexpected binding');
      return { cancel() { cancelled = true; cancelPending?.(); }, next: () => new Promise((resolve, reject) => {
        cancelPending = () => reject(Error('cancelled'));
        control.once('data', (chunk) => chunk.toString() === 'A' ? resolve(next++) : reject(Error('bad grant')));
      }) };
    };
    let probePhase;
    const report = {};
    (async () => { ${block}
      control.destroy();
      process.stdout.write(JSON.stringify({ ...report.botFdTransport, cancelled }));
    })().catch(() => { process.exitCode = 1; });
  `;
  const peers = [], owners = [], responses = ['', ''];
  let child;
  const server = createServer((socket) => { peers.push(socket); socket.on('error', () => {}); });
  const timeout = setTimeout(() => { child?.kill('SIGKILL'); owners.forEach((socket) => socket.destroy()); peers.forEach((socket) => socket.destroy()); }, 10_000);
  try {
    server.listen(join(directory, 'bot.sock'));
    await once(server, 'listening');
    for (let index = 0; index < 2; index++) {
      const accepted = once(server, 'connection');
      const owner = connect(join(directory, 'bot.sock'));
      owners.push(owner); owner.on('error', () => {});
      await once(owner, 'connect'); await accepted;
      owner.setEncoding('utf8').on('data', (chunk) => { responses[index] += chunk; });
    }
    child = spawn(process.execPath, ['--no-warnings', '-e', "eval(require('node:fs').readFileSync(0, 'utf8'))"], {
      stdio: ['pipe', 'pipe', 'pipe', ...peers, 'pipe'], env: buildRuntimeEnv(process.env),
    });
    const exited = once(child, 'exit');
    const ready = once(child.stdio[5], 'data');
    child.stdin.end(childScript);
    let stdout = '', stderr = '';
    child.stdout.setEncoding('utf8').on('data', (chunk) => { stdout += chunk; });
    child.stderr.setEncoding('utf8').on('data', (chunk) => { stderr += chunk; });
    await once(child, 'spawn');
    peers.forEach((socket) => socket.destroy());
    assert.equal((await ready)[0].toString(), 'R');
    for (const owner of owners) {
      const closed = once(owner, 'close');
      child.stdio[5].write('A');
      owner.write('GET /fd-probe HTTP/1.1\r\nHost: 127.0.0.1:43117\r\nConnection: close\r\n\r\n');
      await closed;
    }
    const [code] = await exited;
    assert.equal(code, 0, stderr);
    assert.equal(stderr, '');
    assert.deepEqual(JSON.parse(stdout), { request: true, noListener: true, connections: 2, cancelledWait: true, cancelled: true });
    for (const response of responses) {
      assert.match(response, /^HTTP\/1\.1 200/);
      assert.ok(response.includes('fd-ok'));
    }
  } finally {
    clearTimeout(timeout); child?.kill(); owners.forEach((socket) => socket.destroy()); peers.forEach((socket) => socket.destroy());
    await new Promise((resolve) => server.close(resolve));
  }
});

test('the exact packaged Bot Store block survives two host processes using a fake file capability', { timeout: 15_000 }, async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'mbot-node-bot-store-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  execFileSync('python3', [join(root, 'prepare-probe.py'), '--bundle-only', '--bundle-output', directory]);
  assert.deepEqual(await readFile(join(directory, 'third_party/minimax-bot/LICENSE')),
    await readFile(join(root, '../../runtime/vendor/minimax-bot/LICENSE')));
  const script = await readFile(join(directory, 'node-fd-probe.cjs'), 'utf8');
  const storeLine = /^const botStoreModuleUrl = (.+);$/m.exec(script);
  const memoryLine = /^const botMemoryModuleUrl = (.+);$/m.exec(script);
  const adapterLine = /^const botPersistenceModuleUrl = (.+);$/m.exec(script);
  assert.ok(storeLine && memoryLine && adapterLine);
  const start = script.indexOf("    probePhase = 'bot_store';");
  const end = script.indexOf('\n  }\n  // Owner inspects', start);
  assert.ok(start >= 0 && end > start);
  const block = script.slice(start, end);
  const childScript = `
    const botStoreModuleUrl = ${storeLine[1]};
    const botMemoryModuleUrl = ${memoryLine[1]};
    const botPersistenceModuleUrl = ${adapterLine[1]};
    const fsp = require('node:fs/promises');
    const path = require('node:path');
    const directory = ${JSON.stringify(directory)};
    const target = (name) => {
      if (!/^[A-Za-z0-9._-]{1,128}$/.test(name)) throw Error('invalid name');
      return path.join(directory, name);
    };
    const files = {
      async read(name) {
        try { return await fsp.readFile(target(name)); }
        catch (error) { if (error.code === 'ENOENT') error.code = 'runtime_data_not_found'; throw error; }
      },
      async replace(name, bytes) {
        const destination = target(name), temporary = destination + '.tmp';
        await fsp.writeFile(temporary, bytes); await fsp.rename(temporary, destination); return 'published';
      },
      async append(name, bytes) { await fsp.appendFile(target(name), bytes); return 'published'; },
      async publish(name, bytes) {
        try { await fsp.writeFile(target(name), bytes, { flag: 'wx' }); return 'published'; }
        catch (error) { if (error.code === 'EEXIST') return 'already_exists'; throw error; }
      },
      async size(name) {
        try { return (await fsp.stat(target(name))).size; }
        catch (error) { if (error.code === 'ENOENT') error.code = 'runtime_data_not_found'; throw error; }
      },
      async rename(source, destination) {
        try { await fsp.rename(target(source), target(destination)); return 'published'; }
        catch (error) { if (error.code === 'ENOENT') error.code = 'runtime_data_not_found'; throw error; }
      },
      async remove(name) {
        try { await fsp.unlink(target(name)); return 'removed'; }
        catch (error) { if (error.code === 'ENOENT') error.code = 'runtime_data_not_found'; throw error; }
      },
    };
    let probePhase;
    const report = {};
    (async () => { ${block}
      process.stdout.write(JSON.stringify(report.botStore));
    })().catch(() => { process.exitCode = 1; });
  `;
  async function run(withoutIntl = false) {
    const child = spawn(process.execPath, ['--no-warnings', '-e', "eval(require('node:fs').readFileSync(0, 'utf8'))"], {
      stdio: ['pipe', 'pipe', 'pipe'], env: buildRuntimeEnv(process.env),
    });
    const exited = once(child, 'exit');
    let stdout = '', stderr = '';
    child.stdout.setEncoding('utf8').on('data', (chunk) => { stdout += chunk; });
    child.stderr.setEncoding('utf8').on('data', (chunk) => { stderr += chunk; });
    child.stdin.end((withoutIntl ? 'const Intl = undefined;\n' : '') + childScript);
    const [code] = await exited;
    assert.equal(code, 0, stderr);
    assert.equal(stderr, '');
    return JSON.parse(stdout);
  }
  assert.deepEqual(await run(), { available: true, before: 0, after: 1, schedules: 1, usage: 1, recorded: true,
    memoryBefore: 0, memoryAfter: 1, memoryJournal: 1, memoryBackup: true });
  assert.equal((await readdir(directory)).filter((name) => /^bot-memory-[a-f0-9]{64}\.bin$/.test(name)).length, 2);
  assert.deepEqual(await run(), { available: true, before: 1, after: 2, schedules: 1, usage: 1, recorded: false,
    memoryBefore: 1, memoryAfter: 1, memoryJournal: 2, memoryBackup: true });
  assert.equal((await readdir(directory)).filter((name) => /^bot-memory-[a-f0-9]{64}\.bin$/.test(name)).length, 3);
  assert.deepEqual(await run(true), { available: false });
});
