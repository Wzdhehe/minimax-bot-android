// SPDX-License-Identifier: AGPL-3.0-only
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, openSync, fstatSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { setTimeout as delay } from 'node:timers/promises';
import { createNativeModelRoute } from './native-model-route.mjs';

const input = { providerId: 'p1', modelId: 'm1' };
const request = { method: 'POST', body: JSON.stringify({ model: 'm1', stream: true, messages: [{ role: 'user', content: 'Hello' }] }),
  headers: { authorization: 'Bearer discarded-placeholder' } };

test('route factories use exact virtual provider URLs and expose no native endpoint or session', async () => {
  for (const [apiFormat, url] of [
    ['openai-completions', '/v1/chat/completions'], ['openai-responses', '/v1/responses'],
    ['anthropic-messages', '/v1/messages'], ['google-generative-ai', '/v1beta/models/m1:streamGenerateContent?alt=sse'],
  ]) {
    let captured;
    const route = createNativeModelRoute({ ...input, apiFormat, binding: {
      open(id, body) { captured = { id, body }; throw Error('native-diagnostic-must-not-escape'); }, cancel() {},
    } });
    assert.equal(route.requestUrl, 'https://models.mbot.invalid' + url);
    assert.equal(route.apiKey, 'native-broker-placeholder');
    assert.equal(route.token, undefined); assert.equal(route.session, undefined);
    const response = await route.fetch(route.requestUrl, request);
    assert.equal(response.status, 400);
    assert.match(captured.id, /^[0-9a-f-]{36}$/);
    assert.deepEqual(JSON.parse(captured.body), { ...input, request: JSON.parse(request.body) });
    assert.equal((await response.text()).includes('native-diagnostic'), false);
    assert.equal(captured.body.includes('discarded-placeholder'), false);
  }
});

test('abort cancels the same native request ID and closes a late owned FD', async () => {
  const directory = mkdtempSync(join(tmpdir(), 'mbot-route-late-'));
  try {
    const fd = openSync(join(directory, 'owned-file'), 'w+');
    let started, release, openedId, cancelledId;
    const opening = new Promise((resolve) => { started = resolve; });
    const route = createNativeModelRoute({ ...input, apiFormat: 'openai-completions', binding: {
      open(id) { openedId = id; started(); return new Promise((resolve) => { release = resolve; }); },
      cancel(id) { cancelledId = id; throw Error('cancel-diagnostic'); },
    } });
    const controller = new AbortController();
    const pending = route.fetch(route.requestUrl, { ...request, signal: controller.signal });
    await opening; controller.abort();
    await assert.rejects(pending, { code: 'broker_cancelled' });
    assert.equal(cancelledId, openedId);
    release(fd); await delay(10);
    assert.throws(() => fstatSync(fd), { code: 'EBADF' });
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test('already-aborted callers never enter the native binding', async () => {
  let calls = 0;
  const route = createNativeModelRoute({ ...input, apiFormat: 'openai-completions', binding: { open() { calls++; }, cancel() {} } });
  const controller = new AbortController(); controller.abort();
  await assert.rejects(route.fetch(route.requestUrl, { ...request, signal: controller.signal }), { code: 'broker_cancelled' });
  assert.equal(calls, 0);
});
