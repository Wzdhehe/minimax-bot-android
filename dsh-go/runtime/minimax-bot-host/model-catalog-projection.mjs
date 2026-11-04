// SPDX-License-Identifier: AGPL-3.0-only
// Translate the native non-secret catalog into the call sequence the pinned
// upstream provider API expects.
//
// The pinned upstream `LocalModelProviderService` owns provider keys, config
// locking, atomic writes and api-key validation. This layer decides only which
// providers are projected, in which order, and which model becomes the
// default. It never assembles config YAML, never invents a provider key and
// never sees a credential.
import { nativeVirtualBaseUrl } from './native-model-route.mjs';

/** The four BYOK wire protocols the pinned upstream provider API accepts. */
export const PROJECTED_API_FORMATS = Object.freeze([
  'openai-completions',
  'openai-responses',
  'anthropic-messages',
  'google-generative-ai',
]);

/** Field names that may never reach the guest runtime. */
const CREDENTIAL_FIELD = /(api[-_.]?key|secret|token|credential|password|passwd|authorization|cookie)/i;

/** Field names that would leak the real upstream endpoint into the guest. */
const ENDPOINT_FIELD = /^(baseurl|base_url|url|endpoint|host|hostname)$/i;

/** Non-secret flags whose name shares a credential-like word. */
const SAFE_FLAG_FIELDS = new Set(['hasApiKey']);

const PROVIDER_ID = /^[A-Za-z0-9._-]{1,128}$/;
const MODEL_ID = /^[A-Za-z0-9._:/-]{1,256}$/;

/** The nonsecret credential the upstream SDK constructors require. */
const PLACEHOLDER_API_KEY = 'native-broker-placeholder';

function projectionFailure(code, upstreamCode) {
  const error = Object.assign(new Error(code), { code });
  if (typeof upstreamCode === 'string' && /^[A-Z0-9_]{1,64}$/.test(upstreamCode)) {
    error.upstreamCode = upstreamCode;
  }
  return error;
}

/**
 * Reject a catalog that carries credential-like or endpoint-like fields.
 * The whole catalog is refused: a half-projected catalog would silently hide
 * a provider the user configured.
 */
function assertCatalogSafe(catalog) {
  const walk = (value) => {
    if (!value || typeof value !== 'object') return;
    for (const [name, entry] of Object.entries(value)) {
      if (SAFE_FLAG_FIELDS.has(name)) {
        if (typeof entry !== 'boolean') throw new TypeError('native_catalog_contains_credential');
        continue;
      }
      if (CREDENTIAL_FIELD.test(name)) throw new TypeError('native_catalog_contains_credential');
      if (ENDPOINT_FIELD.test(name)) throw new TypeError('native_catalog_forbidden_field');
      walk(entry);
    }
  };
  walk(catalog);
}

/**
 * The display name is the sanitized native provider id, not the user-facing
 * name. The pinned upstream `generateProviderKey` only produces a stable key
 * for a simple ASCII name; a friendly name in a non-ASCII script would make
 * it emit a random `provider-<hex>` key and the model key would change on
 * every restart.
 */
function displayNameFor(providerId) {
  return providerId.replace(/[^A-Za-z0-9 _-]/g, '-');
}

/**
 * Project the native non-secret catalog.
 *
 * Returns the upserts the upstream provider API should receive, the default
 * model selection when it is complete and known, and the entries that were
 * refused with a stable reason.
 */
export function projectNativeCatalog(catalog) {
  assertCatalogSafe(catalog);
  const providers = Array.isArray(catalog?.providers) ? catalog.providers : [];
  const upserts = [];
  const rejected = [];
  const claimedNames = new Set();
  for (const provider of providers) {
    const providerId = typeof provider?.providerId === 'string' ? provider.providerId.trim() : '';
    if (!PROVIDER_ID.test(providerId)) {
      rejected.push({ providerId, reason: 'invalid_provider_id' });
      continue;
    }
    if (!PROJECTED_API_FORMATS.includes(provider.apiFormat)) {
      rejected.push({ providerId, reason: 'unsupported_api_format' });
      continue;
    }
    if (provider.enabled === false) continue;
    const models = [...new Set((Array.isArray(provider.models) ? provider.models : [])
      .filter((model) => typeof model === 'string' && MODEL_ID.test(model)))];
    if (!models.length) continue;
    const displayName = displayNameFor(providerId);
    if (claimedNames.has(displayName)) {
      rejected.push({ providerId, reason: 'duplicate_display_name' });
      continue;
    }
    claimedNames.add(displayName);
    upserts.push({ providerId, displayName, apiFormat: provider.apiFormat, models });
  }
  const defaultProviderId = typeof catalog?.defaultProviderId === 'string' ? catalog.defaultProviderId : null;
  const defaultModelId = typeof catalog?.defaultModelId === 'string' ? catalog.defaultModelId : null;
  const target = defaultProviderId && defaultModelId
    ? upserts.find((entry) => entry.providerId === defaultProviderId)
    : undefined;
  const defaultSelection = target && target.models.includes(defaultModelId)
    ? { providerId: defaultProviderId, modelId: defaultModelId }
    : null;
  return { upserts, defaultSelection, rejected };
}

async function resolveForkProviderId(providers, view, match, displayName) {
  if (typeof view?.providerId === 'string' && view.providerId) return view.providerId;
  if (typeof match?.providerId === 'string' && match.providerId) return match.providerId;
  const refreshed = await providers.listUser();
  const found = Array.isArray(refreshed)
    ? refreshed.find((entry) => entry?.name === displayName)
    : undefined;
  if (typeof found?.providerId === 'string' && found.providerId) return found.providerId;
  throw projectionFailure('runtime_provider_projection_failed');
}

/**
 * Project the native catalog through the pinned upstream provider API.
 *
 * `application` is the upstream facade: `modelProviders.listUser/create/update`
 * and `models.select`. `probe` optionally runs the official connectivity test
 * for one projected provider and reports `{ ok }`; a provider whose test
 * failed stays visible but is not selected.
 */
export async function applyNativeCatalog({ application, catalog, probe = null }) {
  const projection = projectNativeCatalog(catalog);
  if (!projection.upserts.length) {
    return { providerBinding: [], defaultSelection: null, rejected: projection.rejected };
  }
  const providers = application?.modelProviders;
  const models = application?.models;
  if (!providers || typeof providers.listUser !== 'function' ||
      typeof providers.create !== 'function' || typeof providers.update !== 'function') {
    throw new TypeError('The upstream provider application is required');
  }
  if (!models || typeof models.select !== 'function') {
    throw new TypeError('The upstream model application is required');
  }
  const listed = await providers.listUser();
  const providerBinding = [];
  for (const upsert of projection.upserts) {
    const request = {
      name: upsert.displayName,
      baseUrl: nativeVirtualBaseUrl(upsert.apiFormat),
      apiFormat: upsert.apiFormat,
      models: upsert.models.map((modelId) => ({ modelId })),
    };
    const match = Array.isArray(listed)
      ? listed.find((entry) => entry?.name === upsert.displayName)
      : undefined;
    let view;
    try {
      // The upstream api key is tri-state: omitting it keeps the stored one.
      view = match?.providerId
        ? await providers.update({ providerId: match.providerId, ...request })
        : await providers.create({ ...request, apiKey: PLACEHOLDER_API_KEY });
    } catch (error) {
      throw projectionFailure('runtime_provider_projection_failed', error?.code);
    }
    const forkProviderId = await resolveForkProviderId(providers, view, match, upsert.displayName);
    let connectivity = 'not_run';
    if (typeof probe === 'function') {
      try {
        const outcome = await probe(forkProviderId, upsert);
        connectivity = outcome?.ok === true ? 'ok' : 'failed';
      } catch {
        connectivity = 'failed';
      }
    }
    providerBinding.push({
      forkProviderId,
      nativeProviderId: upsert.providerId,
      apiFormat: upsert.apiFormat,
      models: upsert.models,
      connectivity,
    });
  }
  let defaultSelection = null;
  if (projection.defaultSelection) {
    const target = providerBinding.find(
      (entry) => entry.nativeProviderId === projection.defaultSelection.providerId);
    if (target && target.connectivity !== 'failed') {
      const modelId = projection.defaultSelection.modelId;
      let selected = false;
      try {
        selected = await models.select({ providerId: target.forkProviderId, modelId });
      } catch (error) {
        throw projectionFailure('runtime_provider_projection_failed', error?.code);
      }
      if (selected === false) throw projectionFailure('runtime_provider_projection_failed');
      defaultSelection = {
        forkProviderId: target.forkProviderId,
        nativeProviderId: target.nativeProviderId,
        modelId,
      };
    }
  }
  return { providerBinding, defaultSelection, rejected: projection.rejected };
}
