// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { nativeReviewOptions, withNativeByokHost } from './runtime-native-review.mjs';

test('native BYOK changes only the upstream review policy cell and never mutates its input', async () => {
  const input = { marker: {}, ownerPolicy: { kind: 'webui', wiring: { miniAppService: true },
    execution: { reviewPolicy: 'neither', restricted: false, promptProfile: 'desktop' } } };
  assert.equal(nativeReviewOptions(input), input);
  const actual = await withNativeByokHost(async () => { await Promise.resolve(); return nativeReviewOptions(input); });
  assert.equal(actual.marker, input.marker);
  assert.equal(actual.ownerPolicy.wiring, input.ownerPolicy.wiring);
  assert.equal(actual.ownerPolicy.kind, 'webui');
  assert.deepEqual(actual.ownerPolicy.execution, { ...input.ownerPolicy.execution, reviewPolicy: 'cli' });
  assert.equal(input.ownerPolicy.execution.reviewPolicy, 'neither');
  assert.equal(nativeReviewOptions(input), input);
});

test('a changed upstream owner contract is refused instead of silently changing policy', () => {
  for (const ownerPolicy of [null, { kind: 'desktop', execution: { reviewPolicy: 'neither' } },
    { kind: 'webui', execution: { reviewPolicy: 'tui' } }]) {
    assert.throws(() => withNativeByokHost(() => nativeReviewOptions({ ownerPolicy })), { code: 'runtime_review_policy_mismatch' });
  }
});
