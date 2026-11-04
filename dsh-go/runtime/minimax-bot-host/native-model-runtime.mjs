// SPDX-License-Identifier: AGPL-3.0-only
// Assemble the native model runtime for one guest process.
//
// The pinned fork gateway is not imported here: the assembly is exercised on
// its own, so the projection, the per-turn binding and the session model
// lookup can be verified without the upstream runtime.
import { applyNativeCatalog } from './model-catalog-projection.mjs';
import { createNativeModelBinding } from './native-model-binding.mjs';

/**
 * The model key of one turn, read once before the turn starts.
 *
 * The upstream model list marks the session's effective model as selected, so
 * this is the fork's own view of the model the turn will use. A model switched
 * while a turn runs therefore reaches the next turn, not the running one.
 */
export async function resolveModelKeyFor(application, sessionId) {
  const models = application?.models;
  if (!models || typeof models.list !== 'function') return null;
  const entries = await models.list(sessionId ? { sessionId } : {});
  const selected = Array.isArray(entries) ? entries.find((entry) => entry?.selected) : undefined;
  if (!selected) return null;
  if (typeof selected.modelConfigId === 'string' && selected.modelConfigId) return selected.modelConfigId;
  if (typeof selected.providerId === 'string' && typeof selected.modelId === 'string') {
    return `${selected.providerId}/${selected.modelId}`;
  }
  return null;
}

/** The fork provider id is the key of a model key; the native id resolves the real endpoint. */
function forkProviderMap(providerBinding) {
  return Object.fromEntries(providerBinding.map((entry) => [entry.forkProviderId, {
    nativeProviderId: entry.nativeProviderId,
    apiFormat: entry.apiFormat,
    models: entry.models,
  }]));
}

/**
 * Project the native catalog and bind every turn to the native route of its
 * own model.
 *
 * Without a catalog the guest runs exactly as before: no provider call, no
 * binding and no scoped fetch. The scoped fetch is installed once on the given
 * target; outside a binding every model call then fails closed.
 */
export async function createNativeModelRuntime({
  application, catalog = null, probe = null, nativeBinding = null, fetchTarget = null,
}) {
  if (!catalog) return { providerBinding: [], binding: null, resolveModelKey: null };
  const projected = await applyNativeCatalog({ application, catalog, probe });
  const providerBinding = projected.providerBinding;
  if (!providerBinding.length || !nativeBinding) {
    return { providerBinding, binding: null, resolveModelKey: null };
  }
  const binding = createNativeModelBinding({
    byForkProviderId: forkProviderMap(providerBinding),
    binding: nativeBinding,
  });
  if (fetchTarget) binding.install(fetchTarget);
  return {
    providerBinding,
    binding,
    resolveModelKey: (sessionId) => resolveModelKeyFor(application, sessionId),
  };
}
