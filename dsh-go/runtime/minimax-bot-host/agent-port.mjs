// Adapter for the pinned WebUI process-local agent contract. This module does
// not start a runtime or register a caller. The host must supply a caller
// resolved from its own live process registration, never from Bot requests.
import { NativeBindingError } from './native-model-binding.mjs';

export function createBotAgentPort({ agent, caller, binding = null, resolveModelKey = null, autoApprove = false }) {
  if (!agent || !['run', 'models', 'reply', 'answer'].every((name) => typeof agent[name] === 'function')) {
    throw new TypeError('A complete process-local agent port is required');
  }
  if (typeof caller?.pluginId !== 'string' || !caller.pluginId ||
      typeof caller?.runId !== 'string' || !caller.runId) {
    throw new TypeError('A host-registered caller is required');
  }
  // Capture the identity at creation; Bot-controlled arguments cannot change it.
  const identity = Object.freeze({
    pluginId: caller.pluginId,
    runId: caller.runId,
    ...(caller.hostSessionId === undefined ? {} : { hostSessionId: caller.hostSessionId }),
  });

  /**
   * The model key of one turn. A model named by the caller wins; otherwise the
   * session's current selection is read once, before the turn starts. The key
   * is never resolved again inside the turn, so a model switched while the
   * turn runs takes effect from the next turn.
   */
  async function modelKeyFor(input) {
    const requested = input?.model;
    if (typeof requested === 'string' && requested) return requested;
    if (requested && typeof requested === 'object' &&
        typeof requested.providerId === 'string' && typeof requested.modelId === 'string') {
      return `${requested.providerId}/${requested.modelId}`;
    }
    if (typeof resolveModelKey !== 'function') throw new NativeBindingError('model_not_available');
    const resolved = await resolveModelKey(input?.sessionId);
    if (typeof resolved !== 'string' || !resolved) throw new NativeBindingError('model_not_available');
    return resolved;
  }

  /** Run one turn inside the native route of its own model. */
  function boundTurn(input, call) {
    // An absent broker must never turn a model request into an upstream
    // network request, including when the runtime has old provider config.
    if (!binding) return Promise.reject(new NativeBindingError('native_binding_unavailable'));
    return (async () => binding.runForModelKey(await modelKeyFor(input), call))();
  }

  // Trusted product policy, never a Bot/HTTP input. Native capabilities and
  // protected-path denials still apply. Replies grant one owned request only;
  // they never install global rules or provide an unrestricted Android shell.
  function turn(input, options, invoke) {
    if (!autoApprove) return boundTurn(input, () => invoke(options.signal, options.onEvent));
    const abort = new AbortController(), replies = new Map();
    const signal = options.signal ? AbortSignal.any([options.signal, abort.signal]) : abort.signal;
    let active = true, failed = false;
    const onEvent = (event) => {
      if (!active) return;
      if (event.type !== 'permission.requested') { options.onEvent?.(event); return; }
      if (typeof event.sessionId !== 'string' || !event.sessionId || typeof event.requestId !== 'string' || !event.requestId) {
        failed = true; abort.abort(); return;
      }
      const key = JSON.stringify([event.sessionId, event.requestId]);
      if (replies.has(key)) return;
      if (replies.size >= 128) { failed = true; abort.abort(); return; }
      const reply = Promise.resolve().then(() => agent.reply({ caller: identity, sessionId: event.sessionId,
        requestId: event.requestId, decision: 'allowOnce' })).catch(() => { failed = true; abort.abort(); });
      replies.set(key, reply);
    };
    return boundTurn(input, () => invoke(signal, onEvent)).then(async (value) => {
      await Promise.all(replies.values());
      if (failed) throw new Error('bot_permission_reply_failed');
      return value;
    }).finally(() => { active = false; abort.abort(); });
  }

  return Object.freeze({
    run(input, options = {}) {
      return turn(input, options, (signal, onEvent) => agent.run({
        caller: identity, text: input.text, sessionId: input.sessionId,
        agent: input.agent, model: input.model, signal, onEvent,
      }));
    },
    models() { return agent.models({ caller: identity }); },
    reply(input) {
      return agent.reply({ caller: identity, sessionId: input.sessionId, requestId: input.requestId, decision: input.decision });
    },
    answer(input, options = {}) {
      return turn(input, options, (signal, onEvent) => agent.answer({
        caller: identity, sessionId: input.sessionId, requestId: input.requestId,
        answers: input.answers, model: input.model, signal, onEvent,
      }));
    },
  });
}
