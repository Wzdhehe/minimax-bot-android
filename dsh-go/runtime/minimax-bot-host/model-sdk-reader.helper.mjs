// SPDX-License-Identifier: AGPL-3.0-only
import { createNativeModelFetch } from './model-sdk-fetch.mjs';
import { setTimeout as delay } from 'node:timers/promises';
import { pathToFileURL } from 'node:url';

const [format, mode, sdkRoot] = process.argv.slice(2);
const url = format === 'openai-completions' ? 'https://model.example/v1/chat/completions' :
  format === 'openai-responses' ? 'https://model.example/v1/responses' :
  format === 'anthropic-messages' ? 'https://model.example/v1/messages' :
  'https://model.example/v1/models/m1:streamGenerateContent?alt=sse';
let payload, channelCount = 0;
const fetchImpl = createNativeModelFetch({ providerId: 'p1', modelId: 'm1', apiFormat: format, requestUrl: url,
  timeoutMs: mode === 'unconsumed' ? 100 : 2500,
  openChannel(value) {
    payload = value; channelCount++;
    if (mode === 'sdk-failure') throw Error('unsafe-diagnostic');
    return 3;
  },
});
let result;
try {
  if (mode.startsWith('sdk')) {
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
