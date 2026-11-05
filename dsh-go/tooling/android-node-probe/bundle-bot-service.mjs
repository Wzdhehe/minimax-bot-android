// SPDX-License-Identifier: AGPL-3.0-only
// Build a fixed verification-only module; no downloaded or Agent code is run.
import { realpathSync } from 'node:fs';
import { isBuiltin } from 'node:module';
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const dsh = resolve(here, '../..');
const repo = resolve(dsh, '..');
const source = join(dsh, 'runtime/minimax-bot-host/service.mjs');
const roots = [join(dsh, 'runtime/minimax-bot-host'), join(dsh, 'runtime/vendor/minimax-bot'), join(repo, 'packages/mbot-workspace-guard')].map((path) => realpathSync(path));
const modulePath = join(dsh, 'tooling/model-sdk-probe/node_modules/esbuild/lib/main.js');
const esbuild = await import(pathToFileURL(modulePath).href);
if (esbuild.version !== '0.28.2') throw new Error('probe_esbuild_version_invalid');
const result = await esbuild.build({ absWorkingDir: dsh, entryPoints: [source], bundle: true, platform: 'node',
  format: 'esm', target: 'node24', write: false, metafile: true, logLevel: 'silent' });
const inputs = Object.keys(result.metafile.inputs).map((name) => realpathSync(resolve(dsh, name)));
const inside = (root, path) => {
  const rel = relative(root, path);
  return rel !== '' && rel !== '..' && !rel.startsWith(`..${sep}`) && !isAbsolute(rel);
};
if (inputs.length > 200 || inputs.some((path) => !roots.some((root) => inside(root, path))) ||
    !inputs.includes(realpathSync(source)) || !inputs.includes(realpathSync(join(dsh, 'runtime/vendor/minimax-bot/miniapp/node/server.mjs')))) {
  throw new Error('probe_bot_bundle_input_invalid');
}
const external = Object.values(result.metafile.outputs).flatMap((output) => output.imports.filter((entry) => entry.external));
if (external.some((entry) => !isBuiltin(entry.path)) || result.outputFiles.length !== 1 || result.outputFiles[0].contents.length > 2 * 1024 * 1024) {
  throw new Error('probe_bot_bundle_output_invalid');
}
process.stdout.write(`data:text/javascript;base64,${Buffer.from(result.outputFiles[0].contents).toString('base64')}`);
