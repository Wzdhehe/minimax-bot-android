// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import { test } from 'node:test';

import { createNativeModelRuntime, resolveModelKeyFor } from './native-model-runtime.mjs';

function facade({ selected = 'custom_provider:minimax/m1' } = {}) {
  const calls = { create: [], update: [], select: [], list: [] };
  const application = {
    modelProviders: {
      async listUser() { return []; },
      async create(input) {
        calls.create.push(input);
        return { providerId: `custom_provider:${input.name}`, name: input.name };
      },
      async update(input) {
        calls.update.push(input);
        return { providerId: input.providerId, name: input.name };
      },
      async testProvider() { return { success: true }; },
    },
    models: {
      async list(input) {
        calls.list.push(input ?? {});
        return selected ? [{ providerId: 'custom_provider:minimax', modelId: 'm1',
          modelConfigId: selected, selected: true }] : [];
      },
      async select(input) { calls.select.push(input); return true; },
    },
  };
  return { application, calls };
}

const CATALOG = {
  providers: [{ providerId: 'minimax', apiFormat: 'openai-completions', enabled: true, models: ['m1'] }],
  defaultProviderId: 'minimax',
  defaultModelId: 'm1',
};

test('projects the catalog and binds the turn to the native route', async () => {
  const { application, calls } = facade();
  const target = {};
  const nativeBinding = { open() { return Promise.resolve(null); }, cancel() {} };
  const runtime = await createNativeModelRuntime({
    application, catalog: CATALOG, nativeBinding, fetchTarget: target,
  });
  assert.deepEqual(calls.create, [{
    name: 'minimax',
    baseUrl: 'https://models.mbot.invalid/v1',
    apiFormat: 'openai-completions',
    models: [{ modelId: 'm1' }],
    apiKey: 'native-broker-placeholder',
  }]);
  assert.deepEqual(calls.select, [{ providerId: 'custom_provider:minimax', modelId: 'm1' }]);
  assert.equal(runtime.providerBinding[0].forkProviderId, 'custom_provider:minimax');
  assert.equal(typeof target.fetch, 'function');
  const route = runtime.binding.routeFor('custom_provider:minimax/m1');
  assert.equal(route.baseUrl, 'https://models.mbot.invalid/v1');
  assert.equal(route.apiKey, 'native-broker-placeholder');
});

test('runs a turn inside the scope of its own model', async () => {
  const { application } = facade();
  const target = {};
  const runtime = await createNativeModelRuntime({
    application, catalog: CATALOG, nativeBinding: { open() { return Promise.resolve(null); }, cancel() {} },
    fetchTarget: target,
  });
  let inside = false;
  const result = await runtime.binding.runForModelKey(
    await runtime.resolveModelKey('session-1'),
    () => { inside = typeof target.fetch === 'function'; return 'done'; },
  );
  assert.equal(result, 'done');
  assert.equal(inside, true);
});

test('refuses a model that is not in the projected catalog', async () => {
  const { application } = facade();
  const runtime = await createNativeModelRuntime({
    application, catalog: CATALOG, nativeBinding: { open() { return Promise.resolve(null); }, cancel() {} },
    fetchTarget: {},
  });
  assert.throws(() => runtime.binding.routeFor('custom_provider:other/m1'), (error) => error.code === 'model_not_available');
});

test('leaves the guest untouched without a catalog', async () => {
  const { application, calls } = facade();
  const runtime = await createNativeModelRuntime({ application, nativeBinding: { open() {}, cancel() {} } });
  assert.deepEqual(runtime, { providerBinding: [], binding: null, resolveModelKey: null });
  assert.deepEqual(calls.create, []);
  assert.deepEqual(calls.select, []);
});

test('reads the session model from the upstream model list', async () => {
  const { application, calls } = facade();
  assert.equal(await resolveModelKeyFor(application, 'session-1'), 'custom_provider:minimax/m1');
  assert.deepEqual(calls.list, [{ sessionId: 'session-1' }]);
  const empty = facade({ selected: null });
  assert.equal(await resolveModelKeyFor(empty.application, undefined), null);
  assert.deepEqual(empty.calls.list, [{}]);
});
