// SPDX-License-Identifier: AGPL-3.0-only
const FORMATS = new Set(['anthropic-messages', 'openai-completions', 'openai-responses', 'google-generative-ai']);

/** Host-only provider probe. resolveProvider must be a trusted native bridge,
 * not a request payload. No key getter or raw provider response is exported.
 * The Android native bridge and runtime resolver are not wired by this module. */
export function createProviderBroker({ resolveProvider, fetchImpl = fetch }) {
  if (typeof resolveProvider !== 'function') throw new TypeError('A native provider resolver is required');
  return Object.freeze({
    async testProvider(providerId, modelId) {
      if (typeof providerId !== 'string' || !providerId || typeof modelId !== 'string' || !modelId) {
        return { ok: false, code: 'invalid_provider' };
      }
      let provider;
      try { provider = await resolveProvider(providerId); }
      catch { return { ok: false, code: 'provider_unavailable' }; }
      if (!provider || !provider.enabled || !FORMATS.has(provider.apiFormat) || !provider.models?.includes(modelId)) {
        return { ok: false, code: 'invalid_provider' };
      }
      if (typeof provider.apiKey !== 'string' || !provider.apiKey) return { ok: false, code: 'missing_key' };
      let base;
      try { base = new URL(provider.baseUrl); } catch { return { ok: false, code: 'invalid_endpoint' }; }
      if (base.protocol !== 'https:' || base.username || base.password || base.search || base.hash) {
        return { ok: false, code: 'invalid_endpoint' };
      }
      const format = provider.apiFormat;
      const headers = { 'content-type': 'application/json' };
      let suffix, body;
      if (format === 'anthropic-messages') {
        suffix = '/messages';
        headers['x-api-key'] = provider.apiKey;
        headers['anthropic-version'] = '2023-06-01';
        body = { model: modelId, max_tokens: 16, messages: [{ role: 'user', content: 'Reply OK.' }] };
      } else if (format === 'google-generative-ai') {
        suffix = `/models/${encodeURIComponent(modelId)}:generateContent`;
        headers['x-goog-api-key'] = provider.apiKey;
        body = { contents: [{ role: 'user', parts: [{ text: 'Reply OK.' }] }], generationConfig: { maxOutputTokens: 16 } };
      } else {
        headers.authorization = `Bearer ${provider.apiKey}`;
        if (format === 'openai-responses') {
          suffix = '/responses';
          body = { model: modelId, input: 'Reply OK.', max_output_tokens: 16 };
        } else {
          suffix = '/chat/completions';
          body = { model: modelId, messages: [{ role: 'user', content: 'Reply OK.' }], max_tokens: 16, stream: false };
        }
      }
      const url = new URL(base);
      url.pathname = base.pathname.replace(/\/$/, '') + suffix;
      try {
        const signal = AbortSignal.timeout(15_000);
        const response = await fetchImpl(url, { method: 'POST', headers, body: JSON.stringify(body), redirect: 'error', signal });
        if (!response.ok) {
          await response.body?.cancel();
          return { ok: false, code: 'provider_rejected', status: response.status };
        }
        const reader = response.body?.getReader();
        if (!reader) return { ok: false, code: 'invalid_response' };
        const chunks = [];
        let size = 0;
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          size += value.byteLength;
          if (size > 64 * 1024) {
            await reader.cancel();
            return { ok: false, code: 'response_too_large' };
          }
          chunks.push(Buffer.from(value));
        }
        const result = JSON.parse(Buffer.concat(chunks).toString('utf8'));
        const content = format === 'anthropic-messages' ? result.content : format === 'google-generative-ai' ? result.candidates : format === 'openai-responses' ? result.output : result.choices;
        return Array.isArray(content) && content.length > 0 ? { ok: true } : { ok: false, code: 'invalid_response' };
      } catch {
        // Transport errors and provider bodies can contain credentials. Never
        // serialize them to a UI/Agent response or a diagnostic log.
        return { ok: false, code: 'request_failed' };
      }
    },
  });
}
