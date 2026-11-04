// SPDX-License-Identifier: AGPL-3.0-only
// Build-only trusted host exports. This module deliberately does not boot an
// Agent, open a listener, register a caller, or change the Android launcher.
import { createWebuiRuntimeHost } from '@mcode-runtime/assembly';
import { createBotRuntimeSession } from './runtime-host-session.mjs';
export { createWebuiRuntimeHost };

// Trusted controller factory; defining it does not launch a process/listener.
// Android must still assemble its independent-UID model/files adapters first.
export function createBuiltinBotRuntime({ dataDir, appVersion, signal }) {
  return createBotRuntimeSession({ dataDir, signal, startHost: ({ dataDir: root }) =>
    createWebuiRuntimeHost({ dataDir: root, appVersion, mcodeToolsRequested: false }) });
}
export { createBotAgentPort } from './agent-port.mjs';
export { startBotService } from './service.mjs';
