// SPDX-License-Identifier: AGPL-3.0-only
import { randomUUID } from 'node:crypto';
import { BrokerError } from './model-broker.mjs';
import { acquireModelFd, streamNativeModelFd } from './model-fd-client.mjs';

/** Text connectivity probes use exactly the product's native model FD grant.
 * No endpoint, headers or credentials cross this interface. Native validates
 * identifiers, prepares the provider body and injects its own authentication.
 */
export function createNativeProbeClient(binding) {
  if (typeof binding?.open !== 'function' || typeof binding?.cancel !== 'function') {
    throw new TypeError('A native model capability is required');
  }
  return Object.freeze({
    async stream({ providerId, modelId, messages, signal, onEvent }) {
      const id = randomUUID();
      const controller = new AbortController();
      const abort = () => {
        controller.abort();
        try { binding.cancel(id); } catch {}
      };
      signal?.addEventListener('abort', abort, { once: true });
      const timeout = setTimeout(abort, 15_000);
      try {
        if (signal?.aborted) abort();
        const fd = await acquireModelFd(async (payload) => {
          try { return await binding.open(id, JSON.stringify(payload)); }
          catch { throw new BrokerError(controller.signal.aborted ? 'broker_cancelled' : 'broker_unavailable'); }
        }, { providerId, modelId, messages }, controller.signal);
        await streamNativeModelFd({ fd, signal: controller.signal, onEvent, timeoutMs: 15_000 });
      } finally {
        clearTimeout(timeout);
        signal?.removeEventListener('abort', abort);
      }
    },
  });
}
