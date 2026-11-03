import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { startBotService } from './service.mjs';

test('authenticates the actual Bot listener and rotates credentials on restart', async (t) => {
  const dataDir = await mkdtemp(join(tmpdir(), 'bot-auth-'));
  const probe = createServer();
  await new Promise((resolve, reject) => { probe.once('error', reject); probe.listen(0, '127.0.0.1', resolve); });
  const port = probe.address().port;
  await new Promise((resolve, reject) => probe.close((error) => error ? reject(error) : resolve()));
  const options = { port, dataDir, filesDir: dataDir, signal: new AbortController().signal, logger: { info() {}, warn() {}, error() {} } };
  let service = await startBotService(options);
  t.after(async () => { await service.dispose(); await rm(dataDir, { recursive: true, force: true }); });
  const { baseUrl, headers } = service.descriptor;
  for (const path of ['/api/state', '/api/events', '/api/usage-quota', '/bot', '/mcp']) {
    const response = await fetch(`${baseUrl}${path}`);
    assert.equal(response.status, 403);
    await response.text();
  }
  const mutation = await fetch(`${baseUrl}/api/bots`, { method: 'POST', body: JSON.stringify({ name: 'Unauthorized' }) });
  assert.equal(mutation.status, 403);
  await mutation.text();
  const browser = await fetch(`${baseUrl}/api/state`, { headers: { ...headers, Origin: 'https://example.com' } });
  assert.equal(browser.status, 403);
  await browser.text();
  const state = await fetch(`${baseUrl}/api/state`, { headers });
  assert.equal(state.status, 200);
  assert.ok((await state.json()).bots.length > 0);
  await service.dispose();
  service = await startBotService(options);
  assert.notEqual(service.descriptor.headers.Authorization, headers.Authorization);
  const stale = await fetch(`${baseUrl}/api/state`, { headers });
  assert.equal(stale.status, 403);
  await stale.text();
  const fresh = await fetch(`${baseUrl}/api/state`, { headers: service.descriptor.headers });
  assert.equal(fresh.status, 200);
  await fresh.text();
  const quota = await fetch(`${baseUrl}/api/usage-quota`, { headers: service.descriptor.headers });
  assert.deepEqual(await quota.json(), { signedIn: false });
});

test('authenticated provider probe reaches the broker without keys in its HTTP contract', async (t) => {
  const dataDir = await mkdtemp(join(tmpdir(), 'bot-provider-http-'));
  const probe = createServer();
  await new Promise((resolve, reject) => { probe.once('error', reject); probe.listen(0, '127.0.0.1', resolve); });
  const port = probe.address().port;
  await new Promise((resolve, reject) => probe.close((error) => error ? reject(error) : resolve()));
  let requests = 0;
  const modelBroker = {
      async stream({ providerId, modelId, onEvent, signal }) {
        requests++;
        assert.equal(providerId, 'p1');
        assert.equal(modelId, 'model');
        onEvent({ choices: [{ delta: { content: 'OK' } }] });
        assert.equal(signal.aborted, true);
      },
  };
  const getUsageQuota = async ({ forceRefresh }) => {
    assert.equal(forceRefresh, true);
    return { signedIn: true, planName: 'M Plan', accessToken: 'must-stay-private', quota: { fiveHour: { usedPercent: 17 }, weekly: { usedPercent: 32 } } };
  };
  const service = await startBotService({ port, dataDir, filesDir: dataDir, modelBroker, getUsageQuota, signal: new AbortController().signal, logger: { info() {}, warn() {}, error() {} } });
  t.after(async () => { await service.dispose(); await rm(dataDir, { recursive: true, force: true }); });
  const post = (body, authorized = true) => fetch(`${service.descriptor.baseUrl}/api/providers/test`, {
    method: 'POST', headers: { ...(authorized ? service.descriptor.headers : {}), 'content-type': 'application/json' }, body: JSON.stringify(body),
  });
  const good = await post({ providerId: 'p1', modelId: 'model' });
  assert.deepEqual(await good.json(), { ok: true });
  const suppliedKey = await post({ providerId: 'p1', modelId: 'model', apiKey: 'must-not-enter-broker' });
  assert.equal(suppliedKey.status, 400);
  await suppliedKey.text();
  const anonymous = await post({ providerId: 'p1', modelId: 'model' }, false);
  assert.equal(anonymous.status, 403);
  await anonymous.text();
  assert.equal(requests, 1);
  const quota = await fetch(`${service.descriptor.baseUrl}/api/usage-quota?refresh=true`, { headers: service.descriptor.headers });
  assert.deepEqual(await quota.json(), { signedIn: true, planName: 'M Plan', quota: { fiveHour: { usedPercent: 17, unlimited: false }, weekly: { usedPercent: 32, unlimited: false } } });
});
