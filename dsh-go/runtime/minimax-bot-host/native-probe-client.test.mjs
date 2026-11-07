// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, openSync, fstatSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { createNativeProbeClient } from './native-probe-client.mjs';
import { createProviderBroker } from './provider-broker.mjs';

test('product probes send only identifiers and text through the native model grant', async () => {
  let request, requestId;
  const client = createNativeProbeClient({
    open(id, payload) { requestId = id; request = JSON.parse(payload); throw Error('private-platform-diagnostic'); },
    cancel() {},
  });
  const result = await createProviderBroker({ modelBroker: client }).testProvider('native-provider', 'm1');
  assert.deepEqual(result, { ok: false, code: 'broker_unavailable' });
  assert.match(requestId, /^[0-9a-f-]{36}$/);
  assert.deepEqual(request, { providerId: 'native-provider', modelId: 'm1', messages: [{ role: 'user', content: 'Reply OK.' }] });
  assert.equal(JSON.stringify(result).includes('private-platform'), false);
});

test('cancelling a probe revokes its ID and closes a late native descriptor', async () => {
  const root = mkdtempSync(join(tmpdir(), 'mbot-probe-late-'));
  try {
    const fd = openSync(join(root, 'owned'), 'w+');
    let opened, release, requestId, cancelledId;
    const started = new Promise((done) => { opened = done; });
    const client = createNativeProbeClient({
      open(id) { requestId = id; opened(); return new Promise((done) => { release = done; }); },
      cancel(id) { cancelledId = id; },
    });
    const controller = new AbortController();
    const pending = client.stream({ providerId: 'p', modelId: 'm', messages: [], signal: controller.signal });
    await started;
    controller.abort();
    await assert.rejects(pending, { code: 'broker_cancelled' });
    assert.equal(cancelledId, requestId);
    release(fd);
    await delay(10);
    assert.throws(() => fstatSync(fd), { code: 'EBADF' });
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('a probe cancelled before admission never opens the native capability', async () => {
  let opened = 0;
  const client = createNativeProbeClient({ open() { opened++; }, cancel() {} });
  const controller = new AbortController(); controller.abort();
  await assert.rejects(client.stream({ signal: controller.signal }), { code: 'broker_cancelled' });
  assert.equal(opened, 0);
});
