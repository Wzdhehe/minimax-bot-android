// SPDX-License-Identifier: AGPL-3.0-only
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { runSdkFdProbe } from '../../runtime/minimax-bot-host/model-sdk-fixture.helper.mjs';
import { piFixtures } from './stream-fixtures.mjs';

const contracts = JSON.parse(readFileSync(new URL('./request-contracts.json', import.meta.url)));
const sdkRoot = fileURLToPath(new URL('.', import.meta.url));


for (const [format, events] of Object.entries(piFixtures)) {
  test(`unchanged pinned pi-ai ${format} translates tools over native FD`, { timeout: 12000 }, async () => {
    const result = await runSdkFdProbe({ format, events, mode: 'pi', sdkRoot });
    assert.equal(result.channelCount, 1, JSON.stringify(result));
    assert.equal(result.message.stopReason, 'toolUse', JSON.stringify(result));
    assert.ok(result.events.includes('toolcall_end'));
    const call = result.message.content.find((item) => item.type === 'toolCall');
    assert.equal(call.name, 'lookup');
    assert.deepEqual(call.arguments, { path: 'notes' });
    assert.equal(result.payload.request.tools.length, 1);
    assert.deepEqual(result.payload.request, contracts.requests[format]);
    assert.equal(result.payload.providerId, 'p1');
    assert.equal(result.payload.modelId, 'm1');
    assert.equal(JSON.stringify(result).includes('native-broker-placeholder'), false);
    assert.equal(result.events.at(-1), 'done');
  });
  test(`unchanged pinned pi-ai ${format} cancellation closes a quiet model FD`, { timeout: 12000 }, async () => {
    const result = await runSdkFdProbe({ format, mode: 'pi-cancel', sdkRoot, quiet: true });
    assert.equal(result.channelCount, 1, JSON.stringify(result));
    assert.equal(result.message.stopReason, 'aborted', JSON.stringify(result));
    assert.equal(result.events.at(-1), 'error');
    assert.equal(JSON.stringify(result).includes('native-broker-placeholder'), false);
  });
}
