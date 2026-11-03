// SPDX-License-Identifier: AGPL-3.0-only
import { closeSync } from 'node:fs';
import { BrokerError } from './model-broker.mjs';
import { streamNativeModelFd } from './model-fd-client.mjs';

const FORMATS = new Set(['openai-completions', 'openai-responses', 'anthropic-messages', 'google-generative-ai']);
const MAX_BODY = 1024 * 1024;
const QUEUE_BYTES = 64 * 1024;
const safeClose = (fd) => { try { closeSync(fd); } catch {} };

/** A model-bound SDK fetch implementation. No fetch/network fallback, Key,
 * native URL or token. The trusted bridge must recheck model authorization
 * and return one owned, preauthenticated Unix model FD for each request.
 * Google pi-ai currently does not call its options.fetch; this alone cannot
 * route that provider. Caller HTTP headers are intentionally never forwarded.
 */
export function createNativeModelFetch({ providerId, modelId, apiFormat, requestUrl, openChannel, timeoutMs = 10 * 60_000 }) {
  let endpoint;
  try { endpoint = new URL(requestUrl); } catch {}
  if (!FORMATS.has(apiFormat) || typeof openChannel !== 'function' ||
      typeof providerId !== 'string' || !providerId.trim() || providerId.length > 200 ||
      typeof modelId !== 'string' || !modelId.trim() || modelId.length > 256 ||
      !endpoint || endpoint.protocol !== 'https:' || endpoint.username || endpoint.password || endpoint.hash ||
      !Number.isFinite(timeoutMs) || timeoutMs <= 0 || timeoutMs > 10 * 60_000) {
    throw new BrokerError('broker_request_invalid');
  }
  const boundUrl = endpoint.href;
  return async function nativeModelFetch(input, init) {
    const cancellation = new AbortController();
    let request;
    let expired = false;
    let finished = false;
    const abort = () => cancellation.abort();
    const timer = setTimeout(() => { expired = true; abort(); }, timeoutMs);
    const finish = () => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      request?.signal.removeEventListener('abort', abort);
    };
    const code = () => expired ? 'broker_stream_failed' : 'broker_cancelled';
    let fd;
    try {
      request = new Request(input, init);
      request.signal.addEventListener('abort', abort, { once: true });
      if (request.signal.aborted) abort();
      if (cancellation.signal.aborted) throw new BrokerError(code());
      if (request.url !== boundUrl || request.method !== 'POST') {
        void request.body?.cancel().catch(() => {});
        throw new BrokerError('broker_request_invalid');
      }
      const payload = await readBody(request, cancellation.signal);
      // The body cannot override the binding even before the native recheck.
      if ((payload.model !== undefined && payload.model !== modelId) ||
          (payload.stream !== undefined && payload.stream !== true)) throw new BrokerError('broker_request_invalid');
      fd = await ownedChannel(openChannel, { providerId, modelId, request: payload }, cancellation.signal);
      if (cancellation.signal.aborted) throw new BrokerError(code());
      const ownedFd = fd;
      fd = undefined;
      let resume;
      const body = new ReadableStream({
        start(controller) {
          // Start reading promptly, but await queue space at every event.
          // At most one bounded native event can exceed the byte queue budget.
          void streamNativeModelFd({ fd: ownedFd, signal: cancellation.signal, timeoutMs,
            async onEvent(event) {
              if (cancellation.signal.aborted) throw new BrokerError(code());
              let frame = '';
              if (apiFormat === 'anthropic-messages' || apiFormat === 'openai-responses') {
                if (typeof event?.type !== 'string' || !/^[a-zA-Z0-9_.-]{1,100}$/.test(event.type)) {
                  throw new BrokerError('broker_invalid_response');
                }
                frame = `event: ${event.type}\n`;
              }
              controller.enqueue(new TextEncoder().encode(`${frame}data: ${JSON.stringify(event)}\n\n`));
              if (controller.desiredSize <= 0) await new Promise((resolve) => { resume = resolve; });
            },
          }).then(() => {
            if (!cancellation.signal.aborted) {
              // Anthropic/Responses/Google expect JSON events, not this sentinel.
              if (apiFormat === 'openai-completions') controller.enqueue(new TextEncoder().encode('data: [DONE]\n\n'));
              controller.close();
            }
          }, (error) => {
            try { controller.error(new BrokerError(cancellation.signal.aborted ? code() :
              error instanceof BrokerError ? error.code : 'broker_stream_failed')); } catch {}
          }).finally(finish);
        },
        pull() { const next = resume; resume = undefined; next?.(); },
        cancel() { abort(); const next = resume; resume = undefined; next?.(); },
      }, new ByteLengthQueuingStrategy({ highWaterMark: QUEUE_BYTES }));
      return new Response(body, { status: 200, headers: { 'content-type': 'text/event-stream' } });
    } catch (error) {
      if (fd !== undefined) safeClose(fd);
      finish();
      if (request?.signal.aborted) throw new BrokerError('broker_cancelled');
      // A deterministic 400 prevents provider SDKs retrying a failed bridge
      // operation whose upstream outcome is unknown. Never return diagnostics.
      const failure = cancellation.signal.aborted ? code() :
        error instanceof BrokerError && error.code === 'broker_request_invalid' ? error.code : 'broker_unavailable';
      return Response.json({ type: 'error', error: { type: 'invalid_request_error', message: failure, code: failure } }, { status: 400 });
    }
  };
}

async function readBody(request, signal) {
  if (!request.body) throw new BrokerError('broker_request_invalid');
  const reader = request.body.getReader();
  const abort = () => { reader.cancel().catch(() => {}); };
  signal.addEventListener('abort', abort, { once: true });
  const chunks = [];
  let size = 0;
  try {
    for (;;) {
      if (signal.aborted) throw new BrokerError('broker_cancelled');
      const { value, done } = await reader.read();
      if (signal.aborted) throw new BrokerError('broker_cancelled');
      if (done) break;
      size += value.byteLength;
      if (size > MAX_BODY) throw new BrokerError('broker_request_invalid');
      chunks.push(Buffer.from(value));
    }
    const body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    if (!body || typeof body !== 'object' || Array.isArray(body)) throw new BrokerError('broker_request_invalid');
    return body;
  } catch {
    throw new BrokerError('broker_request_invalid');
  } finally {
    signal.removeEventListener('abort', abort);
    // A caller-supplied source may return a never-settling cancel promise.
    // Revoke reads without making our deadline depend on that promise.
    void reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}

async function ownedChannel(openChannel, payload, signal) {
  let abort;
  let received;
  let transferred = false;
  let abandoned = false;
  const cancelled = new Promise((_, reject) => {
    abort = () => reject(new BrokerError('broker_cancelled'));
    signal.addEventListener('abort', abort, { once: true });
    if (signal.aborted) abort();
  });
  // A bridge may finish after cancellation. Its late FD still belongs to us.
  const opening = Promise.resolve().then(() => {
    if (signal.aborted) throw new BrokerError('broker_cancelled');
    return openChannel(payload, { signal });
  }).then((fd) => {
    if (!Number.isSafeInteger(fd) || fd < 3) throw new BrokerError('broker_request_invalid');
    if (abandoned || signal.aborted) { safeClose(fd); throw new BrokerError('broker_cancelled'); }
    received = fd;
    return fd;
  });
  try {
    const fd = await Promise.race([opening, cancelled]);
    transferred = true;
    return fd;
  } finally {
    abandoned = true;
    signal.removeEventListener('abort', abort);
    if (!transferred && received !== undefined) safeClose(received);
  }
}
