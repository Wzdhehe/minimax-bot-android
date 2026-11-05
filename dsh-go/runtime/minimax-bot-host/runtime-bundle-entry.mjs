// SPDX-License-Identifier: AGPL-3.0-only
// Build-only trusted host exports. This module deliberately does not boot an
// Agent, open a listener, register a caller, or change the Android launcher.
import { createWebuiRuntimeHost } from '@mcode-runtime/assembly';
import { createBotRuntimeSession } from './runtime-host-session.mjs';
import { catalogFromDescriptor, readRuntimeDescriptor, readRuntimeDescriptorFd } from './runtime-descriptor.mjs';
import { startBotRuntimeFromFd } from './bot-runtime-start.mjs';
import { bindRuntimeSqlite } from './runtime-sqlite-binding.mjs';
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
export async function createBuiltinBotRuntime({ dataDir, appVersion, signal, descriptor = null, probe = null, nativeBinding = null, nativeSqlite = null }) {
  const sqlite = nativeSqlite === null ? null : bindRuntimeSqlite({ dataDir,
    open: typeof nativeSqlite?.open === 'function' ? nativeSqlite.open.bind(nativeSqlite) : null,
    backup: typeof nativeSqlite?.backup === 'function' ? nativeSqlite.backup.bind(nativeSqlite) : nativeSqlite?.backup,
    openBackup: typeof nativeSqlite?.openBackup === 'function' ? nativeSqlite.openBackup.bind(nativeSqlite) : nativeSqlite?.openBackup });
  try {
    const runtime = await createBotRuntimeSession({ dataDir, signal, catalog: catalogFromDescriptor(descriptor), probe, nativeBinding, startHost: ({ dataDir: root }) =>
      createWebuiRuntimeHost({ dataDir: root, appVersion, mcodeToolsRequested: false }) });
    if (!sqlite) return runtime;
    let closing;
    function close() {
      if (closing) return closing;
      sqlite.deactivate();
      signal?.removeEventListener('abort', onAbort);
      closing = Promise.resolve().then(() => runtime.close()).then((value) => {
        sqlite.finish(true); return value;
      }, (error) => { sqlite.finish(false); throw error; });
      return closing;
    }
    function onAbort() { void close().catch(() => {}); }
    signal?.addEventListener('abort', onAbort, { once: true });
    if (signal?.aborted) {
      await close();
      throw Object.assign(new Error('REQUEST_CANCELLED'), { code: 'REQUEST_CANCELLED' });
    }
    return Object.freeze({ ...runtime, close });
  } catch (error) {
    sqlite?.finish(false);
    throw error;
  }
}

// Explicit trusted startup, not an import side effect. Native must assemble
// the independent-UID process/files/SQLite first. A caller cannot replace the
// pinned factory by adding startRuntime to options.
export function startBuiltinBotRuntimeFromFd(options) {
  return startBotRuntimeFromFd({ ...options, startRuntime: createBuiltinBotRuntime });
}
export { createBotAgentPort } from './agent-port.mjs';
export { startBotService } from './service.mjs';
export { createBotFdTransport } from './bot-fd-transport.mjs';
export { startBotFdIngress } from './bot-fd-ingress.mjs';
export { createBotStorePersistence } from './bot-store-persistence.mjs';
export { createBotFileLease } from './bot-file-lease.mjs';
export { createBotMemoryFilesystem } from './bot-memory-filesystem.mjs';
export { createBotPersistence } from './bot-persistence.mjs';
