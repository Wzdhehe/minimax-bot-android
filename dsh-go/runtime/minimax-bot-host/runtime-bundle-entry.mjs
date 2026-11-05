// SPDX-License-Identifier: AGPL-3.0-only
// Build-only trusted host exports. This module deliberately does not boot an
// Agent, open a listener, register a caller, or change the Android launcher.
import { createWebuiRuntimeHost } from '@mcode-runtime/assembly';
import { createBotRuntimeSession } from './runtime-host-session.mjs';
import { catalogFromDescriptor, readRuntimeDescriptor, readRuntimeDescriptorFd } from './runtime-descriptor.mjs';
export { createWebuiRuntimeHost };

// The guest reads the descriptor the Android host wrote into its own private
// directory. This is the only channel that carries the native provider
// catalog into the guest.
export { readRuntimeDescriptor, readRuntimeDescriptorFd };

// Trusted controller factory; defining it does not launch a process/listener.
// Android must still assemble its independent-UID model/files adapters first.
//
// The non-secret provider catalog arrives inside the descriptor and not as a
// separate argument: the descriptor is the trust boundary, so the guest
// cannot be handed a catalog the host never wrote, and a descriptor that
// breaks the field policy is refused before anything is projected.
export function createBuiltinBotRuntime({ dataDir, appVersion, signal, descriptor = null, probe = null, nativeBinding = null }) {
  return createBotRuntimeSession({ dataDir, signal, catalog: catalogFromDescriptor(descriptor), probe, nativeBinding, startHost: ({ dataDir: root }) =>
    createWebuiRuntimeHost({ dataDir: root, appVersion, mcodeToolsRequested: false }) });
}
export { createBotAgentPort } from './agent-port.mjs';
export { startBotService } from './service.mjs';
export { createBotFdTransport } from './bot-fd-transport.mjs';
export { startBotFdIngress } from './bot-fd-ingress.mjs';
export { createBotStorePersistence } from './bot-store-persistence.mjs';
export { createBotFileLease } from './bot-file-lease.mjs';
