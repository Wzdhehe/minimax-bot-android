// SPDX-License-Identifier: AGPL-3.0-only
import { AsyncLocalStorage } from 'node:async_hooks';
import { BrokerError } from './model-broker.mjs';

/** Explicit process-wide SDK fetch dispatcher for an isolated runtime.
 * Construction does not mutate globalThis. A trusted launcher must install
 * `fetch` once, then invoke each provider inside `run(boundFetch, operation)`.
 * Async resources inherit the binding; concurrent models never share a slot.
 * Outside a binding all fetches fail closed, with no original/network fallback.
 * This routes SDKs (notably pinned Google) lacking a per-client fetch hook;
 * it is not an authorization or sandbox boundary. Native rechecks IDs.
 */
export function createModelFetchScope() {
  const scope = new AsyncLocalStorage();
  return Object.freeze({
    fetch(input, init) {
      const fetchImpl = scope.getStore();
      if (!fetchImpl) return Promise.resolve(Response.json({ type: 'error', error: {
        type: 'invalid_request_error', message: 'broker_request_invalid', code: 'broker_request_invalid',
      } }, { status: 400 }));
      return fetchImpl(input, init);
    },
    run(fetchImpl, operation) {
      if (typeof fetchImpl !== 'function' || typeof operation !== 'function') throw new BrokerError('broker_request_invalid');
      return scope.run(fetchImpl, operation);
    },
  });
}
