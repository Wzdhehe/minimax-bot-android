// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createProviderBroker } from './provider-broker.mjs';

const secret = 'test-provider-secret';
const provider = { enabled: true, baseUrl: 'https://example.com/v1', models: ['model'], apiKey: secret };
for (const [format, path, field, header] of [
  ['openai-completions', '/v1/chat/completions', 'choices', 'authorization'],
  ['openai-responses', '/v1/responses', 'output', 'authorization'],
  ['anthropic-messages', '/v1/messages', 'content', 'x-api-key'],
  ['google-generative-ai', '/v1/models/model:generateContent', 'candidates', 'x-goog-api-key'],
]) {
  test(`injects the selected provider key only into the ${format} request`, async () => {
    const broker = createProviderBroker({
      resolveProvider: async (id) => { assert.equal(id, 'provider-1'); return { ...provider, apiFormat: format }; },
      fetchImpl: async (url, options) => {
        assert.equal(url.pathname, path);
        assert.equal(url.search, '');
        assert.equal(options.redirect, 'error');
        assert.equal(options.headers[header], header === 'authorization' ? `Bearer ${secret}` : secret);
        assert.ok(!options.body.includes(secret));
        return Response.json({ [field]: [{ text: secret }] });
      },
    });
    assert.deepEqual(Object.keys(broker), ['testProvider']);
    assert.deepEqual(await broker.testProvider('provider-1', 'model'), { ok: true });
  });
}
test('refuses invalid models and endpoints without a request', async () => {
  let requests = 0;
  const broker = createProviderBroker({ resolveProvider: async () => ({ ...provider, apiFormat: 'openai-completions', baseUrl: 'http://example.com' }), fetchImpl: async () => { requests++; } });
  assert.equal((await broker.testProvider('p', 'other')).code, 'invalid_provider');
  assert.equal((await broker.testProvider('p', 'model')).code, 'invalid_endpoint');
  assert.equal(requests, 0);
});
test('provider errors cannot echo credentials into the returned projection', async () => {
  for (const fail of [async () => { throw new Error(secret); }, async () => new Response(secret, { status: 401 }), async () => new Response(secret)]) {
    const broker = createProviderBroker({ resolveProvider: async () => ({ ...provider, apiFormat: 'openai-completions' }), fetchImpl: fail });
    const result = await broker.testProvider('p', 'model');
    assert.equal(result.ok, false);
    assert.ok(!JSON.stringify(result).includes(secret));
  }
});
