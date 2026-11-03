// SPDX-License-Identifier: AGPL-3.0-only
export const fixtures = {
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
    { type: 'response.output_item.done', output_index: 0, item: { id: 'f1', type: 'function_call', call_id: 'call1', name: 'lookup', arguments: '{"path":"notes"}' } },
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

export const piFixtures = { ...fixtures, 'google-generative-ai': [
  { candidates: [{ index: 0, content: { role: 'model', parts: [{ functionCall: { name: 'lookup', args: { path: 'notes' } } }] },
    finishReason: 'STOP' }], usageMetadata: { promptTokenCount: 1, candidatesTokenCount: 2, totalTokenCount: 3 } },
] };
