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

const LOOPBACK_HOST = /^127\.0\.0\.1$/;
const MAX_BODY_BYTES = 1024 * 1024;
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
    async stream({ providerId, modelId, messages, signal, onEvent }) {
      if (typeof providerId !== 'string' || !providerId ||
          typeof modelId !== 'string' || !modelId ||
          !Array.isArray(messages)) {
        throw new BrokerError('broker_request_invalid');
      }
      let response;
      try {
        response = await fetchImpl(endpoint, {
          method: 'POST',
          headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
          body: JSON.stringify({ providerId, modelId, messages }),
          signal,
          redirect: 'error',
        });
      } catch (error) {
        if (signal?.aborted) throw new BrokerError('broker_cancelled');
        throw new BrokerError('broker_unavailable');
      }
      if (!response.ok) {
        await response.body?.cancel().catch(() => {});
        throw new BrokerError('broker_rejected');
      }
      if (!response.body) throw new BrokerError('broker_invalid_response');
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      for (;;) {
        let chunk;
        try { chunk = await reader.read(); }
        catch {
          if (signal?.aborted) throw new BrokerError('broker_cancelled');
          throw new BrokerError('broker_stream_failed');
        }
        if (chunk.done) break;
        buffer += decoder.decode(chunk.value, { stream: true });
        const { events, rest } = parseSseFrames(buffer);
        buffer = rest;
        for (const event of events) {
          if (event.done) return;
          if (event.invalid) throw new BrokerError('broker_invalid_response');
          const failure = event.payload && typeof event.payload.error === 'string' ? event.payload.error : null;
          if (failure) {
            // The host only ever reports a stable code here. Anything else is
            // dropped rather than forwarded, because a provider body could
            // carry a credential.
            throw new BrokerError(/^[a-z_]{1,40}$/.test(failure) ? failure : 'broker_unavailable');
          }
          if (typeof onEvent === 'function') onEvent(event.payload);
        }
      }
    },
  });
}

/**
 * The loopback endpoint the runtime and plugins use. It authenticates the
 * runtime session token, forwards the request to the native broker and
 * streams the answer back as SSE. A runtime disconnect aborts the upstream
 * broker request, which aborts the provider connection.
 */
export function startModelProxy({ broker, host = '127.0.0.1', port = 0, token, logger }) {
  if (!broker || typeof broker.stream !== 'function') throw new TypeError('A broker client is required');
  if (typeof token !== 'string' || !token) throw new TypeError('A runtime session token is required');
  if (!LOOPBACK_HOST.test(host)) throw new TypeError('The proxy must listen on loopback');

  const log = (code) => { if (typeof logger === 'function') logger(code); };

  const server = createServer((req, res) => {
    const url = new URL(req.url ?? '/', 'http://127.0.0.1');
    if (url.pathname !== MODEL_STREAM_PATH || req.method !== 'POST') {
      res.writeHead(404, { 'content-type': 'text/plain' });
      res.end('not_found');
      return;
    }
    // A browser origin or a foreign Host header means the caller is not the
    // runtime. The proxy only serves loopback peers.
    if (req.headers.origin !== undefined) {
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
      if (size > MAX_BODY_BYTES) return;
      let request;
      try { request = JSON.parse(Buffer.concat(chunks).toString('utf8')); }
      catch { res.writeHead(400, { 'content-type': 'text/plain' }); res.end('bad_request'); return; }
      const controller = new AbortController();
      let finished = false;
      const abort = () => { if (!finished) controller.abort(); };
      req.on('aborted', abort);
      res.on('close', abort);
      res.writeHead(200, {
        'content-type': 'text/event-stream',
        'cache-control': 'no-store',
        connection: 'keep-alive',
      });
      broker.stream({
        providerId: request?.providerId,
        modelId: request?.modelId,
        messages: request?.messages,
        signal: controller.signal,
        onEvent: (payload) => {
          if (finished || controller.signal.aborted) return;
          res.write(`data: ${JSON.stringify(payload)}\n\n`);
        },
      }).then(() => {
        finished = true;
        res.end();
      }).catch((error) => {
        finished = true;
        const code = error instanceof BrokerError ? error.code : 'broker_unavailable';
        log(code);
        if (!res.headersSent) res.writeHead(502, { 'content-type': 'text/plain' });
        if (!controller.signal.aborted) {
          res.write(`data: ${JSON.stringify({ error: code })}\n\n`);
        }
        res.end();
      });
    });
  });

  return new Promise((resolvePromise, rejectPromise) => {
    server.on('error', rejectPromise);
    server.listen(port, host, () => {
      const address = server.address();
      resolvePromise(Object.freeze({
        host,
        port: address.port,
        baseUrl: `http://${host}:${address.port}`,
        token,
        dispose: () => new Promise((done) => server.close(() => done())),
      }));
    });
  });
}
