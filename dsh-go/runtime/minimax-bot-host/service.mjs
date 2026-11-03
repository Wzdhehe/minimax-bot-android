import { randomBytes, timingSafeEqual } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { start } from '../vendor/minimax-bot/miniapp/node/server.mjs';
import { createHostWorkspace } from './workspace.mjs';
import { readUsageQuota } from './usage-quota.mjs';

/** The descriptor belongs on a private Android host channel. This authenticates
 * HTTP requests; it does not isolate Agent code or provide model credentials. */
export async function startBotService({ port, dataDir, filesDir, agent, providerBroker, getUsageQuota, signal, logger }) {
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new TypeError('A local port is required');
  const workspace = await createHostWorkspace(filesDir);
  const token = randomBytes(32).toString('hex');
  const credential = Buffer.from(`Bearer ${token}`);
  const host = `127.0.0.1:${port}`;
  const lifecycle = await start({
    pluginRoot: fileURLToPath(new URL('../vendor/minimax-bot/', import.meta.url)),
    dataDir, agent, signal, logger, listen: { host: '127.0.0.1', port },
  }, {
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
  });
  return {
    workspace,
    descriptor: Object.freeze({ baseUrl: `http://${host}`, headers: Object.freeze({ Authorization: `Bearer ${token}` }) }),
    dispose: lifecycle.dispose,
  };
}
