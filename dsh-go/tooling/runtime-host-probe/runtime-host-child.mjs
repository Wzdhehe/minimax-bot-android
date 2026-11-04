// SPDX-License-Identifier: AGPL-3.0-only
// Keyless real-factory probe: no replacement gateway/session/model factory.
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { mkdir } from 'node:fs/promises';
import { createServer } from 'node:net';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const [bundle, root] = process.argv.slice(2);
const { createBuiltinBotRuntime, startBotService } = await import(pathToFileURL(join(bundle, 'runtime-host.partial.mjs')).href);
const token = randomBytes(32).toString('hex');
let savedBotId;
for (let pass = 0; pass < 2; pass++) {
  const runtime = await createBuiltinBotRuntime({ dataDir: root, appVersion: '0.1.0-host-probe' });
  let service;
  try {
    // A real missing-row lookup via the registered gateway and SessionSystem.
    // NOT_PERMITTED/AGENT_UNAVAILABLE would reveal a broken registration seam.
    await assert.rejects(runtime.agent.reply({ sessionId: 'missing-session', requestId: 'missing-ask', decision: 'deny' }),
      (error) => error.code === 'SESSION_NOT_FOUND');
    await mkdir(join(root, 'files'), { recursive: true });
    const listener = createServer();
    await new Promise((resolve, reject) => { listener.once('error', reject); listener.listen(0, '127.0.0.1', resolve); });
    const port = listener.address().port;
    await new Promise((resolve, reject) => listener.close((error) => error ? reject(error) : resolve()));
    service = await startBotService({ port, dataDir: join(root, 'bot'), filesDir: join(root, 'files'), agent: runtime.agent,
      authToken: token, signal: new AbortController().signal, logger: { info() {}, warn() {}, error() {} } });
    const stateUrl = `${service.descriptor.baseUrl}/api/state`;
    const anonymous = await fetch(stateUrl);
    assert.equal(anonymous.status, 403); await anonymous.text();
    const stateResponse = await fetch(stateUrl, { headers: service.descriptor.headers });
    assert.equal(stateResponse.status, 200);
    const state = await stateResponse.json();
    assert.ok(state.bots.length > 0);
    if (!pass) {
      const created = await fetch(`${service.descriptor.baseUrl}/api/bots`, {
        method: 'POST', headers: { ...service.descriptor.headers, 'content-type': 'application/json' },
        body: JSON.stringify({ name: 'Real runtime host probe' }),
      });
      assert.equal(created.status, 200);
      savedBotId = (await created.json()).bot.id;
      assert.match(savedBotId, /^bot_[a-zA-Z0-9]+$/);
    } else {
      assert.ok(state.bots.some((bot) => bot.id === savedBotId && bot.name === 'Real runtime host probe'));
    }
  } finally {
    await service?.dispose();
    const closing = runtime.close();
    assert.equal(runtime.close(), closing);
    await closing;
    await assert.rejects(runtime.agent.models(), (error) => error.code === 'AGENT_UNAVAILABLE');
  }
}
console.log('PASS: real fork factory cold-start/reopen, registered gateway with SQLite lookup, authenticated official Bot create/persist/read, shared close and stale-port rejection; no model turn or Android execution');
