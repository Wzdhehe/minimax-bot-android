// SPDX-License-Identifier: AGPL-3.0-only
// Regenerate only after prepare-pi has verified and built the pinned source.
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { runSdkFdProbe } from '../../runtime/minimax-bot-host/model-sdk-fixture.helper.mjs';
import { UPSTREAM_RUNTIME_COMMIT } from '../../runtime/minimax-bot-host/upstream-runtime-pin.mjs';
import { piFixtures } from './stream-fixtures.mjs';
const root = fileURLToPath(new URL('.', import.meta.url));
assert.equal(JSON.parse(readFileSync(new URL('./.build/source-inputs.json', import.meta.url))).commit, UPSTREAM_RUNTIME_COMMIT);
const contract = { _license: 'SPDX-License-Identifier: AGPL-3.0-only', commit: UPSTREAM_RUNTIME_COMMIT, requests: {} };
for (const [format, events] of Object.entries(piFixtures)) {
  const result = await runSdkFdProbe({ format, events, mode: 'pi', sdkRoot: root });
  assert.equal(result.message.stopReason, 'toolUse');
  contract.requests[format] = result.payload.request;
}
writeFileSync(new URL('./request-contracts.json', import.meta.url), JSON.stringify(contract, null, 2) + '\n');
console.log('Captured four deterministic tool request contracts from pinned pi-ai');
