import { randomBytes, timingSafeEqual } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { start } from '../vendor/minimax-bot/miniapp/node/server.mjs';
import { createHostWorkspace } from './workspace.mjs';
import { readUsageQuota } from './usage-quota.mjs';
import { createProviderBroker } from './provider-broker.mjs';

/** The descriptor belongs on a private Android host channel. This authenticates
 * HTTP requests; it does not isolate Agent code or provide model credentials. */
export async function startBotService({ port, dataDir, filesDir, agent, modelBroker, getUsageQuota, signal, logger, authToken, fdTransport }) {
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new TypeError('A local port is required');
  // A trusted launcher may already have published this session in its private
  // descriptor. Validate before filesystem or listener side effects, and never
  // silently rotate an explicitly supplied but malformed credential.
  if (authToken !== undefined && (typeof authToken !== 'string' || !/^[0-9a-f]{32,64}$/.test(authToken))) {
    throw new TypeError('A 32-64 hex Bot session token is required');
  }
  if (fdTransport !== undefined && (typeof fdTransport?.listenServer !== 'function' || typeof fdTransport?.close !== 'function')) {
    throw new TypeError('A trusted Bot FD transport is required');
  }
  const token = authToken ?? randomBytes(32).toString('hex');
  const workspace = await createHostWorkspace(filesDir);
  // Provider probes use the same native stream capability as model calls.
  // No resolver returning plaintext credentials is accepted by this wrapper.
  const providerBroker = modelBroker ? createProviderBroker({ modelBroker }) : undefined;
  const credential = Buffer.from(`Bearer ${token}`);
  const host = `127.0.0.1:${port}`;
  let lifecycle;
  try { lifecycle = await start({
    pluginRoot: fileURLToPath(new URL('../vendor/minimax-bot/', import.meta.url)),
    dataDir, agent, signal, logger, listen: { host: '127.0.0.1', port },
  }, {
    listenServer: fdTransport?.listenServer,
    resolveMediaPath: (path) => workspace.resolve(path),
    testProvider: providerBroker ? (id, model) => providerBroker.testProvider(id, model) : undefined,
    getUsageQuota: (forceRefresh) => readUsageQuota(getUsageQuota, forceRefresh),
    authorizeRequest(request) {
      if (request.headers.origin !== undefined || request.headers.host !== host) return false;
      const supplied = request.headers.authorization;
      if (typeof supplied !== 'string') return false;
      const bytes = Buffer.from(supplied);
      return bytes.length === credential.length && timingSafeEqual(bytes, credential);
    },
  }); } catch (error) { fdTransport?.close(); throw error; }
  const onAbort = () => fdTransport?.close();
  signal?.addEventListener('abort', onAbort, { once: true });
  if (signal?.aborted) onAbort();
  return {
    workspace,
    descriptor: Object.freeze({ baseUrl: `http://${host}`, headers: Object.freeze({ Authorization: `Bearer ${token}` }) }),
    async dispose() {
      signal?.removeEventListener('abort', onAbort);
      fdTransport?.close();
      await lifecycle.dispose();
    },
  };
}
