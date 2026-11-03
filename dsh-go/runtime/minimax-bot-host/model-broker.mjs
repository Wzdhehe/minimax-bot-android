// SPDX-License-Identifier: AGPL-3.0-only
//
// Host side of the provider broker.
//
// The provider API key stays in the Android native process (AndroidKeyStore).
// The native broker is the only component that decrypts the key; it injects
// the key into the upstream provider request and streams the answer back.
//
// This module never sees the key. The host holds a session-scoped loopback
// token and a loopback endpoint, and it forwards the runtime's model stream
// to that endpoint:
//   runtime/plugin -> host proxy (loopback, runtime token)
//                  -> native broker (loopback, host token)
//                  -> provider API (key injected natively, never leaves)
//
// Cancellation travels the whole way: when the runtime disconnects, the host
// aborts the broker request, and the broker aborts its upstream connection.

import { timingSafeEqual } from 'node:crypto';
import { createServer } from 'node:http';
import { once } from 'node:events';

const LOOPBACK_HOST = /^127\.0\.0\.1$/;
const MAX_BODY_BYTES = 1024 * 1024;
const MAX_FRAME_CHARS = 256 * 1024;
const PUBLIC_FAILURE_CODES = new Set(['broker_request_invalid', 'invalid_provider', 'missing_key', 'invalid_endpoint',
  'provider_rejected', 'provider_request_failed', 'broker_invalid_response', 'provider_sensitive_response',
  'broker_cancelled', 'broker_unavailable', 'broker_stream_failed', 'broker_rejected']);
export const MODEL_STREAM_PATH = '/v1/model/stream';

/** Stable error codes. A provider body, a broker body or a stack trace can
 * carry a credential, so no upstream text is ever copied into an error. */
export class BrokerError extends Error {
  constructor(code) {
    super(code);
    this.name = 'BrokerError';
    this.code = code;
  }
}

function constantTimeEquals(expected, supplied) {
  if (typeof supplied !== 'string') return false;
  const a = Buffer.from(expected);
  const b = Buffer.from(supplied);
  return a.length === b.length && timingSafeEqual(a, b);
}

function parseSseFrames(buffer) {
  const events = [];
  let index = buffer.indexOf('\n\n');
  while (index !== -1) {
    const frame = buffer.slice(0, index);
    if (frame.length > MAX_FRAME_CHARS) throw new BrokerError('broker_invalid_response');
    buffer = buffer.slice(index + 2);
    for (const line of frame.split('\n')) {
      if (!line.startsWith('data:')) continue;
      const data = line.slice(5).trim();
      if (!data) continue;
      if (data === '[DONE]') { events.push({ done: true }); continue; }
      try { events.push({ payload: JSON.parse(data) }); }
      catch { events.push({ invalid: true }); }
    }
    index = buffer.indexOf('\n\n');
  }
  return { events, rest: buffer };
}

function modelPayload(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw new BrokerError('broker_request_invalid');
  const { providerId, modelId, messages, request } = input;
  if (typeof providerId !== 'string' || !providerId.trim() || providerId.length > 200 ||
      typeof modelId !== 'string' || !modelId.trim() || modelId.length > 256) {
    throw new BrokerError('broker_request_invalid');
  }
  const text = Array.isArray(messages);
  const payload = request && typeof request === 'object' && !Array.isArray(request);
  if (Boolean(text) === Boolean(payload) || (messages !== undefined && request !== undefined)) {
    throw new BrokerError('broker_request_invalid');
  }
  return text ? { providerId, modelId, messages } : { providerId, modelId, request };
}

/**
 * Client for the native broker. Holds only a loopback endpoint and a session
 * token; the provider key is not part of this interface.
 */
export function createModelBrokerClient({ baseUrl, token, fetchImpl = fetch }) {
  if (typeof baseUrl !== 'string' || !baseUrl) throw new TypeError('A broker endpoint is required');
  const target = new URL(baseUrl);
  if (target.protocol !== 'http:' || !LOOPBACK_HOST.test(target.hostname)) {
    throw new TypeError('The broker endpoint must be a loopback address');
  }
  if (typeof token !== 'string' || !token) throw new TypeError('A broker session token is required');
  const endpoint = new URL(MODEL_STREAM_PATH, target);

  return Object.freeze({
    endpoint: endpoint.toString(),
    async stream(input) {
      const payload = modelPayload(input);
      const { signal, onEvent } = input;
      let response;
      try {
        response = await fetchImpl(endpoint, {
          method: 'POST',
          headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
          body: JSON.stringify(payload),
          signal,
          redirect: 'error',
        });
      } catch (error) {
        if (signal?.aborted) throw new BrokerError('broker_cancelled');
        throw new BrokerError('broker_unavailable');
      }
      return consumeModelBrokerResponse(response, { signal, onEvent });
    },
  });
}


/** Shared response consumer for fetch and preconnected native FD transport.
 * Both enforce the same framing, backpressure and stable error contract. */
export async function consumeModelBrokerResponse(response, { signal, onEvent } = {}) {
  if (!response.ok) {
    await response.body?.cancel().catch(() => {});
    throw new BrokerError('broker_rejected');
  }
  if (!response.body) throw new BrokerError('broker_invalid_response');
  if (response.headers?.get('content-type')?.split(';')[0].trim().toLowerCase() !== 'text/event-stream') {
    await response.body.cancel().catch(() => {});
    throw new BrokerError('broker_invalid_response');
  }
  const reader = response.body.getReader();
  const framed = response.headers?.get('x-mbot-broker-protocol') === '1';
  const decoder = new TextDecoder();
  let buffer = '';
  const abortReader = () => { reader.cancel().catch(() => {}); };
  signal?.addEventListener('abort', abortReader, { once: true });
  try {
    for (;;) {
      if (signal?.aborted) throw new BrokerError('broker_cancelled');
      let chunk;
      try { chunk = await reader.read(); }
      catch {
        if (signal?.aborted) throw new BrokerError('broker_cancelled');
        throw new BrokerError('broker_stream_failed');
      }
      if (signal?.aborted) throw new BrokerError('broker_cancelled');
      if (chunk.done) break;
      buffer += decoder.decode(chunk.value, { stream: true });
      const { events, rest } = parseSseFrames(buffer);
      buffer = rest;
      if (buffer.length > MAX_FRAME_CHARS) throw new BrokerError('broker_invalid_response');
      for (const event of events) {
        if (signal?.aborted) throw new BrokerError('broker_cancelled');
        if (event.done) return;
        if (event.invalid) throw new BrokerError('broker_invalid_response');
        const failure = event.payload && typeof event.payload.error === 'string' ? event.payload.error : null;
        if (failure) {
          // The host only ever reports a stable code here. Anything else is
          // dropped rather than forwarded, because a provider body could
          // carry a credential.
          throw new BrokerError(PUBLIC_FAILURE_CODES.has(failure) ? failure : 'broker_unavailable');
        }
        if (typeof onEvent === 'function') await deliverBrokerEvent(onEvent, event.payload, signal);
      }
    }
    if (framed) throw new BrokerError('broker_stream_failed');
  } finally {
    signal?.removeEventListener('abort', abortReader);
    // Also close on DONE, invalid JSON, callback failure or cancellation.
    // Otherwise a caller can leave an upstream body/socket pinned forever.
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}

/** A suspended consumer must not pin a cancelled model stream indefinitely.
 * This stops waiting, not arbitrary side effects inside a caller's callback. */
async function deliverBrokerEvent(callback, payload, signal) {
  if (!signal) return callback(payload);
  if (signal.aborted) throw new BrokerError('broker_cancelled');
  let abort;
  const cancelled = new Promise((_, reject) => {
    abort = () => reject(new BrokerError('broker_cancelled'));
    signal.addEventListener('abort', abort, { once: true });
  });
  try {
    await Promise.race([Promise.resolve().then(() => {
      if (signal.aborted) throw new BrokerError('broker_cancelled');
      return callback(payload);
    }), cancelled]);
  } finally { signal.removeEventListener('abort', abort); }
}

/**
 * The loopback endpoint the runtime and plugins use. It authenticates the
 * runtime session token, forwards the request to the native broker and
 * streams the answer back as SSE. A runtime disconnect aborts the upstream
 * broker request, which aborts the provider connection.
 */
export function startModelProxy({ broker, host = '127.0.0.1', port = 0, token, logger, timeoutMs = 10 * 60_000 }) {
  if (!broker || typeof broker.stream !== 'function') throw new TypeError('A broker client is required');
  if (typeof token !== 'string' || !token) throw new TypeError('A runtime session token is required');
  if (!LOOPBACK_HOST.test(host)) throw new TypeError('The proxy must listen on loopback');
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0 || timeoutMs > 10 * 60_000) throw new TypeError('Invalid model deadline');

  const log = (code) => { try { if (typeof logger === 'function') logger(code); } catch {} };
  const active = new Set();
  const pending = new Set();
  let stopping = false;
  let disposal;

  const server = createServer((req, res) => {
    if (stopping) { res.destroy(); return; }
    const url = new URL(req.url ?? '/', 'http://127.0.0.1');
    if (url.pathname !== MODEL_STREAM_PATH || req.method !== 'POST') {
      res.writeHead(404, { 'content-type': 'text/plain' });
      res.end('not_found');
      return;
    }
    // A browser origin or a foreign Host header means the caller is not the
    // runtime. The proxy only serves loopback peers.
    if (req.headers.origin !== undefined || req.headers.host !== `${host}:${server.address()?.port}`) {
      res.writeHead(403, { 'content-type': 'text/plain' });
      res.end('forbidden');
      return;
    }
    const supplied = req.headers.authorization;
    if (typeof supplied !== 'string' || !constantTimeEquals(`Bearer ${token}`, supplied)) {
      res.writeHead(401, { 'content-type': 'text/plain' });
      res.end('unauthorized');
      return;
    }
    let size = 0;
    const chunks = [];
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) { req.destroy(); return; }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (stopping) { res.destroy(); return; }
      if (size > MAX_BODY_BYTES) return;
      let request;
      try { request = JSON.parse(Buffer.concat(chunks).toString('utf8')); }
      catch { res.writeHead(400, { 'content-type': 'text/plain' }); res.end('bad_request'); return; }
      let payload;
      try {
        payload = modelPayload(request);
        const keys = Object.keys(request).sort().join(',');
        if (keys !== 'messages,modelId,providerId' && keys !== 'modelId,providerId,request') throw new Error();
      } catch { res.writeHead(400, { 'content-type': 'text/plain' }); res.end('broker_request_invalid'); return; }
      const controller = new AbortController();
      active.add(controller);
      const deadline = setTimeout(() => { controller.abort(); res.destroy(); }, timeoutMs);
      let finished = false;
      const abort = () => { if (!finished) controller.abort(); };
      req.on('aborted', abort);
      res.on('close', abort);
      res.writeHead(200, {
        'content-type': 'text/event-stream',
        'cache-control': 'no-store',
        connection: 'keep-alive',
        'x-mbot-broker-protocol': '1',
      });
      const task = broker.stream({
        ...payload,
        signal: controller.signal,
        onEvent: async (payload) => {
          if (finished || controller.signal.aborted) return;
          if (!res.write(`data: ${JSON.stringify(payload)}\n\n`)) {
            await once(res, 'drain', { signal: controller.signal });
          }
        },
      }).then(() => {
        finished = true;
        res.end('data: [DONE]\n\n');
      }).catch((error) => {
        finished = true;
        const code = error instanceof BrokerError && PUBLIC_FAILURE_CODES.has(error.code) ? error.code : 'broker_unavailable';
        log(code);
        if (!res.headersSent) res.writeHead(502, { 'content-type': 'text/plain' });
        if (!controller.signal.aborted) {
          res.write(`data: ${JSON.stringify({ error: code })}\n\n`);
        }
        res.end();
      }).finally(() => { clearTimeout(deadline); active.delete(controller); });
      pending.add(task);
      task.finally(() => pending.delete(task)).catch(() => {});
    });
  });

  return new Promise((resolvePromise, rejectPromise) => {
    server.maxConnections = 24;
    server.headersTimeout = 10_000;
    server.requestTimeout = 10_000;
    server.on('error', rejectPromise);
    server.listen(port, host, () => {
      const address = server.address();
      resolvePromise(Object.freeze({
        host,
        port: address.port,
        baseUrl: `http://${host}:${address.port}`,
        token,
        dispose: () => disposal ??= (async () => {
          stopping = true;
          const closing = new Promise((done) => server.close(() => done()));
          for (const controller of active) controller.abort();
          server.closeAllConnections?.();
          await closing;
          await Promise.allSettled([...pending]);
        })(),
      }));
    });
  });
}
