// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createProviderBroker } from './provider-broker.mjs';
import { BrokerError } from './model-broker.mjs';

for (const [format, event] of [
  ['openai-completions', { choices: [{ delta: { content: 'OK' } }] }],
  ['openai-responses', { type: 'response.output_text.delta', delta: 'OK' }],
  ['anthropic-messages', { type: 'content_block_delta', delta: { type: 'text_delta', text: 'OK' } }],
  ['google-generative-ai', { candidates: [{ content: { parts: [{ text: 'OK' }] } }] }],
]) {
  test(`probes ${format} through native using only IDs and cancels after text`, async () => {
    let calls = 0;
    const broker = createProviderBroker({ modelBroker: {
      async stream(input) {
        calls++;
        assert.deepEqual(Object.keys(input).sort(), ['messages', 'modelId', 'onEvent', 'providerId', 'signal']);
        assert.equal(input.providerId, 'p1');
        assert.equal(input.modelId, 'm1');
        input.onEvent({ type: 'metadata' });
        assert.equal(input.signal.aborted, false);
        input.onEvent(event);
        assert.equal(input.signal.aborted, true);
        throw new BrokerError('broker_cancelled');
      },
    } });
    assert.deepEqual(await broker.testProvider('p1', 'm1'), { ok: true });
    assert.equal(calls, 1);
    assert.deepEqual(Object.keys(broker), ['testProvider']);
  });
}

test('refuses invalid IDs and the obsolete Key resolver interface', async () => {
  let calls = 0;
  const broker = createProviderBroker({ modelBroker: { async stream() { calls++; } } });
  for (const [provider, model] of [['', 'm'], ['p', ''], ['p', 'a'.repeat(257)]]) {
    assert.deepEqual(await broker.testProvider(provider, model), { ok: false, code: 'invalid_provider' });
  }
  assert.equal(calls, 0);
  assert.throws(() => createProviderBroker({ resolveProvider() {} }), TypeError);
});

test('does not expose arbitrary native errors or retry unknown outcomes', async () => {
  let calls = 0;
  for (const failure of [new Error('fake-private-upstream-detail'), new BrokerError('private_error_not_allowlisted')]) {
    const broker = createProviderBroker({ modelBroker: { async stream() { calls++; throw failure; } } });
    assert.deepEqual(await broker.testProvider('p', 'm'), { ok: false, code: 'request_failed' });
  }
  const broker = createProviderBroker({ modelBroker: { async stream() { throw new BrokerError('invalid_provider'); } } });
  assert.deepEqual(await broker.testProvider('p', 'm'), { ok: false, code: 'invalid_provider' });
  assert.equal(calls, 2);
});

test('metadata-only or empty streams are not reported as a successful model probe', async () => {
  const broker = createProviderBroker({ modelBroker: { async stream({ onEvent }) { onEvent({ choices: [{ delta: { role: 'assistant' } }] }); } } });
  assert.deepEqual(await broker.testProvider('p', 'm'), { ok: false, code: 'invalid_response' });
});

test('a stalled native stream is cancelled at the probe deadline', async () => {
  let cancelled = false;
  const broker = createProviderBroker({ timeoutMs: 10, modelBroker: { async stream({ signal }) {
    await new Promise((resolve, reject) => signal.addEventListener('abort', () => {
      cancelled = true; reject(new BrokerError('broker_cancelled'));
    }, { once: true }));
  } } });
  assert.deepEqual(await broker.testProvider('p', 'm'), { ok: false, code: 'request_timeout' });
  assert.equal(cancelled, true);
});
