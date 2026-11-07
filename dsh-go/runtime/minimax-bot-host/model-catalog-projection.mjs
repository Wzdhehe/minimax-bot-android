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
import { findCatalogViolation, validatedModelSettings } from './runtime-catalog-policy.mjs';

/** The four BYOK wire protocols the pinned upstream provider API accepts. */
export const PROJECTED_API_FORMATS = Object.freeze([
  'openai-completions',
  'openai-responses',
  'anthropic-messages',
  'google-generative-ai',
]);

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
  const violation = findCatalogViolation(catalog);
  if (violation === 'credential') throw new TypeError('native_catalog_contains_credential');
  if (violation === 'endpoint') throw new TypeError('native_catalog_forbidden_field');
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
    const settings = validatedModelSettings(provider);
    const disabled = new Set(settings?.filter((row) => row.enabled === false).map((row) => row.modelId));
    const models = [...new Set((Array.isArray(provider.models) ? provider.models : [])
      .filter((model) => typeof model === 'string' && MODEL_ID.test(model) && !disabled.has(model)))];
    if (!models.length) continue;
    const displayName = displayNameFor(providerId);
    if (claimedNames.has(displayName)) {
      rejected.push({ providerId, reason: 'duplicate_display_name' });
      continue;
    }
    claimedNames.add(displayName);
    upserts.push({ providerId, displayName, apiFormat: provider.apiFormat, models,
      ...(settings ? { modelSettings: settings } : {}) });
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
  const active = new Set(projection.upserts.map((row) => row.providerId));
  const disabledNames = new Set((Array.isArray(catalog?.providers) ? catalog.providers : [])
    .filter((row) => typeof row?.providerId === 'string' && PROVIDER_ID.test(row.providerId) &&
      !active.has(row.providerId) && !projection.rejected.some((entry) => entry.providerId === row.providerId))
    .map((row) => displayNameFor(row.providerId)));
  if (!projection.upserts.length && !disabledNames.size) {
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
  // A boot with all models switched off must retire the previous provider's
  // visible catalog too, rather than just dropping its native route.
  for (const row of Array.isArray(listed) ? listed : []) {
    if (disabledNames.has(row?.name) && typeof row?.providerId === 'string') {
      try { await providers.update({ providerId: row.providerId, enabled: false }); }
      catch (error) { throw projectionFailure('runtime_provider_projection_failed', error?.code); }
    }
  }
  const providerBinding = [];
  for (const upsert of projection.upserts) {
    const request = {
      name: upsert.displayName,
      baseUrl: nativeVirtualBaseUrl(upsert.apiFormat),
      apiFormat: upsert.apiFormat,
      models: upsert.modelSettings ?? upsert.models.map((modelId) => ({ modelId })),
    };
    const match = Array.isArray(listed)
      ? listed.find((entry) => entry?.name === upsert.displayName)
      : undefined;
    let view;
    try {
      // mcode's update merges omitted limits into the old model. Native is
      // authoritative at boot: recreate our own projected provider through
      // official APIs so clearing a limit/capability really restores defaults.
      // Its deterministic ASCII name preserves the model's provider key.
      const replace = match?.providerId && upsert.modelSettings;
      if (replace) {
        if (typeof providers.delete !== 'function') throw projectionFailure('runtime_provider_projection_failed');
        await providers.delete({ providerId: match.providerId });
      }
      // The upstream api key is tri-state: omitting it keeps the stored one.
      view = match?.providerId && !replace
        ? await providers.update({ providerId: match.providerId, ...request, enabled: true })
        : await providers.create({ ...request, apiKey: PLACEHOLDER_API_KEY });
      if (replace && view?.providerId !== match.providerId) throw projectionFailure('runtime_provider_projection_failed');
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
