// SPDX-License-Identifier: AGPL-3.0-only
import { build } from 'esbuild';
import { existsSync, readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { resolve, join, relative, dirname, isAbsolute } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { checkUpstreamRuntimePin } from '../../runtime/minimax-bot-host/upstream-runtime-pin-check.mjs';
import { UPSTREAM_RUNTIME_COMMIT } from '../../runtime/minimax-bot-host/upstream-runtime-pin.mjs';

const root = dirname(fileURLToPath(import.meta.url));
const source = process.argv[2] && resolve(process.argv[2]);
if (!source || !existsSync(join(source, '.git'))) throw Error('A clean pinned Git checkout is required');
const assertPin = () => {
  const result = checkUpstreamRuntimePin({ sourceDir: source });
  if (!result.ok) throw Error(result.errors.join('\n'));
};
assertPin();
const sdkPackage = JSON.parse(readFileSync(join(source, 'third_party/pi-mono/packages/ai/package.json')));
const probePackage = JSON.parse(readFileSync(join(root, 'package.json')));
for (const [name, version] of Object.entries(probePackage.dependencies)) {
  if (sdkPackage.dependencies[name] !== version) throw Error('Provider SDK dependency differs from pinned upstream');
}
if (JSON.parse(readFileSync(join(source, 'package.json'))).devDependencies.esbuild !== probePackage.devDependencies.esbuild) {
  throw Error('Bundler version differs from pinned upstream');
}
const output = join(root, '.build');
mkdirSync(output, { recursive: true });
const manifest = { commit: UPSTREAM_RUNTIME_COMMIT, inputs: {} };
for (const format of ['openai-completions', 'openai-responses', 'anthropic', 'google']) {
  const result = await build({
    absWorkingDir: source, entryPoints: [`third_party/pi-mono/packages/ai/src/providers/${format}.ts`],
    bundle: true, packages: 'external', platform: 'node', format: 'esm', target: 'node24', metafile: true,
    outfile: join(output, `${format}.mjs`), logLevel: 'silent',
  });
  for (const input of Object.keys(result.metafile.inputs)) {
    const path = resolve(source, input), name = relative(source, path);
    if (name.startsWith('..') || isAbsolute(name)) throw Error('Bundle input outside pinned checkout');
    manifest.inputs[name] = createHash('sha256').update(readFileSync(path)).digest('hex');
  }
}
assertPin();
writeFileSync(join(output, 'source-inputs.json'), JSON.stringify(manifest, null, 2) + '\n');
console.log(`Prepared four unchanged pi-ai providers from ${UPSTREAM_RUNTIME_COMMIT}`);
