// SPDX-License-Identifier: AGPL-3.0-only
import test from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { runSdkFdProbe } from '../../runtime/minimax-bot-host/model-sdk-fixture.helper.mjs';

const sdkRoot = fileURLToPath(new URL('.', import.meta.url));
const fixtures = {
  'openai-completions': [
    { id: 'c1', object: 'chat.completion.chunk', choices: [{ index: 0, delta: {
      tool_calls: [{ index: 0, id: 'call1', type: 'function', function: { name: 'lookup', arguments: '{"path":' } }],
    }, finish_reason: null }] },
    { id: 'c1', object: 'chat.completion.chunk', choices: [{ index: 0, delta: {
      tool_calls: [{ index: 0, function: { arguments: '"notes"}' } }],
    }, finish_reason: 'tool_calls' }] },
  ],
  'openai-responses': [
    { type: 'response.created', response: { id: 'r1', status: 'in_progress', output: [] } },
    { type: 'response.output_item.added', output_index: 0, item: { id: 'f1', type: 'function_call', call_id: 'call1', name: 'lookup', arguments: '' } },
    { type: 'response.function_call_arguments.delta', item_id: 'f1', output_index: 0, delta: '{"path":"notes"}' },
    { type: 'response.completed', response: { id: 'r1', status: 'completed', output: [] } },
  ],
  'anthropic-messages': [
    { type: 'message_start', message: { id: 'a1', type: 'message', role: 'assistant', content: [], model: 'm1', stop_reason: null,
      stop_sequence: null, usage: { input_tokens: 1, output_tokens: 0 } } },
    { type: 'content_block_start', index: 0, content_block: { type: 'tool_use', id: 'call1', name: 'lookup', input: {} } },
    { type: 'content_block_delta', index: 0, delta: { type: 'input_json_delta', partial_json: '{"path":"notes"}' } },
    { type: 'content_block_stop', index: 0 },
    { type: 'message_delta', delta: { stop_reason: 'tool_use', stop_sequence: null }, usage: { output_tokens: 10 } },
    { type: 'message_stop' },
  ],
};

for (const [format, events] of Object.entries(fixtures)) {
  test(`pinned ${format} SDK consumes real FD tool events and emits a provider-shaped request`, { timeout: 10000 }, async () => {
    const result = await runSdkFdProbe({ format, events, mode: 'sdk', sdkRoot });
    assert.deepEqual(result.events, events);
    assert.equal(result.channelCount, 1);
    assert.equal(result.payload.providerId, 'p1');
    assert.equal(result.payload.modelId, 'm1');
    assert.equal(result.payload.request.model, 'm1');
    assert.equal(result.payload.request.stream, true);
    assert.equal(result.payload.request.tools.length, 1);
    const text = JSON.stringify(result);
    assert.equal(text.includes('native-broker-placeholder'), false);
    assert.equal(text.includes('authorization'), false);
    assert.equal(text.includes('x-api-key'), false);
  });
  test(`pinned ${format} SDK does not retry a bridge failure or expose its diagnostics`, { timeout: 10000 }, async () => {
    const result = await runSdkFdProbe({ format, mode: 'sdk-failure', sdkRoot, quiet: true });
    assert.equal(result.status, 400);
    assert.equal(result.code, 'broker_unavailable');
    assert.equal(result.channelCount, 1);
    assert.equal(result.hasUnsafeDiagnostic, false);
  });
  test(`pinned ${format} SDK early iterator exit closes its native FD`, { timeout: 10000 }, async () => {
    const result = await runSdkFdProbe({ format, events, mode: 'sdk-cancel', sdkRoot });
    assert.deepEqual(result.events, events.slice(0, 1));
    assert.equal(result.channelCount, 1);
  });
}
