// SPDX-License-Identifier: AGPL-3.0-only
import { randomUUID } from 'node:crypto';
import { BrokerError } from './model-broker.mjs';
import { createNativeModelFetch } from './model-sdk-fetch.mjs';

/** The virtual HTTPS origin every native route is addressed through. */
const VIRTUAL_ORIGIN = 'https://models.mbot.invalid';

/** The base URL a native route exposes for one api format. The upstream
 * provider configuration must address exactly this origin so the guest never
 * learns the real endpoint. */
export function nativeVirtualBaseUrl(apiFormat) {
  if (apiFormat === 'anthropic-messages') return VIRTUAL_ORIGIN;
  if (apiFormat === 'google-generative-ai') return `${VIRTUAL_ORIGIN}/v1beta`;
  return `${VIRTUAL_ORIGIN}/v1`;
}

/** SDK routing uses a virtual HTTPS origin, never the configured upstream URL.
 * Native resolves the real endpoint/auth from IDs and rechecks authorization.
 * The nonsecret placeholder merely satisfies SDK constructors. */
export function createNativeModelRoute({ providerId, modelId, apiFormat, binding, timeoutMs, onFailure }) {
  if (!binding || typeof binding.open !== 'function' || typeof binding.cancel !== 'function' ||
      typeof modelId !== 'string' || !modelId.trim() || modelId.length > 256) throw new BrokerError('broker_request_invalid');
  const baseUrl = nativeVirtualBaseUrl(apiFormat);
  const suffix = {
    'openai-completions': '/chat/completions', 'openai-responses': '/responses',
    'anthropic-messages': '/v1/messages',
    'google-generative-ai': `/models/${encodeURIComponent(modelId)}:streamGenerateContent?alt=sse`,
  }[apiFormat];
  if (!suffix) throw new BrokerError('broker_request_invalid');
  const requestUrl = baseUrl + suffix;
  const fetchImpl = createNativeModelFetch({ providerId, modelId, apiFormat, requestUrl, timeoutMs, onFailure,
    async openChannel(payload, { signal }) {
      if (signal.aborted) throw new BrokerError('broker_cancelled');
      const id = randomUUID();
      const cancel = () => { try { binding.cancel(id); } catch {} };
      signal.addEventListener('abort', cancel, { once: true });
      try {
        // On success always return the owned FD, even after abort: the outer
        // FD adapter closes late results. Throwing here would lose that FD.
        return await binding.open(id, JSON.stringify(payload));
      } catch {
        throw new BrokerError(signal.aborted ? 'broker_cancelled' : 'broker_unavailable');
      } finally { signal.removeEventListener('abort', cancel); }
    },
  });
  return Object.freeze({ providerId, modelId, apiFormat, baseUrl, requestUrl,
    apiKey: 'native-broker-placeholder', fetch: fetchImpl });
}
