// Adapter for the pinned WebUI process-local agent contract. This module does
// not start a runtime or register a caller. The host must supply a caller
// resolved from its own live process registration, never from Bot requests.
export function createBotAgentPort({ agent, caller }) {
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
  return Object.freeze({
    run(input, options = {}) {
      return agent.run({
        caller: identity, text: input.text, sessionId: input.sessionId,
        agent: input.agent, model: input.model, signal: options.signal, onEvent: options.onEvent,
      });
    },
    models() { return agent.models({ caller: identity }); },
    reply(input) {
      return agent.reply({ caller: identity, sessionId: input.sessionId, requestId: input.requestId, decision: input.decision });
    },
    answer(input, options = {}) {
      return agent.answer({
        caller: identity, sessionId: input.sessionId, requestId: input.requestId,
        answers: input.answers, model: input.model, signal: options.signal, onEvent: options.onEvent,
      });
    },
  });
}
