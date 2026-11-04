// SPDX-License-Identifier: AGPL-3.0-only
// Keyless real-factory probe: no replacement gateway/session/model factory.
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { mkdir, readFile } from 'node:fs/promises';
import { createServer } from 'node:net';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import YAML from 'yaml';

const [bundle, root] = process.argv.slice(2);
const { createBuiltinBotRuntime, startBotService } = await import(pathToFileURL(join(bundle, 'runtime-host.partial.mjs')).href);
const token = randomBytes(32).toString('hex');
// A non-secret catalog: the guest may learn the provider, its api format and
// its models. The real endpoint and the real credential stay on the native
// side and are never part of this fixture.
const CATALOG = {
  providers: [{ providerId: 'probe', name: 'Probe', apiFormat: 'openai-completions', enabled: true, models: ['probe-model'] }],
  defaultProviderId: 'probe',
  defaultModelId: 'probe-model',
};
let savedBotId;
for (let pass = 0; pass < 2; pass++) {
  const runtime = await createBuiltinBotRuntime({ dataDir: root, appVersion: '0.1.0-host-probe', catalog: CATALOG });
  let service;
  try {
    // The projected provider reached the fork model system through the
    // upstream provider API, and the guest holds the placeholder credential
    // against the virtual origin only.
    assert.deepEqual(runtime.providerBinding.map((entry) => entry.forkProviderId), ['custom_provider:probe']);
    assert.deepEqual(runtime.providerBinding.map((entry) => entry.nativeProviderId), ['probe']);
    const configPath = join(root, 'config.yaml');
    const raw = await readFile(configPath, 'utf8');
    const config = YAML.parse(raw);
    assert.equal(config.custom_provider?.probe?.options?.apiKey, 'native-broker-placeholder');
    assert.equal(config.custom_provider?.probe?.options?.baseURL, 'https://models.mbot.invalid/v1');
    assert.equal(config.custom_provider?.probe?.api, 'openai-completions');
    assert.ok(config.custom_provider?.probe?.models?.['probe-model']);
    assert.equal(config.defaultModel, 'custom_provider:probe/probe-model');
    // No real provider endpoint or credential may appear in the guest config.
    assert.ok(!/sk-[A-Za-z0-9]/.test(raw));
    assert.ok(!raw.includes('api.minimax.io'));
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
console.log('PASS: real fork factory cold-start/reopen, projected non-secret provider catalog with placeholder credential and virtual origin, registered gateway with SQLite lookup, authenticated official Bot create/persist/read, shared close and stale-port rejection; no model turn or Android execution');
