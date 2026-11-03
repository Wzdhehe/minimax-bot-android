// SPDX-License-Identifier: AGPL-3.0-only
import { createModelFetchScope } from './model-fetch-scope.mjs';
import { createNativeModelFetch } from './model-sdk-fetch.mjs';
import { setTimeout as delay } from 'node:timers/promises';
import { pathToFileURL } from 'node:url';

const [format, mode, sdkRoot] = process.argv.slice(2);
const url = format === 'openai-completions' ? 'https://model.example/v1/chat/completions' :
  format === 'openai-responses' ? 'https://model.example/v1/responses' :
  format === 'anthropic-messages' ? 'https://model.example/v1/messages' :
  'https://model.example/v1/models/m1:streamGenerateContent?alt=sse';
let payload, channelCount = 0;
let providerAbort, providerTimer;
const fetchImpl = createNativeModelFetch({ providerId: 'p1', modelId: 'm1', apiFormat: format, requestUrl: url,
  timeoutMs: mode === 'unconsumed' ? 100 : 2500,
  openChannel(value) {
    payload = value; channelCount++;
    if (mode === 'sdk-failure') throw Error('unsafe-diagnostic');
    if (mode === 'pi-cancel') providerTimer = setTimeout(() => providerAbort.abort(), 80);
    return 3;
  },
});
let result;
try {
  if (mode.startsWith('pi')) {
    const names = { 'openai-completions': ['openai-completions', 'streamOpenAICompletions'],
      'openai-responses': ['openai-responses', 'streamOpenAIResponses'],
      'anthropic-messages': ['anthropic', 'streamAnthropic'], 'google-generative-ai': ['google', 'streamGoogle'] };
    const [name, symbol] = names[format];
    const scope = createModelFetchScope();
    globalThis.fetch = scope.fetch;
    const provider = await import(pathToFileURL(`${sdkRoot}/.build/${name}.mjs`));
    const controller = new AbortController();
    providerAbort = controller;
    const model = { id: 'm1', name: 'Probe', api: format,
      provider: format === 'google-generative-ai' ? 'google' : format === 'anthropic-messages' ? 'anthropic' : 'openai',
      baseUrl: format === 'anthropic-messages' ? 'https://model.example' : 'https://model.example/v1',
      reasoning: false, input: ['text'], cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
      contextWindow: 8192, maxTokens: 256 };
    const context = { messages: [{ role: 'user', content: 'Hello', timestamp: Date.now() }],
      tools: [{ name: 'lookup', description: 'Lookup a value', parameters: { type: 'object', properties: {} } }] };
    const options = { apiKey: 'native-broker-placeholder', fetch: fetchImpl, signal: controller.signal,
      maxTokens: 256, temperature: 0.2, sessionId: 'probe-session', cacheRetention: 'short' };
    // Google ignores options.fetch, so only the async scope can route it.
    const stream = scope.run(fetchImpl, () => provider[symbol](model, context, options));
    const events = [];
    try { for await (const event of stream) events.push(event.type); }
    finally { if (providerTimer) clearTimeout(providerTimer); }
    const message = await stream.result();
    result = { events, message, payload, channelCount };
  } else if (mode.startsWith('sdk')) {
    // Exact provider SDK dependencies installed only in the separate probe.
    const packageName = format === 'anthropic-messages' ? '@anthropic-ai/sdk' : 'openai';
    const module = await import(pathToFileURL(`${sdkRoot}/node_modules/${packageName}/index.mjs`));
    // Anthropic appends /v1/messages; OpenAI appends /chat/completions
    // or /responses to a versioned base. These are SDK routing metadata only.
    const client = new module.default({ apiKey: 'native-broker-placeholder',
      baseURL: format === 'anthropic-messages' ? 'https://model.example' : 'https://model.example/v1', fetch: fetchImpl });
    const options = { model: 'm1', stream: true, messages: [{ role: 'user', content: 'Hello' }] };
    const tool = { name: 'lookup', description: 'Lookup a value', parameters: { type: 'object', properties: {} } };
    const stream = format === 'anthropic-messages' ? await client.messages.create({ ...options, max_tokens: 256,
      tools: [{ name: tool.name, description: tool.description, input_schema: tool.parameters }] }) :
      format === 'openai-responses' ? await client.responses.create({ model: 'm1', stream: true, input: 'Hello',
        tools: [{ type: 'function', ...tool }] }) :
      await client.chat.completions.create({ ...options, tools: [{ type: 'function', function: tool }] });
    const events = [];
    for await (const event of stream) {
      events.push(event);
      if (mode === 'sdk-cancel') break;
    }
    result = { events, payload, channelCount };
  } else {
    const body = format === 'google-generative-ai' ? { contents: [{ role: 'user', parts: [{ text: 'Hello' }] }] } :
      format === 'openai-responses' ? { model: 'm1', input: 'Hello', stream: true } :
      { model: 'm1', messages: [{ role: 'user', content: 'Hello' }], stream: true,
        ...(format === 'anthropic-messages' ? { max_tokens: 256 } : {}) };
    const response = await fetchImpl(url, { method: 'POST', headers: { authorization: 'Bearer native-broker-placeholder' },
      body: JSON.stringify(body) });
    if (mode === 'cancel' || mode === 'cancel-full') {
      if (mode === 'cancel-full') await delay(100);
      await response.body.cancel(); result = { code: 'cancelled' };
    }
    else {
      if (mode === 'unconsumed') await delay(200);
      result = { status: response.status, body: await response.text(), payload, channelCount };
    }
  }
} catch (error) { result = { code: error.code ?? error.error?.code ?? error.error?.error?.code ?? 'unexpected_error',
  status: error.status, channelCount, hasUnsafeDiagnostic: String(error.message).includes('unsafe-diagnostic') }; }
process.stdout.write(JSON.stringify(result));
