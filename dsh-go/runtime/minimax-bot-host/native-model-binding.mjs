// SPDX-License-Identifier: AGPL-3.0-only
// Bind one model turn to the native model route.
//
// The route and the fetch scope already exist: `createNativeModelRoute`
// addresses a virtual origin and `createModelFetchScope` dispatches inside
// one async binding. This module resolves the turn's model key against the
// projected provider map and keeps the binding lifetime separate from the
// runtime that owns it.
import { createNativeModelRoute } from './native-model-route.mjs';
import { createModelFetchScope } from './model-fetch-scope.mjs';

export class NativeBindingError extends Error {
  constructor(code) {
    super(code);
    this.code = code;
  }
}

/**
 * `byForkProviderId` maps the fork-side provider id (the key of the model
 * key, e.g. `custom_provider:minimax`) to the native provider it was
 * projected from.
 */
export function createNativeModelBinding({ byForkProviderId, routeFactory, scope = createModelFetchScope(), binding = null }) {
  if (!byForkProviderId || typeof byForkProviderId !== 'object') {
    throw new TypeError('A fork provider map is required');
  }
  if (routeFactory === undefined && !binding) {
    throw new TypeError('A native model broker binding is required');
  }
  const factory = routeFactory ?? ((input) => createNativeModelRoute({ ...input, binding }));
  if (typeof factory !== 'function') throw new TypeError('A route factory is required');
  if (!scope || typeof scope.fetch !== 'function' || typeof scope.run !== 'function') {
    throw new TypeError('A fetch scope is required');
  }
  let installed = false;
  let installedTarget = null;
  let previousFetch;

  function parseModelKey(modelKey) {
    if (typeof modelKey !== 'string') throw new NativeBindingError('invalid_model_key');
    const slash = modelKey.indexOf('/');
    if (slash <= 0 || slash === modelKey.length - 1) throw new NativeBindingError('invalid_model_key');
    return { providerId: modelKey.slice(0, slash), modelId: modelKey.slice(slash + 1) };
  }

  function routeFor(modelKey) {
    const { providerId, modelId } = parseModelKey(modelKey);
    const entry = Object.prototype.hasOwnProperty.call(byForkProviderId, providerId)
      ? byForkProviderId[providerId]
      : null;
    if (!entry) throw new NativeBindingError('model_not_available');
    if (!Array.isArray(entry.models) || !entry.models.includes(modelId)) {
      throw new NativeBindingError('model_not_available');
    }
    return factory({ providerId: entry.nativeProviderId, modelId, apiFormat: entry.apiFormat });
  }

  return Object.freeze({
    /** Install the scoped fetch once. The target is the object the SDK reads it from. */
    install(target) {
      if (!target || typeof target !== 'object') throw new TypeError('An install target is required');
      if (installed) return;
      previousFetch = Object.prototype.hasOwnProperty.call(target, 'fetch') ? target.fetch : undefined;
      target.fetch = scope.fetch;
      installed = true;
      installedTarget = target;
    },
    routeFor,
    /** Run one turn inside the route of its own model. The key is resolved once, before the turn. */
    runForModelKey(modelKey, operation) {
      if (!installed) throw new NativeBindingError('binding_not_installed');
      if (typeof operation !== 'function') throw new TypeError('An operation is required');
      const route = routeFor(modelKey);
      return scope.run(route.fetch, operation);
    },
    close() {
      if (!installed) return;
      installed = false;
      if (installedTarget) {
        if (previousFetch === undefined) delete installedTarget.fetch;
        else installedTarget.fetch = previousFetch;
      }
      installedTarget = null;
      previousFetch = undefined;
    },
  });
}
