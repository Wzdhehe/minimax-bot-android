// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import { mkdtemp, rm, stat } from 'node:fs/promises';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { test } from 'node:test';
import { createBotPersistence } from './bot-persistence.mjs';

const esbuildPath = process.env.ESBUILD_MODULE;
const missing = () => Object.assign(new Error('runtime_data_not_found'), { code: 'runtime_data_not_found' });

test('the real restricted Bot service can start from an in-memory module without a filesystem workspace',
  { skip: !esbuildPath && 'Set ESBUILD_MODULE to the pinned esbuild module', timeout: 20_000 }, async (t) => {
    const esbuild = await import(pathToFileURL(resolve(esbuildPath)).href);
    assert.equal(esbuild.version, '0.28.2');
    const output = await esbuild.build({ entryPoints: [fileURLToPath(new URL('./service.mjs', import.meta.url))],
      bundle: true, platform: 'node', format: 'esm', target: 'node24', write: false, logLevel: 'silent' });
    assert.equal(output.outputFiles.length, 1);
    // A data URL has no filesystem-relative parent. In restricted mode the
    // service must not try to resolve the original Mini App client directory.
    const url = `data:text/javascript;base64,${Buffer.from(output.outputFiles[0].contents).toString('base64')}`;
    const { startBotService } = await import(url);
    const directory = await mkdtemp(join(tmpdir(), 'mbot-data-url-'));
    const dataDir = join(directory, 'virtual-bot');
    let service;
    t.after(async () => { if (service) await service.dispose(); await rm(directory, { recursive: true, force: true }); });
    const values = new Map();
    const files = {
      async read(name) { if (!values.has(name)) throw missing(); return Uint8Array.from(values.get(name)); },
      async size(name) { if (!values.has(name)) throw missing(); return values.get(name).length; },
      async replace(name, bytes) { values.set(name, Buffer.from(bytes)); return 'published'; },
      async append(name, bytes) { values.set(name, Buffer.concat([values.get(name) ?? Buffer.alloc(0), Buffer.from(bytes)])); return 'published'; },
      async publish(name, bytes) { if (values.has(name)) return 'already_exists'; values.set(name, Buffer.from(bytes)); return 'published'; },
      async rename(from, to) { if (!values.has(from)) throw missing(); values.set(to, values.get(from)); values.delete(from); return 'published'; },
      async remove(name) { if (!values.delete(name)) throw missing(); return 'removed'; },
    };
    const reservation = createServer();
    await new Promise((done, reject) => { reservation.once('error', reject); reservation.listen(0, '127.0.0.1', done); });
    const port = reservation.address().port;
    await new Promise((done, reject) => reservation.close((error) => error ? reject(error) : done()));
    const token = 'a'.repeat(64);
    service = await startBotService({ port, dataDir, fileMode: 'unavailable', ...createBotPersistence({ dataDir, files }),
      authToken: token, signal: new AbortController().signal, logger: { info() {}, warn() {}, error() {} } });
    assert.equal(service.workspace, null);
    const state = await fetch(`${service.descriptor.baseUrl}/api/state`, { headers: service.descriptor.headers });
    assert.equal(state.status, 200);
    const body = await state.text();
    assert.ok(JSON.parse(body).bots.length > 0);
    assert.ok(!body.includes(token));
    const upload = await fetch(`${service.descriptor.baseUrl}/api/uploads?name=x.txt`, {
      method: 'POST', headers: service.descriptor.headers, body: 'unavailable',
    });
    assert.equal(upload.status, 503);
    assert.equal((await upload.json()).error, 'files_unavailable');
    await assert.rejects(stat(dataDir), (error) => error.code === 'ENOENT');
  });
