// SPDX-License-Identifier: AGPL-3.0-only
import { closeSync } from 'node:fs';

const STOPPED = Symbol('stopped');
const unavailable = () => Object.assign(new Error('bot_transport_unavailable'), { code: 'bot_transport_unavailable' });

/**
 * Forward connections from a trusted, owner-authorized FD source to one Bot
 * transport. Each successful next(signal) must transfer a new, independently
 * owned FD. The transport owns each FD once accept is called. This helper is
 * not an authorization boundary and does not create a listening socket.
 *
 * close() stops promptly even if next ignores abort. A late FD from that one
 * pending request is closed when it arrives; callers should also make their
 * native source honor abort or revoke it when the isolated process exits.
 */
export function startBotFdIngress({ transport, next, signal } = {}) {
  if (typeof transport?.accept !== 'function' || typeof next !== 'function' ||
      (signal !== undefined && (typeof signal?.addEventListener !== 'function' || typeof signal?.removeEventListener !== 'function' || typeof signal?.aborted !== 'boolean'))) {
    throw new TypeError('A trusted Bot connection source and transport are required');
  }
  const controller = new AbortController();
  let closed = false;
  let wake;
  function stop() {
    if (closed) return;
    closed = true;
    controller.abort();
    wake?.();
  }
  function waitForNext() {
    return new Promise((resolve, reject) => {
      let settled = false;
      const onStop = () => finish(STOPPED);
      function finish(value, error) {
        if (settled) return;
        settled = true;
        if (wake === onStop) wake = undefined;
        if (error) reject(error); else resolve(value);
      }
      wake = onStop;
      Promise.resolve().then(() => closed ? STOPPED : next(controller.signal)).then((fd) => {
        if (fd === STOPPED) return finish(STOPPED);
        if (settled || closed) {
          if (Number.isSafeInteger(fd) && fd >= 3) try { closeSync(fd); } catch { }
          return finish(STOPPED);
        }
        finish({ fd });
      }, () => finish(STOPPED, closed ? undefined : unavailable()));
      if (closed) onStop();
    });
  }
  const onAbort = () => stop();
  signal?.addEventListener('abort', onAbort, { once: true });
  if (signal?.aborted) stop();
  const done = (async () => {
    try {
      while (!closed) {
        // Only one request can be outstanding. The handler remains attached
        // after stop so a late FD is not leaked even if next ignores its signal.
        const result = await waitForNext();
        if (result === STOPPED) break;
        if (closed) {
          if (Number.isSafeInteger(result.fd) && result.fd >= 3) try { closeSync(result.fd); } catch { }
          break;
        }
        try { transport.accept(result.fd); } catch { throw unavailable(); }
      }
    } finally {
      stop();
      signal?.removeEventListener('abort', onAbort);
    }
  })();
  return Object.freeze({ done, close() { stop(); return done; } });
}
