// SPDX-License-Identifier: AGPL-3.0-only
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, openSync, fstatSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { setTimeout as delay } from 'node:timers/promises';
import { createNativeModelFetch } from './model-sdk-fetch.mjs';
import { runSdkFdProbe } from './model-sdk-fixture.helper.mjs';

const url = 'https://model.example/v1/chat/completions';
const config = { providerId: 'p1', modelId: 'm1', apiFormat: 'openai-completions', requestUrl: url };
const init = { method: 'POST', body: JSON.stringify({ model: 'm1', messages: [{ role: 'user', content: 'Hello' }], stream: true }) };

test('SDK fetch rejects other URLs/methods/models, oversized and malformed JSON before opening a channel', async () => {
  let opened = 0;
  const fetchImpl = createNativeModelFetch({ ...config, openChannel() { opened++; throw Error('unsafe-diagnostic'); } });
  for (const [target, options] of [
    ['https://elsewhere.example/v1/chat/completions', init],
    [url + '?key=caller-input', init], [url, { method: 'GET' }],
    [url, { ...init, body: '{' }], [url, { ...init, body: '[]' }],
    [url, { ...init, body: JSON.stringify({ model: 'other' }) }],
    [url, { ...init, body: JSON.stringify({ stream: false }) }],
    [url, { ...init, body: ' '.repeat(1024 * 1024 + 1) }],
  ]) {
    const response = await fetchImpl(target, options);
    assert.equal(response.status, 400);
    assert.equal((await response.json()).error.code, 'broker_request_invalid');
  }
  assert.equal(opened, 0);
  for (const requestUrl of ['http://model.example/v1', 'https://user:password@model.example/v1', url + '#fragment']) {
    assert.throws(() => createNativeModelFetch({ ...config, requestUrl, openChannel() {} }), { code: 'broker_request_invalid' });
  }
});

test('bridge failures are stable nonretryable responses without caller headers or diagnostics', async () => {
  let received;
  const fetchImpl = createNativeModelFetch({ ...config, openChannel(payload) { received = payload; throw Error('unsafe-diagnostic'); } });
  const response = await fetchImpl(url, { ...init, headers: { authorization: 'Bearer placeholder', 'x-sdk-header': 'discard-me' } });
  assert.equal(response.status, 400);
  assert.deepEqual(received, { providerId: 'p1', modelId: 'm1', request: JSON.parse(init.body) });
  const text = await response.text();
  assert.equal(text.includes('unsafe-diagnostic'), false);
  assert.equal(text.includes('placeholder'), false);
  assert.equal(text.includes('discard-me'), false);
  assert.equal(JSON.parse(text).error.code, 'broker_unavailable');
});

test('a stalled request body with uncooperative cancel cannot pin the fetch deadline', { timeout: 2000 }, async () => {
  let opened = false;
  const fetchImpl = createNativeModelFetch({ ...config, timeoutMs: 40, openChannel() { opened = true; } });
  const response = await fetchImpl(url, { method: 'POST', duplex: 'half', body: new ReadableStream({
    pull() { return new Promise(() => {}); },
    cancel() { return new Promise(() => {}); },
  }) });
  assert.equal(response.status, 400);
  assert.equal((await response.json()).error.code, 'broker_stream_failed');
  assert.equal(opened, false);
});

test('cancellation and deadlines interrupt bridge wait and dispose an FD returned late', async () => {
  const directory = mkdtempSync(join(tmpdir(), 'mbot-sdk-late-'));
  try {
    for (const abort of [true, false]) {
      const fd = openSync(join(directory, 'owned-file'), 'w+');
      let release, started;
      const opening = new Promise((resolve) => { started = resolve; });
      const controller = new AbortController();
      const fetchImpl = createNativeModelFetch({ ...config, timeoutMs: abort ? 2000 : 40,
        openChannel() { started(); return new Promise((resolve) => { release = resolve; }); } });
      const pending = fetchImpl(url, { ...init, signal: controller.signal });
      await opening;
      if (abort) controller.abort();
      if (abort) await assert.rejects(pending, { code: 'broker_cancelled' });
      else assert.equal((await (await pending).json()).error.code, 'broker_stream_failed');
      release(fd);
      await delay(10);
      assert.throws(() => fstatSync(fd), { code: 'EBADF' });
    }
    let called = false;
    const controller = new AbortController(); controller.abort();
    const fetchImpl = createNativeModelFetch({ ...config, openChannel() { called = true; } });
    await assert.rejects(fetchImpl(url, { ...init, signal: controller.signal }), { code: 'broker_cancelled' });
    assert.equal(called, false);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test('inherited real FD becomes provider-shaped SSE with event names and correct terminal markers', { timeout: 15000 }, async () => {
  for (const format of ['openai-completions', 'openai-responses', 'anthropic-messages', 'google-generative-ai']) {
    const event = format === 'anthropic-messages' ? { type: 'message_stop' } :
      format === 'openai-responses' ? { type: 'response.completed', response: { id: 'r1' } } : { choices: [{ delta: { content: 'Hello' } }] };
    const result = await runSdkFdProbe({ format, events: [event] });
    assert.equal(result.status, 200);
    assert.ok(result.body.includes(JSON.stringify(event)));
    assert.equal(result.body.includes('event:'), ['anthropic-messages', 'openai-responses'].includes(format));
    assert.equal(result.body.includes('[DONE]'), format === 'openai-completions');
    assert.equal(result.channelCount, 1);
    assert.deepEqual(Object.keys(result.payload).sort(), ['modelId', 'providerId', 'request']);
  }
});

test('SDK response cancellation, unconsumed timeout, truncated stream and invalid event types close real FD', { timeout: 15000 }, async () => {
  assert.equal((await runSdkFdProbe({ mode: 'cancel', quiet: true })).code, 'cancelled');
  assert.equal((await runSdkFdProbe({ mode: 'cancel-full', events: Array.from({ length: 20 }, () => ({ text: 'x'.repeat(64 * 1024) })) })).code, 'cancelled');
  assert.equal((await runSdkFdProbe({ mode: 'unconsumed', quiet: true })).code, 'broker_stream_failed');
  assert.equal((await runSdkFdProbe({ events: [{ delta: 'partial' }], truncated: true })).code, 'broker_stream_failed');
  assert.equal((await runSdkFdProbe({ format: 'anthropic-messages', events: [{ type: 'bad\nevent' }] })).code, 'broker_invalid_response');
});
