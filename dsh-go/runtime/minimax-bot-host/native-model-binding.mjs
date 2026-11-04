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

// Every binding installed on the same process-wide fetch must use the same
// AsyncLocalStorage. Otherwise the last installer sees only its own turns.
const sharedScope = createModelFetchScope();
const installations = new WeakMap();

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
export function createNativeModelBinding({ byForkProviderId, routeFactory, scope = sharedScope, binding = null }) {
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
  let activeTurns = 0;

  // Keep the dispatcher installed until this binding's active operations have
  // settled. A late fetch from a closed turn must fail instead of reaching
  // the original network fetch while its operation is still running.
  function releaseInstallation() {
    if (installed || activeTurns || !installedTarget) return;
    const target = installedTarget;
    installedTarget = null;
    const record = installations.get(target);
    if (!record || --record.users > 0) return;
    installations.delete(target);
    // Never overwrite a later owner's change to the property.
    if (target.fetch !== scope.fetch) return;
    if (record.originalDescriptor) Object.defineProperty(target, 'fetch', record.originalDescriptor);
    else delete target.fetch;
  }

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
      if (installed) {
        if (target !== installedTarget || target.fetch !== scope.fetch) throw new NativeBindingError('binding_install_conflict');
        return;
      }
      if (installedTarget) throw new NativeBindingError('binding_draining');
      let record = installations.get(target);
      if (record) {
        if (record.scope !== scope || target.fetch !== scope.fetch) throw new NativeBindingError('binding_install_conflict');
      } else {
        const originalDescriptor = Object.getOwnPropertyDescriptor(target, 'fetch');
        target.fetch = scope.fetch;
        if (target.fetch !== scope.fetch) throw new NativeBindingError('binding_install_conflict');
        record = { scope, originalDescriptor, users: 0 };
        installations.set(target, record);
      }
      record.users++;
      installed = true;
      installedTarget = target;
    },
    routeFor,
    /** Run one turn inside the route of its own model. The key is resolved once, before the turn. */
    runForModelKey(modelKey, operation) {
      if (!installed || installedTarget.fetch !== scope.fetch) throw new NativeBindingError('binding_not_installed');
      if (typeof operation !== 'function') throw new TypeError('An operation is required');
      const route = routeFor(modelKey);
      activeTurns++;
      const finish = () => { activeTurns--; releaseInstallation(); };
      let result;
      try {
        result = scope.run((input, init) => {
          if (!installed || installedTarget?.fetch !== scope.fetch) {
            return Promise.reject(new NativeBindingError('native_binding_unavailable'));
          }
          return route.fetch(input, init);
        }, operation);
      } catch (error) { finish(); throw error; }
      try {
        if (result && typeof result.then === 'function') return Promise.resolve(result).finally(finish);
      } catch (error) { finish(); throw error; }
      finish();
      return result;
    },
    close() {
      if (!installed) return;
      installed = false;
      releaseInstallation();
    },
  });
}
