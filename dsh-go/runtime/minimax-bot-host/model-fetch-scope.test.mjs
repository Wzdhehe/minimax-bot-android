// SPDX-License-Identifier: AGPL-3.0-only
import test from 'node:test';
import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';
import { createModelFetchScope } from './model-fetch-scope.mjs';

test('scope construction leaves globals alone and unbound calls cannot reach a network fallback', async () => {
  const original = globalThis.fetch;
  const scope = createModelFetchScope();
  assert.equal(globalThis.fetch, original);
  const response = await scope.fetch('https://unbound.invalid');
  assert.equal(response.status, 400);
  assert.equal((await response.json()).error.code, 'broker_request_invalid');
  assert.throws(() => scope.run(null, () => {}), { code: 'broker_request_invalid' });
});

test('overlapping providers retain separate async bindings after synchronous stream creation returns', async () => {
  const scope = createModelFetchScope();
  const requests = [[], []];
  const launch = (index, wait) => scope.run(async (url) => { requests[index].push(url); return index; }, () => {
    // Pinned providers return a stream synchronously and start asynchronous work.
    return { result: (async () => { await delay(wait); return scope.fetch(`model-${index}`); })() };
  });
  const first = launch(0, 20), second = launch(1, 5);
  assert.deepEqual(await Promise.all([first.result, second.result]), [0, 1]);
  assert.deepEqual(requests, [['model-0'], ['model-1']]);
  assert.equal((await scope.fetch('outside')).status, 400);
});

test('nested bindings restore their parent even after exceptions', async () => {
  const scope = createModelFetchScope();
  await scope.run(async () => 'parent', async () => {
    assert.equal(await scope.fetch('one'), 'parent');
    assert.throws(() => scope.run(async () => 'child', () => { throw Error('operation failed'); }));
    assert.equal(await scope.fetch('two'), 'parent');
    assert.equal(await scope.run(async () => 'child', () => scope.fetch('three')), 'child');
  });
  assert.equal((await scope.fetch('outside')).status, 400);
});
