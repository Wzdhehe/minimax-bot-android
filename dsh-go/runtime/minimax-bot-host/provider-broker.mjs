// SPDX-License-Identifier: AGPL-3.0-only
import { BrokerError } from './model-broker.mjs';

const PUBLIC_CODES = new Set(['broker_unavailable', 'broker_rejected', 'broker_invalid_response',
  'broker_request_invalid', 'broker_stream_failed', 'invalid_provider', 'invalid_endpoint',
  'missing_key', 'provider_rejected', 'provider_request_failed', 'provider_sensitive_response']);

function hasText(event) {
  if (!event || typeof event !== 'object') return false;
  if (typeof event.delta === 'string' && event.delta.length) return true;
  if (typeof event.delta?.text === 'string' && event.delta.text.length) return true;
  if (event.choices?.some?.((choice) => typeof choice?.delta?.content === 'string' && choice.delta.content.length)) return true;
  return event.candidates?.some?.((candidate) => candidate?.content?.parts?.some?.((part) =>
    typeof part?.text === 'string' && part.text.length)) === true;
}

/** Identifier-only probe. All provider resolution, authorization, HTTPS and Key
 * injection happen in the native model broker. Node never receives a Key or a
 * provider credential-bearing object. No mutations or automatic retries. */
export function createProviderBroker({ modelBroker, timeoutMs = 15_000 }) {
  if (!modelBroker || typeof modelBroker.stream !== 'function') throw new TypeError('A native model broker client is required');
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0 || timeoutMs > 60_000) throw new TypeError('Invalid probe timeout');
  return Object.freeze({
    async testProvider(providerId, modelId) {
      if (typeof providerId !== 'string' || !providerId.trim() || providerId.length > 200 ||
          typeof modelId !== 'string' || !modelId.trim() || modelId.length > 256) {
        return { ok: false, code: 'invalid_provider' };
      }
      const controller = new AbortController();
      let accepted = false;
      let timedOut = false;
      const timer = setTimeout(() => { timedOut = true; controller.abort(); }, timeoutMs);
      try {
        await modelBroker.stream({ providerId, modelId, messages: [{ role: 'user', content: 'Reply OK.' }],
          signal: controller.signal,
          onEvent(event) {
            // Role/admission/usage metadata alone does not prove text output.
            if (!accepted && hasText(event)) { accepted = true; controller.abort(); }
          },
        });
        return accepted ? { ok: true } : { ok: false, code: timedOut ? 'request_timeout' : 'invalid_response' };
      } catch (error) {
        if (accepted) return { ok: true };
        if (timedOut) return { ok: false, code: 'request_timeout' };
        const code = error instanceof BrokerError && PUBLIC_CODES.has(error.code) ? error.code : 'request_failed';
        return { ok: false, code };
      } finally {
        clearTimeout(timer);
        controller.abort();
      }
    },
  });
}
