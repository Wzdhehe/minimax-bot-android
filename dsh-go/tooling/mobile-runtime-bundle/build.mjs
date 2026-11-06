// SPDX-License-Identifier: AGPL-3.0-only
// Bundle JS dependencies for Android. No desktop native binary is copied.
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync, mkdirSync, realpathSync, readdirSync } from 'node:fs';
import { dirname, join, resolve, relative, isAbsolute, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { isBuiltin } from 'node:module';
import { UPSTREAM_RUNTIME_PIN } from '../../runtime/minimax-bot-host/upstream-runtime-pin.mjs';
import { checkUpstreamRuntimePin } from '../../runtime/minimax-bot-host/upstream-runtime-pin-check.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const [sourceInput, bundleInput, esbuildInput, outputInput, ...extra] = process.argv.slice(2);
if (!sourceInput || !bundleInput || !esbuildInput || !outputInput || extra.length) throw Error('Expected <pinned-checkout> <verified-partial-bundle> <esbuild-module> <new-output>');
const bundle = realpathSync(bundleInput), output = resolve(outputInput);
if (existsSync(output)) throw Error('Refusing to replace an existing mobile bundle');
const source = realpathSync(sourceInput);
const insideSource = relative(source, output);
if (insideSource === '' || (!isAbsolute(insideSource) && insideSource !== '..' && !insideSource.startsWith('..' + sep)) || !checkUpstreamRuntimePin({ sourceDir: source, bundleMetafile: join(bundle, 'metafile.json') }).ok) throw Error('runtime_source_pin_invalid');
const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
const manifest = JSON.parse(readFileSync(join(bundle, 'manifest.json')));
if (manifest.upstream.commit !== UPSTREAM_RUNTIME_PIN.commit || manifest.upstream.tree !== UPSTREAM_RUNTIME_PIN.tree) throw Error('runtime_pin_mismatch');
const artifact = join(bundle, 'runtime-host.partial.mjs');
if (manifest.artifact.path !== 'runtime-host.partial.mjs' || hash(readFileSync(artifact)) !== manifest.artifact.sha256) throw Error('runtime_bundle_digest_mismatch');
const assets = [];
for (const row of manifest.assets) {
  if (!row.path.startsWith('assets/') || row.path.split('/').some((part) => part === '..' || part === '.')) throw Error('runtime_asset_invalid');
  const bytes = readFileSync(join(bundle, row.path));
  if (bytes.length !== row.bytes || hash(bytes) !== row.sha256) throw Error('runtime_asset_digest_mismatch');
  assets.push({ path: row.path, bytes: row.bytes, sha256: row.sha256, base64: bytes.toString('base64') });
}
const esbuild = await import(pathToFileURL(resolve(esbuildInput)).href);
if (esbuild.version !== '0.28.2') throw Error('esbuild_pin_mismatch');
const dependencies = realpathSync(join(here, 'node_modules'));
const photon = join(dependencies, '@silvia-odwyer/photon-node');
if (JSON.parse(readFileSync(join(photon, 'package.json'))).version !== '0.3.4') throw Error('photon_version_mismatch');
const photonJs = join(photon, 'photon_rs.js'), wasm = readFileSync(join(photon, 'photon_rs_bg.wasm'));
const optionalUnavailable = ['supports-color', 'bufferutil', 'utf-8-validate'];
const plugin = { name: 'mobile-code-assets', setup(build) {
  // These are optional require() calls inside upstream try/catch blocks.
  // Keep their genuine JS fallbacks; never ship a desktop native extension.
  build.onResolve({ filter: /^(supports-color|bufferutil|utf-8-validate)$/ }, (input) => ({ path: input.path, namespace: 'optional-unavailable' }));
  build.onLoad({ filter: /.*/, namespace: 'optional-unavailable' }, () => ({
    contents: "throw Object.assign(new Error('optional_module_not_bundled'),{code:'MODULE_NOT_FOUND'});", loader: 'js' }));
  build.onResolve({ filter: /^@mbot\/compiled-host$/ }, () => ({ path: artifact }));
  build.onLoad({ filter: /photon_rs\.js$/ }, (input) => {
    if (realpathSync(input.path) !== photonJs) return undefined;
    const source = readFileSync(photonJs, 'utf8');
    const original = "const path = require('path').join(__dirname, 'photon_rs_bg.wasm');\nconst bytes = require('fs').readFileSync(path);";
    if (source.split(original).length !== 2) throw Error('photon_asset_loader_changed');
    return { contents: source.replace(original, `const bytes = Buffer.from(${JSON.stringify(wasm.toString('base64'))}, 'base64');`),
      loader: 'js', resolveDir: photon };
  });
} };
const settings = { absWorkingDir: here,
  entryPoints: [resolve(here, '../../runtime/minimax-bot-host/mobile-runtime-entry.mjs')],
  outfile: join(output, 'runtime.mjs'), bundle: true, platform: 'node', format: 'esm', target: 'node24',
  nodePaths: [dependencies], plugins: [plugin], minify: true, legalComments: 'eof', write: false, metafile: true,
  banner: { js: "import{createRequire as __mbotRequire}from'node:module';const require=__mbotRequire(import.meta.url);const __dirname='/mbot-code',__filename='/mbot-code/runtime.mjs';" },
  logLevel: 'silent',
};
const built = await esbuild.build(settings);
const filesystem = await esbuild.build({ ...settings,
  entryPoints: [resolve(here, '../../runtime/minimax-bot-host/mobile-filesystem.mjs')],
  outfile: join(output, 'filesystem.mjs'),
});
const unresolved = [built, filesystem].flatMap((build) => Object.values(build.metafile.outputs)
  .flatMap((item) => item.imports)).filter((item) => !isBuiltin(item.path));
if (unresolved.length || built.outputFiles.length !== 1) throw Error('mobile_bundle_has_unresolved_packages:' + unresolved.map((item) => item.path).join(','));
const sourceBytes = built.outputFiles[0].contents;
// Keep the original licence texts of every bundled dependency in the APK.
// Full texts accompany esbuild's retained legal comments, including packages
// whose entry module itself does not contain a complete licence notice.
const packages = new Map();
for (const input of [built, filesystem].flatMap((item) => Object.keys(item.metafile.inputs))) {
  if (input.startsWith('optional-unavailable:')) continue;
  let directory = dirname(resolve(here, input));
  while (directory !== dirname(directory)) {
    if (existsSync(join(directory, 'package.json'))) {
      const entry = JSON.parse(readFileSync(join(directory, 'package.json')));
      if (entry.name && entry.version) {
        if (directory.includes('node_modules') && !packages.has(entry.name)) packages.set(entry.name, { directory, entry });
        break;
      }
    }
    directory = dirname(directory);
  }
}
const notices = [];
const licenseOverrides = JSON.parse(readFileSync(join(here, 'license-overrides.json'), 'utf8'));
for (const [name, { directory, entry }] of [...packages].sort(([a], [b]) => a.localeCompare(b))) {
  const candidates = readdirSync(directory).filter((name) => /^(licen[cs]e|copying|notice)([.-].*)?$/iu.test(name));
  const texts = candidates.map((name) => readFileSync(join(directory, name), 'utf8'));
  // Some npm packages publish their full MIT text inside README rather than
  // a separate LICENSE (for example data-uri-to-buffer 4.0.1).
  if (!texts.length) {
    for (const file of readdirSync(directory).filter((name) => /^readme([.-].*)?$/iu.test(name))) {
      const text = readFileSync(join(directory, file), 'utf8');
      if (/copyright/iu.test(text) && /permission is hereby granted/iu.test(text)) texts.push(text);
    }
  }
  if (!texts.length && entry.license === 'Apache-2.0') {
    // The complete standard Apache terms; retain the package's own author
    // declaration too. These npm packages omit a separate LICENSE file.
    texts.push(JSON.stringify({ author: entry.author, repository: entry.repository }),
      readFileSync(join(dependencies, '@opentelemetry/api/LICENSE'), 'utf8'));
  }
  const override = licenseOverrides[name];
  if (!texts.length && override && override.version === entry.version) texts.push(`${override.source}\n${override.text}`);
  if (!texts.length) throw Error('dependency_licence_missing:' + name);
  notices.push(`${name}@${entry.version} (${typeof entry.license === 'string' ? entry.license : 'see text'})\n${texts.join('\n')}`);
}
for (const [name, file] of [['DSH Go', resolve(here, '../../LICENSE')], ['MiniMax Bot', resolve(here, '../../runtime/vendor/minimax-bot/LICENSE')], ['Pinned mcode WebUI runtime', join(source, 'LICENSE')]]) {
  notices.push(`${name}\n${readFileSync(file, 'utf8')}`);
}
mkdirSync(output);
writeFileSync(join(output, 'runtime.mjs'), sourceBytes, { flag: 'wx' });
writeFileSync(join(output, 'filesystem.mjs'), filesystem.outputFiles[0].contents, { flag: 'wx' });
writeFileSync(join(output, 'resources.json'), JSON.stringify(assets), { flag: 'wx' });
writeFileSync(join(output, 'THIRD-PARTY-NOTICES.txt'), notices.join('\n\n========================================\n\n'), { flag: 'wx' });
const inputs = Object.fromEntries([...new Set([built, filesystem].flatMap((item) => Object.keys(item.metafile.inputs)))].map((name) => {
  if (name.startsWith('optional-unavailable:')) return [name, 'explicit-upstream-fallback'];
  const file = resolve(here, name);
  return [relative(here, file), hash(readFileSync(file))];
}));
writeFileSync(join(output, 'mobile-manifest.json'), JSON.stringify({ schemaVersion: 1, upstream: manifest.upstream,
  runtimeSha256: hash(sourceBytes), resourcesSha256: hash(readFileSync(join(output, 'resources.json'))),
  filesystemSha256: hash(filesystem.outputFiles[0].contents),
  noticesSha256: hash(readFileSync(join(output, 'THIRD-PARTY-NOTICES.txt'))),
  sourceBundleSha256: manifest.artifact.sha256, dependencyLockSha256: hash(readFileSync(join(here, 'package-lock.json'))),
  photonWasmSha256: hash(wasm), inputs, status: 'product-assembly-candidate', androidVerified: false,
  optionalUnavailable,
}, null, 2) + '\n', { flag: 'wx' });
console.log(`Built mobile runtime: ${sourceBytes.length} JS bytes; ${assets.length} resources; no external npm packages or desktop native binaries.`);
