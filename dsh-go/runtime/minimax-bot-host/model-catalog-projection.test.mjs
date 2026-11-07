// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import { test } from 'node:test';

import { applyNativeCatalog, projectNativeCatalog, PROJECTED_API_FORMATS } from './model-catalog-projection.mjs';

function catalog(overrides = {}) {
  return {
    providers: [{
      providerId: 'minimax',
      name: 'MiniMax',
      apiFormat: 'openai-completions',
      enabled: true,
      models: ['MiniMax-M3.1-Flash'],
    }],
    defaultProviderId: 'minimax',
    defaultModelId: 'MiniMax-M3.1-Flash',
    ...overrides,
  };
}

/** A stand-in for the pinned upstream provider facade. */
function facade() {
  const calls = { listUser: 0, create: [], update: [], delete: [], select: [], testProvider: [] };
  const providers = [];
  const application = {
    modelProviders: {
      async listUser() {
        calls.listUser += 1;
        return providers.map((provider) => ({ ...provider }));
      },
      async create(input) {
        calls.create.push(input);
        const view = { providerId: `custom_provider:${input.name}`, name: input.name, models: input.models };
        providers.push(view);
        return view;
      },
      async update(input) {
        calls.update.push(input);
        const view = { providerId: input.providerId, name: input.name, models: input.models };
        providers.push(view);
        return view;
      },
      async delete(input) {
        calls.delete.push(input);
        const index = providers.findIndex((row) => row.providerId === input.providerId);
        if (index >= 0) providers.splice(index, 1);
      },
      async testProvider(input) {
        calls.testProvider.push(input);
        return { success: true };
      },
    },
    models: {
      async select(input) {
        calls.select.push(input);
        return true;
      },
    },
  };
  return { application, calls, providers };
}

test('projects enabled providers with a deterministic display name', () => {
  const result = projectNativeCatalog(catalog());
  assert.deepEqual(result.upserts, [{
    providerId: 'minimax',
    displayName: 'minimax',
    apiFormat: 'openai-completions',
    models: ['MiniMax-M3.1-Flash'],
  }]);
  assert.deepEqual(result.defaultSelection, { providerId: 'minimax', modelId: 'MiniMax-M3.1-Flash' });
  assert.deepEqual(result.rejected, []);
});

test('sanitizes the display name so the upstream key stays deterministic', () => {
  const result = projectNativeCatalog(catalog({
    providers: [{ providerId: 'my.provider', name: 'x', apiFormat: 'openai-completions', enabled: true, models: ['m1'] }],
    defaultProviderId: null,
    defaultModelId: null,
  }));
  assert.equal(result.upserts[0].displayName, 'my-provider');
});

test('rejects an unsupported api format instead of dropping it silently', () => {
  const result = projectNativeCatalog(catalog({
    providers: [{ providerId: 'p1', name: 'p', apiFormat: 'weird-format', enabled: true, models: ['m1'] }],
    defaultProviderId: null,
    defaultModelId: null,
  }));
  assert.deepEqual(result.upserts, []);
  assert.deepEqual(result.rejected, [{ providerId: 'p1', reason: 'unsupported_api_format' }]);
});

test('skips disabled providers and providers without models', () => {
  const result = projectNativeCatalog(catalog({
    providers: [
      { providerId: 'off', name: 'o', apiFormat: 'openai-completions', enabled: false, models: ['m'] },
      { providerId: 'empty', name: 'e', apiFormat: 'openai-completions', enabled: true, models: [] },
    ],
    defaultProviderId: null,
    defaultModelId: null,
  }));
  assert.deepEqual(result.upserts, []);
  assert.deepEqual(result.rejected, []);
});

test('omits the default selection when the pair is incomplete or unknown', () => {
  assert.equal(projectNativeCatalog(catalog({ providers: [], defaultProviderId: 'p1', defaultModelId: 'm1' })).defaultSelection, null);
  assert.equal(projectNativeCatalog(catalog({ defaultModelId: 'unknown' })).defaultSelection, null);
  assert.equal(projectNativeCatalog(catalog({ defaultProviderId: 'other' })).defaultSelection, null);
});

test('refuses a catalog that carries credential-like fields', () => {
  assert.throws(() => projectNativeCatalog(catalog({
    providers: [{ providerId: 'p1', apiKey: 'sk-live' }],
  })), /native_catalog_contains_credential/);
  assert.throws(() => projectNativeCatalog(catalog({
    providers: [{ providerId: 'p1', options: { api_key: 'sk-live' } }],
  })), /native_catalog_contains_credential/);
});

test('refuses a catalog that carries the real upstream endpoint', () => {
  assert.throws(() => projectNativeCatalog(catalog({
    providers: [{ providerId: 'p1', baseUrl: 'https://api.minimax.io/v1' }],
  })), /native_catalog_forbidden_field/);
});

test('accepts the non-secret api key presence flag', () => {
  const result = projectNativeCatalog(catalog({
    providers: [{ providerId: 'p1', hasApiKey: true, apiFormat: 'openai-completions', enabled: true, models: ['m1'] }],
    defaultProviderId: null,
    defaultModelId: null,
  }));
  assert.equal(result.upserts.length, 1);
  assert.throws(() => projectNativeCatalog(catalog({
    providers: [{ providerId: 'p1', hasApiKey: 'yes' }],
  })), /native_catalog_contains_credential/);
});

test('refuses two providers that sanitize to the same display name', () => {
  const result = projectNativeCatalog(catalog({
    providers: [
      { providerId: 'my.provider', apiFormat: 'openai-completions', enabled: true, models: ['m1'] },
      { providerId: 'my-provider', apiFormat: 'openai-completions', enabled: true, models: ['m2'] },
    ],
    defaultProviderId: null,
    defaultModelId: null,
  }));
  assert.equal(result.upserts.length, 1);
  assert.deepEqual(result.rejected, [{ providerId: 'my-provider', reason: 'duplicate_display_name' }]);
});

test('exposes the four supported api formats', () => {
  assert.deepEqual([...PROJECTED_API_FORMATS].sort(),
    ['anthropic-messages', 'google-generative-ai', 'openai-completions', 'openai-responses']);
});

test('projects through the upstream provider API and selects the default model', async () => {
  const { application, calls } = facade();
  const result = await applyNativeCatalog({ application, catalog: catalog() });
  assert.deepEqual(calls.create, [{
    name: 'minimax',
    baseUrl: 'https://models.mbot.invalid/v1',
    apiFormat: 'openai-completions',
    models: [{ modelId: 'MiniMax-M3.1-Flash' }],
    apiKey: 'native-broker-placeholder',
  }]);
  assert.deepEqual(calls.select, [{ providerId: 'custom_provider:minimax', modelId: 'MiniMax-M3.1-Flash' }]);
  assert.deepEqual(result.providerBinding, [{
    forkProviderId: 'custom_provider:minimax',
    nativeProviderId: 'minimax',
    apiFormat: 'openai-completions',
    models: ['MiniMax-M3.1-Flash'],
    connectivity: 'not_run',
  }]);
  assert.equal(result.defaultSelection.modelId, 'MiniMax-M3.1-Flash');
});

test('updates a known provider without sending an api key', async () => {
  const { application, calls } = facade();
  await applyNativeCatalog({ application, catalog: catalog() });
  const second = await applyNativeCatalog({ application, catalog: catalog() });
  assert.equal(calls.create.length, 1);
  assert.equal(calls.update.length, 1);
  assert.equal(calls.update[0].providerId, 'custom_provider:minimax');
  assert.equal('apiKey' in calls.update[0], false);
  assert.equal(second.providerBinding[0].forkProviderId, 'custom_provider:minimax');
});

test('never calls the upstream facade without a projected provider', async () => {
  const { application, calls } = facade();
  const result = await applyNativeCatalog({ application, catalog: null });
  assert.deepEqual(result, { providerBinding: [], defaultSelection: null, rejected: [] });
  assert.equal(calls.listUser, 0);
  assert.deepEqual(calls.create, []);
  assert.deepEqual(calls.select, []);
});

test('keeps a provider whose connectivity test failed out of the default selection', async () => {
  const { application, calls } = facade();
  const result = await applyNativeCatalog({
    application,
    catalog: catalog(),
    probe: async (providerId) => {
      calls.testProvider.push({ providerId });
      return { ok: false };
    },
  });
  assert.equal(result.providerBinding[0].connectivity, 'failed');
  assert.deepEqual(calls.select, []);
  assert.equal(result.defaultSelection, null);
});

test('runs the connectivity probe through the official test and selects on success', async () => {
  const { application, calls } = facade();
  const result = await applyNativeCatalog({
    application,
    catalog: catalog(),
    probe: async (providerId) => {
      const outcome = await application.modelProviders.testProvider({ providerId });
      return { ok: outcome?.success === true };
    },
  });
  assert.deepEqual(calls.testProvider, [{ providerId: 'custom_provider:minimax' }]);
  assert.equal(result.providerBinding[0].connectivity, 'ok');
  assert.equal(calls.select.length, 1);
});

test('refuses to continue when the upstream provider API rejects a provider', async () => {
  const { application, calls } = facade();
  application.modelProviders.create = async () => {
    throw Object.assign(new Error('refused'), { code: 'TEST_REQUIRED' });
  };
  await assert.rejects(() => applyNativeCatalog({ application, catalog: catalog() }), (error) => {
    assert.equal(error.code, 'runtime_provider_projection_failed');
    assert.equal(error.upstreamCode, 'TEST_REQUIRED');
    return true;
  });
  assert.deepEqual(calls.select, []);
});

test('per-model mcode parameters reach the facade; disabled models never bind', async () => {
  const { application, calls } = facade();
  const input = catalog();
  input.providers[0].models = ['m1', 'm2'];
  input.providers[0].modelSettings = [
    { modelId: 'm1', enabled: true, limit: { context: 1000000, output: 128000 },
      effortOptions: ['high', 'custom-level'], modalities: { input: ['text', 'image', 'pdf', 'audio', 'video'], output: ['text'] } },
    { modelId: 'm2', enabled: false },
  ];
  input.defaultModelId = 'm2';
  const result = await applyNativeCatalog({ application, catalog: input });
  assert.deepEqual(calls.create[0].models, [
    { ...input.providers[0].modelSettings[0], attachment: true }, { modelId: 'm2', enabled: false },
  ]);
  assert.deepEqual(result.providerBinding[0].models, ['m1']);
  assert.equal(result.defaultSelection, null);
  assert.deepEqual(calls.select, []);
});

test('all models can be disabled independently without creating a route', async () => {
  const input = catalog();
  input.providers[0].modelSettings = [{ modelId: input.defaultModelId, enabled: false }];
  const { application, calls } = facade();
  const result = await applyNativeCatalog({ application, catalog: input });
  assert.deepEqual(result.providerBinding, []);
  assert.equal(result.defaultSelection, null);
  assert.deepEqual(calls.create, []);
});

test('model overrides reject malformed limits, unknown fields and hidden secrets', () => {
  for (const patch of [
    { limit: { context: 0 } }, { limit: { output: 1.5 } }, { limit: { context: Number.MAX_SAFE_INTEGER + 1 } },
    { baseUrl: 'https://example.invalid' }, { apiKey: 'test-only-secret' },
    { modalities: { input: ['executable'] } }, { enabled: 'false' }, { effortOptions: [''] },
    { reasoning: false, effortOptions: ['high'] }, { options: { arbitrary: true } },
  ]) {
    const input = catalog();
    input.providers[0].modelSettings = [{ modelId: input.defaultModelId, ...patch }];
    assert.throws(() => projectNativeCatalog(input));
  }
  const input = catalog();
  input.providers[0].modelSettings = [{ modelId: 'unknown' }];
  assert.throws(() => projectNativeCatalog(input), /native_model_settings_invalid/);
});

test('clearing a configured limit replaces only the native-owned provider through official APIs', async () => {
  const { application, calls } = facade();
  const input = catalog();
  input.providers[0].modelSettings = [{ modelId: input.defaultModelId, limit: { context: 8192 } }];
  await applyNativeCatalog({ application, catalog: input });
  input.providers[0].modelSettings = [{ modelId: input.defaultModelId, enabled: true }];
  const result = await applyNativeCatalog({ application, catalog: input });
  assert.deepEqual(calls.delete, [{ providerId: 'custom_provider:minimax' }]);
  assert.deepEqual(calls.create[1].models, [{ modelId: input.defaultModelId, enabled: true }]);
  assert.equal(result.providerBinding[0].forkProviderId, 'custom_provider:minimax');
});
