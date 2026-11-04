// SPDX-License-Identifier: AGPL-3.0-only
// Trusted host composition only. No API here is exposed to Bot HTTP payloads.
import { AsyncLocalStorage } from 'node:async_hooks';
import { randomUUID } from 'node:crypto';
import { isAbsolute, resolve } from 'node:path';
import { createMiniAppAgentGateway as createUpstreamGateway } from '@mcode-runtime/agent-gateway';
import { createBotAgentPort } from './agent-port.mjs';

// A colon cannot occur in an installed plugin name in either pinned manifest
// reader. An installed package cannot replace this built-in arena by name.
const BUILTIN_ID = 'builtin:minimax-bot';
const construction = new AsyncLocalStorage();
const failure = (code) => Object.assign(new Error(code), { code });

/** Re-exported ONLY at the pinned RuntimeServices gateway construction seam. */
export function createMiniAppAgentGateway(options) {
  const gateway = createUpstreamGateway(options);
  const scope = construction.getStore();
  if (scope?.active) {
    if (resolve(options.dataDir) !== scope.dataDir) throw failure('runtime_gateway_scope_mismatch');
    if (scope.gateway) throw failure('runtime_gateway_ambiguous');
    scope.gateway = gateway;
  }
  return gateway;
}

/**
 * startHost is a trusted composition dependency, never supplied by a request.
 * Production binds it to the pinned createWebuiRuntimeHost, which constructs
 * and binds the REAL gateway/session ports. No replacement ports are built.
 */
export async function createBotRuntimeSession({ dataDir, startHost, signal }) {
  if (typeof dataDir !== 'string' || !isAbsolute(dataDir) || dataDir.includes('\0')) {
    throw failure('runtime_data_directory_required');
  }
  if (typeof startHost !== 'function') throw failure('runtime_factory_required');
  if (signal?.aborted) throw failure('REQUEST_CANCELLED');
  const scope = { active: true, dataDir: resolve(dataDir), gateway: null };
  let assembled;
  let release;
  let closeRuntime;
  let closed = false;
  let closePromise;
  const onAbort = () => { void close().catch(() => {}); };
  function close() {
    if (closePromise) return closePromise;
    closed = true;
    signal?.removeEventListener('abort', onAbort);
    // Install the promise before invoking foreign cleanup; synchronous abort
    // callbacks or concurrent close callers must all join the same teardown.
    closePromise = Promise.resolve().then(async () => {
      const errors = [];
      try { release?.(); } catch (error) { errors.push(error); }
      try { await closeRuntime?.(); } catch (error) { errors.push(error); }
      if (errors.length === 1) throw errors[0];
      if (errors.length > 1) throw new AggregateError(errors, 'runtime_close_failed');
    });
    return closePromise;
  }
  try {
    try { assembled = await construction.run(scope, () => startHost({ dataDir: scope.dataDir })); }
    finally { scope.active = false; }
    const apiHost = assembled?.host?.apiHost;
    if (typeof apiHost?.close === 'function') closeRuntime = apiHost.close.bind(apiHost);
    if (!closeRuntime || !scope.gateway || !assembled.host.application?.miniAppAgent) {
      throw failure('runtime_agent_registration_unavailable');
    }
    if (signal?.aborted) throw failure('REQUEST_CANCELLED');
    const caller = Object.freeze({ pluginId: BUILTIN_ID, runId: randomUUID() });
    release = scope.gateway.registerArena({
      pluginId: caller.pluginId, processGeneration: caller.runId,
      policy: { kind: 'allowlist', capabilities: ['session.run'], maxConcurrent: 2 },
    });
    const bot = createBotAgentPort({ agent: assembled.host.application.miniAppAgent, caller });
    // A retained application object cannot become usable again after close,
    // even if another generation later registers the same built-in plugin.
    const agent = Object.freeze(Object.fromEntries(['run', 'models', 'reply', 'answer'].map((method) => [method, (...args) => {
      if (closed) return Promise.reject(failure('AGENT_UNAVAILABLE'));
      return bot[method](...args);
    }])));
    apiHost.close = close;
    signal?.addEventListener('abort', onAbort, { once: true });
    if (signal?.aborted) { await close(); throw failure('REQUEST_CANCELLED'); }
    return Object.freeze({ agent, close });
  } catch (error) {
    try { await close(); }
    catch (cleanupError) { throw new AggregateError([error, cleanupError], 'runtime_start_failed'); }
    throw error;
  }
}
