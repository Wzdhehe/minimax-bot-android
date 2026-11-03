// SPDX-License-Identifier: AGPL-3.0-only
import test from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { runSdkFdProbe } from '../../runtime/minimax-bot-host/model-sdk-fixture.helper.mjs';

const sdkRoot = fileURLToPath(new URL('.', import.meta.url));
import { fixtures } from './stream-fixtures.mjs';

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
