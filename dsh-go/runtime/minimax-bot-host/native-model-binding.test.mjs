// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import { test } from 'node:test';

import { createNativeModelBinding, NativeBindingError } from './native-model-binding.mjs';

function fixture() {
  const calls = [];
  const scope = {
    fetch: () => Promise.resolve(new Response('{}')),
    run(fetchImpl, operation) {
      calls.push(['run', typeof fetchImpl]);
      return operation();
    },
  };
  const routeFactory = (input) => {
    calls.push(['route', input]);
    return { fetch: async () => new Response('ok'), baseUrl: 'https://models.mbot.invalid/v1' };
  };
  const binding = createNativeModelBinding({
    byForkProviderId: {
      'custom_provider:minimax': { nativeProviderId: 'minimax', apiFormat: 'openai-completions', models: ['m1'] },
    },
    routeFactory,
    scope,
  });
  return { binding, calls };
}

test('resolves a model key to the native route for that provider', () => {
  const { binding, calls } = fixture();
  const route = binding.routeFor('custom_provider:minimax/m1');
  assert.equal(route.baseUrl, 'https://models.mbot.invalid/v1');
  assert.deepEqual(calls[0][1], { providerId: 'minimax', modelId: 'm1', apiFormat: 'openai-completions' });
});

test('refuses a model key that is not in the native catalog', () => {
  const { binding } = fixture();
  assert.throws(() => binding.routeFor('custom_provider:other/m1'),
    (error) => error instanceof NativeBindingError && error.code === 'model_not_available');
});

test('refuses a malformed model key', () => {
  const { binding } = fixture();
  for (const key of ['nonsense', '/m1', 'custom_provider:minimax/', '', 42]) {
    assert.throws(() => binding.routeFor(key),
      (error) => error instanceof NativeBindingError && error.code === 'invalid_model_key');
  }
});

test('refuses a model outside the provider model list', () => {
  const { binding } = fixture();
  assert.throws(() => binding.routeFor('custom_provider:minimax/other'),
    (error) => error instanceof NativeBindingError && error.code === 'model_not_available');
});

test('runs the operation inside the bound scope', () => {
  const { binding, calls } = fixture();
  binding.install({});
  const result = binding.runForModelKey('custom_provider:minimax/m1', () => 'done');
  assert.equal(result, 'done');
  assert.equal(calls.some(([kind]) => kind === 'run'), true);
});

test('refuses to run a turn before the fetch scope is installed', () => {
  const { binding } = fixture();
  assert.throws(() => binding.runForModelKey('custom_provider:minimax/m1', () => 'done'),
    (error) => error instanceof NativeBindingError && error.code === 'binding_not_installed');
});

test('installs the scoped fetch exactly once on the target', () => {
  const { binding } = fixture();
  const target = {};
  binding.install(target);
  binding.install(target);
  assert.equal(typeof target.fetch, 'function');
});

test('restores the previous fetch when the binding closes', () => {
  const { binding } = fixture();
  const original = () => 'original';
  const target = { fetch: original };
  binding.install(target);
  assert.notEqual(target.fetch, original);
  binding.close();
  assert.equal(target.fetch, original);
});

test('addresses the native virtual origin through the default route factory', () => {
  const binding = createNativeModelBinding({
    byForkProviderId: {
      'custom_provider:minimax': { nativeProviderId: 'minimax', apiFormat: 'anthropic-messages', models: ['m1'] },
    },
    binding: { open() { return Promise.resolve(null); }, cancel() {} },
  });
  const route = binding.routeFor('custom_provider:minimax/m1');
  assert.equal(route.baseUrl, 'https://models.mbot.invalid');
  assert.equal(route.apiKey, 'native-broker-placeholder');
  assert.equal(route.providerId, 'minimax');
});

test('requires a native broker binding when no route factory is supplied', () => {
  assert.throws(() => createNativeModelBinding({ byForkProviderId: {} }), TypeError);
});
