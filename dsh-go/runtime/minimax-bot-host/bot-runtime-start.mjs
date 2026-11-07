// SPDX-License-Identifier: AGPL-3.0-only
import { isAbsolute } from 'node:path';
import { readRuntimeDescriptorFd } from './runtime-descriptor.mjs';
import { createBotPersistence } from './bot-persistence.mjs';
import { createBotFdTransport } from './bot-fd-transport.mjs';
import { startBotFdIngress } from './bot-fd-ingress.mjs';
import { startBotService } from './service.mjs';

const failure = (code) => Object.assign(new Error(code), { code });

/** Trusted guest composition. The bundle entry binds startRuntime to the real
 * pinned factory; it is not taken from IPC or HTTP. Consumes the descriptor FD
 * even on failure. Main must still provide the isolated process, mcode's own
 * admitted filesystem/SQLite, native capabilities and a private ready channel.
 * Bot persistence uses only files; no workspace/media access or guest listener.
 */
export async function startBotRuntimeFromFd({ descriptorFd, expectedOwnerUid, runtimeDataDir, botDataDir,
  appVersion, nativeBinding, nativeSqlite, files, connections, onReady, signal, logger, startRuntime, mobileFiles = null } = {}) {
  const descriptor = readRuntimeDescriptorFd({ fd: descriptorFd, expectedOwnerUid });
  if (descriptor.broker !== null ||
      typeof runtimeDataDir !== 'string' || !isAbsolute(runtimeDataDir) || runtimeDataDir.includes('\0') ||
      typeof botDataDir !== 'string' || !isAbsolute(botDataDir) || botDataDir.includes('\0') ||
      typeof appVersion !== 'string' || !appVersion || appVersion.length > 128 ||
      typeof nativeBinding?.open !== 'function' || typeof nativeBinding?.cancel !== 'function' ||
      ['read', 'size', 'replace', 'append', 'publish', 'rename', 'remove'].some((name) => typeof files?.[name] !== 'function') ||
      typeof connections?.next !== 'function' || typeof connections?.cancel !== 'function' ||
      typeof onReady !== 'function' || typeof startRuntime !== 'function' ||
      ['info', 'warn', 'error'].some((name) => typeof logger?.[name] !== 'function') ||
      (signal !== undefined && (typeof signal?.addEventListener !== 'function' || typeof signal?.removeEventListener !== 'function' || typeof signal?.aborted !== 'boolean'))) {
    throw failure('runtime_start_invalid');
  }
  if (signal?.aborted) throw failure('REQUEST_CANCELLED');
  const controller = new AbortController();
  const transport = createBotFdTransport();
  const persistence = createBotPersistence({ dataDir: botDataDir, files });
  let runtime, service, ingress;
  let stopped = false, started = false, failed = false;
  let closing;
  let settled, rejected;
  const done = new Promise((resolve, reject) => { settled = resolve; rejected = reject; });
  // Start can fail before a handle exists; never leave a second unobserved
  // rejection. Callers of a returned handle still observe the original done.
  void done.catch(() => {});

  function stopNow() {
    if (stopped) return;
    stopped = true;
    controller.abort();
    try { connections.cancel(); } catch { failed = true; }
    transport.close();
  }
  function close() {
    if (closing) return closing;
    stopNow();
    // Assign before executing foreign cleanup, so abort/reentrant close joins
    // the same teardown. A failed step does not skip subsequent cleanup.
    closing = Promise.resolve().then(async () => {
      signal?.removeEventListener('abort', onAbort);
      try { await ingress?.close(); } catch { failed = true; }
      try { await service?.dispose(); } catch { failed = true; }
      try { if (runtime) await runtime.close(); } catch { failed = true; }
      if (failed) throw failure('runtime_close_unverified');
    });
    closing.then(settled, rejected);
    return closing;
  }
  function onAbort() {
    stopNow();
    // While a factory is pending, the startup path adopts and closes its
    // eventual result before teardown settles. Do not report an early drain.
    if (started) void close().catch(() => {});
  }
  function ensureLive() { if (stopped || signal?.aborted) throw failure('REQUEST_CANCELLED'); }
  function ingressEnded() {
    if (stopped) return;
    failed = true;
    stopNow();
    if (started) void close().catch(() => {});
  }
  signal?.addEventListener('abort', onAbort, { once: true });
  if (signal?.aborted) onAbort();
  try {
    ensureLive();
    runtime = await startRuntime({ dataDir: runtimeDataDir, appVersion, descriptor, nativeBinding, nativeSqlite, signal: controller.signal });
    if (!runtime || typeof runtime.close !== 'function' ||
        ['run', 'models', 'reply', 'answer'].some((name) => typeof runtime.agent?.[name] !== 'function')) {
      throw failure('runtime_start_invalid');
    }
    ensureLive();
    service = await startBotService({ port: descriptor.port, authToken: descriptor.token,
      dataDir: botDataDir, fileMode: mobileFiles ? 'mobile' : 'unavailable', mobileFiles, ...persistence, agent: runtime.agent,
      signal: controller.signal, logger, fdTransport: transport });
    ensureLive();
    ingress = startBotFdIngress({ transport, next: () => connections.next(), signal: controller.signal });
    void ingress.done.then(ingressEnded, ingressEnded);
    ensureLive();
    // No token, URL, Key, descriptor or agent-controlled data in this receipt.
    // Main must correlate it to the actual launching UID and run generation.
    await onReady();
    ensureLive();
    started = true;
    return Object.freeze({ close, done });
  } catch {
    try { await close(); } catch { /* Returned failure is stable and keyless. */ }
    throw failure(signal?.aborted ? 'REQUEST_CANCELLED' : 'runtime_start_unavailable');
  }
}
