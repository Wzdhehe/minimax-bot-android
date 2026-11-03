// SPDX-License-Identifier: AGPL-3.0-only
//
// Test-only stand-ins for the two processes that cannot run on a build host:
//   - the native Android broker (Kotlin, holds the provider key), and
//   - the upstream provider API.
//
// The double implements the same wire protocol as the native broker:
//   POST /v1/model/stream
//   authorization: Bearer <session token>
//   body: {"providerId","modelId","messages":[{"role","content"}]}
//   200 text/event-stream, frames "data: {json}\n\n", terminated by
//   "data: [DONE]\n\n" or by closing the connection.
// This legacy double has no X-Mbot-Broker-Protocol header. Production v1
// native/host streams advertise that header and require DONE for completion.
//
// The key lives in the double exactly where it lives in the real broker: the
// native side. It is injected into the upstream request only. The host and
// the runtime never receive it.

import { createServer } from 'node:http';

/** A mock provider API that answers with SSE frames and records its own
 * lifecycle, so a test can prove the connection was closed. */
export async function startMockUpstream({ frames = [], status = 200 } = {}) {
  const state = { requests: [], closed: 0, aborted: 0, body: '' };
  const server = createServer((req, res) => {
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', async () => {
      state.requests.push({ headers: { ...req.headers }, body: Buffer.concat(chunks).toString('utf8') });
      if (status !== 200) {
        res.writeHead(status, { 'content-type': 'application/json' });
        res.end('{"error":"denied"}');
        return;
      }
      res.writeHead(200, { 'content-type': 'text/event-stream' });
      for (const frame of frames) {
        if (state.aborted > 0 || res.destroyed) return;
        res.write(`data: ${JSON.stringify(frame)}\n\n`);
        await new Promise((done) => setTimeout(done, 10));
      }
      if (!res.destroyed) res.write('data: [DONE]\n\n');
      res.end();
    });
    req.on('aborted', () => { state.aborted += 1; });
    res.on('close', () => { state.closed += 1; });
  });
  await new Promise((done) => server.listen(0, '127.0.0.1', done));
  const { port } = server.address();
  return {
    baseUrl: `http://127.0.0.1:${port}`,
    state,
    dispose: () => new Promise((done) => server.close(() => done())),
  };
}

/** The native broker stand-in. It injects the key into the upstream request
 * and streams the upstream answer back, aborting upstream when the caller
 * disconnects. */
export async function startNativeBrokerDouble({ key, upstream, token = 'a'.repeat(64) }) {
  const state = { upstreamHeaders: null, upstreamBody: null, injected: false };
  const server = createServer((req, res) => {
    if (req.headers.authorization !== `Bearer ${token}`) {
      res.writeHead(401, { 'content-type': 'text/plain' });
      res.end('unauthorized');
      return;
    }
    const controller = new AbortController();
    // The host proxy aborts the broker request when the runtime cancels. That
    // must close the provider connection the broker opened.
    req.on('aborted', () => controller.abort());
    res.on('close', () => controller.abort());
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', async () => {
      const request = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      const target = new URL('/v1/chat/completions', upstream.baseUrl);
      let response;
      try {
        response = await fetch(target, {
          method: 'POST',
          headers: {
            authorization: `Bearer ${key}`,
            'content-type': 'application/json',
          },
          body: JSON.stringify({ model: request.modelId, messages: request.messages }),
          signal: controller.signal,
        });
      } catch {
        res.end();
        return;
      }
      state.injected = true;
      res.writeHead(response.status, { 'content-type': 'text/event-stream' });
      const reader = response.body.getReader();
      try {
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          res.write(Buffer.from(value));
        }
      } catch {
        // the caller aborted; the provider connection is closed here
      }
      res.end();
    });
  });
  await new Promise((done) => server.listen(0, '127.0.0.1', done));
  const { port } = server.address();
  return {
    baseUrl: `http://127.0.0.1:${port}`,
    token,
    state,
    dispose: () => new Promise((done) => server.close(() => done())),
  };
}
