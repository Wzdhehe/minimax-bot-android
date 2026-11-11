// SPDX-License-Identifier: AGPL-3.0-only
// Trusted host composition only. No API here is exposed to Bot HTTP payloads.
import { AsyncLocalStorage } from 'node:async_hooks';
import { randomUUID } from 'node:crypto';
import { isAbsolute, resolve } from 'node:path';
import { createMiniAppAgentGateway as createUpstreamGateway } from '@mcode-runtime/agent-gateway';
import { createBotAgentPort } from './agent-port.mjs';
import { createNativeModelBinding, NativeBindingError } from './native-model-binding.mjs';
import { withNativeByokHost } from './runtime-native-review.mjs';
import { createNativeModelRuntime } from './native-model-runtime.mjs';
import { reportStartupProgress } from './runtime-startup-progress.mjs';

// A colon cannot occur in an installed plugin name in either pinned manifest
// reader. An installed package cannot replace this built-in arena by name.
const BUILTIN_ID = 'builtin:minimax-bot';
const construction = new AsyncLocalStorage();
// Only a metadata read may have no default. Model turns use the original
// resolver and authorization, even while another request lists the catalog.
const catalogRead = new AsyncLocalStorage();
const failure = (code) => Object.assign(new Error(code), { code });

/** Re-exported ONLY at the pinned RuntimeServices gateway construction seam. */
export function createMiniAppAgentGateway(options) {
  const gateway = createUpstreamGateway(options);
  const scope = construction.getStore();
  if (scope?.active) {
    if (resolve(options.dataDir) !== scope.dataDir) throw failure('runtime_gateway_scope_mismatch');
    if (scope.gateway) throw failure('runtime_gateway_ambiguous');
    scope.gateway = gateway;
    const bind = gateway.bind.bind(gateway);
    gateway.bind = (ports) => bind({ ...ports,
      resolveModel: (requested) => catalogRead.getStore() === gateway && requested === undefined
        ? Promise.resolve(undefined) : ports.resolveModel(requested),
    });
  }
  return gateway;
}

/**
 * startHost is a trusted composition dependency, never supplied by a request.
 * Production binds it to the pinned createWebuiRuntimeHost, which constructs
 * and binds the REAL gateway/session ports. No replacement ports are built.
 */
export async function createBotRuntimeSession({ dataDir, startHost, signal, catalog = null, probe = null, nativeBinding = null, onStartupPhase }) {
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
  let modelBinding = null, constructionBinding = null;
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
      try { modelBinding?.close(); } catch (error) { errors.push(error); }
      try { constructionBinding?.close(); } catch (error) { errors.push(error); }
      if (errors.length === 1) throw errors[0];
      if (errors.length > 1) throw new AggregateError(errors, 'runtime_close_failed');
    });
    return closePromise;
  }
  try {
    // The pinned host captures fetch while constructing its model/session
    // ports. Install the fail-closed dispatcher BEFORE that capture; binding
    // only after construction leaves those clients holding the old fetch.
    // No models are admitted during construction. The real binding below
    // acquires the same shared dispatcher before this temporary lease closes.
    if (catalog && nativeBinding) {
      constructionBinding = createNativeModelBinding({ byForkProviderId: {}, binding: nativeBinding });
      constructionBinding.install(globalThis);
    }
    reportStartupProgress(onStartupPhase, 'runtime_factory_starting');
    try { assembled = await construction.run(scope, () => catalog && nativeBinding
      ? withNativeByokHost(() => startHost({ dataDir: scope.dataDir })) : startHost({ dataDir: scope.dataDir })); }
    finally { scope.active = false; }
    const apiHost = assembled?.host?.apiHost;
    if (typeof apiHost?.close === 'function') closeRuntime = apiHost.close.bind(apiHost);
    if (!closeRuntime || !scope.gateway || !assembled.host.application?.miniAppAgent) {
      throw failure('runtime_agent_registration_unavailable');
    }
    reportStartupProgress(onStartupPhase, 'runtime_factory_started');
    if (signal?.aborted) throw failure('REQUEST_CANCELLED');
    const caller = Object.freeze({ pluginId: BUILTIN_ID, runId: randomUUID() });
    release = scope.gateway.registerArena({
      pluginId: caller.pluginId, processGeneration: caller.runId,
      policy: { kind: 'allowlist', capabilities: ['session.run'], maxConcurrent: 2 },
    });
    // The native catalog is projected through the upstream provider API: the
    // pinned upstream service owns provider keys, config locking and writes.
    const application = assembled.host.application;
    reportStartupProgress(onStartupPhase, 'native_catalog_starting');
    const nativeRuntime = await createNativeModelRuntime({
      application, catalog, probe, nativeBinding, fetchTarget: globalThis,
    });
    reportStartupProgress(onStartupPhase, 'native_catalog_projected');
    const providerBinding = nativeRuntime.providerBinding;
    modelBinding = nativeRuntime.binding;
    constructionBinding?.close();
    constructionBinding = null;
    const bot = createBotAgentPort({
      agent: application.miniAppAgent,
      caller,
      binding: modelBinding,
      resolveModelKey: nativeRuntime.resolveModelKey,
      autoApprove: true,
    });
    async function models() {
      const result = catalog && !nativeRuntime.defaultSelection
        ? await catalogRead.run(scope.gateway, () => bot.models())
        : await bot.models();
      if (!catalog) return result;
      // The upstream still owns the option shape, variants and efforts. Only
      // expose models Main has admitted, including when no default is chosen.
      const allowed = (row) => providerBinding.some((entry) =>
        entry.forkProviderId === row?.providerId && entry.models.includes(row?.modelId));
      const entries = result.models.filter(allowed);
      const selected = nativeRuntime.defaultSelection && allowed(result.default) ? result.default : undefined;
      return { models: entries, ...(selected ? { default: selected } : {}) };
    }
    // A retained application object cannot become usable again after close,
    // even if another generation later registers the same built-in plugin.
    const agent = Object.freeze(Object.fromEntries(['run', 'models', 'reply', 'answer'].map((method) => [method, (...args) => {
      if (closed) return Promise.reject(failure('AGENT_UNAVAILABLE'));
      return method === 'models' ? models() : bot[method](...args);
    }])));
    apiHost.close = close;
    signal?.addEventListener('abort', onAbort, { once: true });
    if (signal?.aborted) { await close(); throw failure('REQUEST_CANCELLED'); }
    /**
     * Switch the session model through the upstream session selection. The
     * switch reaches the next turn: a turn already running keeps its model.
     */
    const setSessionModel = async ({ providerId, modelId, sessionId }) => {
      const models = application?.models;
      if (!models || typeof models.select !== 'function') throw failure('runtime_model_selection_unavailable');
      const known = providerBinding.some((entry) => entry.forkProviderId === providerId && entry.models.includes(modelId));
      if (!known) throw new NativeBindingError('model_not_available');
      return models.select({ providerId, modelId, sessionId });
    };
    return Object.freeze({ agent, close, providerBinding, setSessionModel });
  } catch (error) {
    try { await close(); }
    catch (cleanupError) { throw new AggregateError([error, cleanupError], 'runtime_start_failed'); }
    throw error;
  }
}
