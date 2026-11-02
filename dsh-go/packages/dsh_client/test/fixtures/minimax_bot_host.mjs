// Local contract fixture: the real MiniMax Bot service, without a model host.
import { pathToFileURL } from 'node:url';
import { join } from 'node:path';

const [pluginRoot, dataDir, port] = process.argv.slice(2);
const { start } = await import(pathToFileURL(join(pluginRoot, 'miniapp/node/server.mjs')).href);
const abort = new AbortController();
const app = await start({
  pluginRoot, dataDir, listen: { host: '127.0.0.1', port: Number(port) },
  signal: abort.signal, logger: { info() {}, warn() {}, error() {} },
});
console.log('READY');
let closing = false;
process.on('SIGTERM', async () => {
  if (closing) return;
  closing = true;
  await app.dispose();
  process.exit(0);
});
