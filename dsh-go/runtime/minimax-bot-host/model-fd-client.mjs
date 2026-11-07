// SPDX-License-Identifier: AGPL-3.0-only
import { Socket } from 'node:net';
import { Readable } from 'node:stream';
import { closeSync } from 'node:fs';
import { BrokerError, consumeModelBrokerResponse } from './model-broker.mjs';

const MAX_HEADER_BYTES = 16 * 1024;
const QUEUE_BYTES = 64 * 1024;

/** Consume one already-connected, already-authenticated native model request.
 * Takes ownership of fd and closes it on completion, failure, deadline or abort.
 * Does not create a connection, write HTTP requests, or accept an endpoint,
 * token, API key or file path. The Android/JNI handoff is not implemented here.
 */
export async function streamNativeModelFd({ fd, signal, onEvent, timeoutMs = 10 * 60_000 }) {
  if (!Number.isSafeInteger(fd) || fd < 3 || !Number.isFinite(timeoutMs) || timeoutMs <= 0 || timeoutMs > 10 * 60_000) {
    throw new BrokerError('broker_request_invalid');
  }
  let socket;
  try { socket = new Socket({ fd, readable: true, writable: false }); }
  catch {
    // A failed uv socket open has not adopted the descriptor.
    try { closeSync(fd); } catch {}
    throw new BrokerError('broker_unavailable');
  }
  const closed = new Promise((resolve) => socket.once('close', resolve));
  // Error details can include platform diagnostics. Never surface them.
  socket.on('error', () => {});
  let expired = false;
  const cancellation = new AbortController();
  const abort = () => { cancellation.abort(); socket.destroy(); };
  signal?.addEventListener('abort', abort, { once: true });
  const deadline = setTimeout(() => { expired = true; abort(); }, timeoutMs);
  try {
    if (signal?.aborted) throw new BrokerError('broker_cancelled');
    // Byte-counted queues are essential: the default Web strategy counts each
    // Buffer as one item, which can multiply the socket's byte high-water mark.
    const body = Readable.toWeb(socket, { strategy: {
      highWaterMark: QUEUE_BYTES, size: (chunk) => chunk.byteLength,
    } });
    const response = await readResponse(body);
    await consumeModelBrokerResponse(response, { signal: cancellation.signal, onEvent });
  } catch (error) {
    if (signal?.aborted) throw new BrokerError('broker_cancelled');
    if (expired) throw new BrokerError('broker_stream_failed');
    throw error instanceof BrokerError ? error : new BrokerError('broker_stream_failed');
  } finally {
    clearTimeout(deadline);
    signal?.removeEventListener('abort', abort);
    socket.destroy();
    await closed;
  }
}

async function readResponse(source) {
  const reader = source.getReader();
  let transferred = false;
  try {
    let header = Buffer.alloc(0);
    let boundary = -1;
    while (boundary < 0) {
      const chunk = await reader.read();
      if (chunk.done) throw new BrokerError('broker_stream_failed');
      header = Buffer.concat([header, chunk.value]);
      boundary = header.indexOf('\r\n\r\n');
      if (boundary > MAX_HEADER_BYTES || (boundary < 0 && header.length > MAX_HEADER_BYTES)) {
        throw new BrokerError('broker_invalid_response');
      }
    }
    const lines = header.subarray(0, boundary).toString('latin1').split('\r\n');
    const status = /^HTTP\/1\.1 ([1-5][0-9]{2}) [^\r\n]*$/.exec(lines.shift());
    if (!status) throw new BrokerError('broker_invalid_response');
    const headers = new Headers();
    for (const line of lines) {
      const colon = line.indexOf(':');
      const name = line.slice(0, colon).toLowerCase();
      if (colon < 1 || !/^[a-z0-9-]+$/.test(name) || headers.has(name)) {
        throw new BrokerError('broker_invalid_response');
      }
      headers.set(name, line.slice(colon + 1).trim());
    }
    if (headers.has('transfer-encoding')) throw new BrokerError('broker_invalid_response');
    if (Number(status[1]) !== 200) throw new BrokerError('broker_rejected');
    // Native FD streams always use explicit completion; no legacy EOF mode.
    if (headers.get('x-mbot-broker-protocol') !== '1') throw new BrokerError('broker_invalid_response');
    const remainder = header.subarray(boundary + 4);
    let released = false;
    const release = () => { if (!released) { released = true; reader.releaseLock(); } };
    const response = new Response(new ReadableStream({
      start(controller) { if (remainder.length) controller.enqueue(remainder); },
      async pull(controller) {
        try {
          const chunk = await reader.read();
          if (chunk.done) { release(); controller.close(); }
          else controller.enqueue(chunk.value);
        } catch {
          release(); controller.error(new BrokerError('broker_stream_failed'));
        }
      },
      async cancel() { try { await reader.cancel(); } finally { release(); } },
    }, new ByteLengthQueuingStrategy({ highWaterMark: QUEUE_BYTES })), { status: 200, headers });
    transferred = true;
    return response;
  } finally {
    if (!transferred) { await reader.cancel().catch(() => {}); reader.releaseLock(); }
  }
}

// Ownership handoff shared by SDK requests and native provider probes.

export async function acquireModelFd(openChannel, payload, signal) {
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
    if (abandoned || signal.aborted) { closeOwned(fd); throw new BrokerError('broker_cancelled'); }
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
    if (!transferred && received !== undefined) closeOwned(received);
  }
}

function closeOwned(fd) { try { closeSync(fd); } catch {} }
