// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Wzdhehe
// Compile the real pinned host graph without executing it or installing native
// dependencies. The result is partial: Android isolation adapters are required.
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { isBuiltin } from 'node:module';
import { tmpdir } from 'node:os';
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { checkUpstreamRuntimePin } from './upstream-runtime-pin-check.mjs';
import { UPSTREAM_RUNTIME_PIN } from './upstream-runtime-pin.mjs';
import { runtimeBuildWiring } from './runtime-build-plugin.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const options = {};
for (let i = 2; i < process.argv.length; i += 2) {
  const flag = process.argv[i];
  if (!['--source', '--esbuild-module', '--out-dir'].includes(flag) || !process.argv[i + 1] || options[flag]) {
    throw new Error('Expected --source <checkout> --esbuild-module <module> --out-dir <new-directory>');
  }
  options[flag] = resolve(process.argv[i + 1]);
}
if (Object.keys(options).length !== 3) throw new Error('Source, esbuild module and new output directory are required');
const source = realpathSync(options['--source']);
// Canonicalize the existing parent as well, so a parent symlink cannot put
// generated output back into the supposedly clean upstream checkout.
const outDir = join(realpathSync(dirname(options['--out-dir'])), basename(options['--out-dir']));
if (existsSync(outDir)) throw new Error('Output directory must not already exist');
// Never create untracked build output in the checkout we are verifying.
const outputRelative = relative(source, outDir);
if (!isAbsolute(outputRelative) && outputRelative !== '..' && !outputRelative.startsWith(`..${sep}`)) {
  throw new Error('Output directory must be outside the upstream checkout');
}
function requirePin(extra = {}) {
  const checked = checkUpstreamRuntimePin({ sourceDir: source, ...extra });
  if (!checked.ok) throw new Error(`Pinned runtime check failed:\n${checked.errors.join('\n')}`);
  return checked;
}
requirePin();
const esbuild = await import(pathToFileURL(options['--esbuild-module']).href);
const upstreamPackage = JSON.parse(readFileSync(join(source, 'package.json'), 'utf8'));
if (esbuild.version !== upstreamPackage.devDependencies.esbuild) throw new Error('esbuild version must match the pinned upstream');
// Reuse the fork's source resolver instead of inventing a second export map.
const { createWorkspaceSourcesPlugin } = await import(pathToFileURL(join(source, 'scripts/lib/workspace-sources-plugin.mjs')).href);
const extraction = JSON.parse(readFileSync(join(source, 'release/extraction.json'), 'utf8'));
const packages = new Map(extraction.packageRoots.map((directory) => {
  const manifest = JSON.parse(readFileSync(join(source, directory, 'package.json'), 'utf8'));
  return [manifest.name, { directory, manifest }];
}));
const artifactName = 'runtime-host.partial.mjs';
const wiring = runtimeBuildWiring(source);
const built = await esbuild.build({
  absWorkingDir: source,
  entryPoints: [join(here, 'runtime-bundle-entry.mjs')],
  outfile: join(outDir, artifactName),
  bundle: true, platform: 'node', format: 'esm', target: 'node24',
  packages: 'external', write: false, metafile: true,
  alias: wiring.alias,
  plugins: [wiring.plugin, createWorkspaceSourcesPlugin(packages, source)],
  logLevel: 'silent',
});
// Relative metafile paths are relative to absWorkingDir, not the invoker's cwd.
// Normalize them before handing the graph to the existing byte-level checker.
const metafile = { ...built.metafile, inputs: Object.fromEntries(Object.entries(built.metafile.inputs)
  .map(([path, input]) => [resolve(source, path), input])) };
const scratch = mkdtempSync(join(tmpdir(), 'mbot-runtime-build-'));
let ownsOutput = false;
try {
  const metaPath = join(scratch, 'metafile.json');
  writeFileSync(metaPath, JSON.stringify(metafile));
  const checked = requirePin({ bundleMetafile: metaPath });
  const upstreamInputs = Object.keys(metafile.inputs).map((path) => relative(source, path))
    .filter((path) => !isAbsolute(path) && path !== '..' && !path.startsWith('../') && !path.startsWith('..\\'))
    .map((path) => path.replaceAll('\\', '/')).sort();
  for (const required of [
    'packages/webui/src/runtime/assembly.ts',
    'packages/local-runtime-v2/src/services.ts',
    'packages/local-runtime-v2/src/service/miniapp/agent/session-ports.ts',
  ]) {
    if (!upstreamInputs.includes(required)) throw new Error(`Real runtime input missing: ${required}`);
  }
  if (!Object.hasOwn(metafile.inputs, join(here, 'runtime-miniapp-index.mjs'))) {
    throw new Error('Pinned RuntimeServices no longer reaches the trusted Bot gateway seam');
  }
  const imports = [...new Set(Object.values(metafile.outputs).flatMap((output) =>
    output.imports.filter((entry) => entry.external).map((entry) => entry.path)))].sort();
  const workspaceNames = new Set(packages.keys());
  for (const specifier of imports) {
    const name = specifier.startsWith('@') ? specifier.split('/').slice(0, 2).join('/') : specifier.split('/')[0];
    if (workspaceNames.has(name) || specifier.startsWith('.') || isAbsolute(specifier)) {
      throw new Error(`Unbundled runtime source dependency: ${specifier}`);
    }
  }
  if (built.outputFiles.length !== 1) throw new Error('Expected one partial host bundle');
  const bytes = built.outputFiles[0].contents;
  const report = {
    schemaVersion: 1,
    status: 'build-graph-only', androidReady: false,
    upstream: { repository: UPSTREAM_RUNTIME_PIN.repository, commit: UPSTREAM_RUNTIME_PIN.commit, tree: UPSTREAM_RUNTIME_PIN.tree },
    esbuildVersion: esbuild.version,
    hostAdaptations: wiring.adaptations,
    bundlerWarnings: built.warnings.map(({ id, text }) => ({ id, text })),
    artifact: { path: artifactName, bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') },
    upstreamInputs,
    nodeBuiltins: imports.filter(isBuiltin),
    externalPackages: imports.filter((specifier) => !isBuiltin(specifier)),
    // createRequire(variable) and computed imports are opaque to esbuild.
    // This is a reviewed example, NOT an exhaustive runtime dependency lock.
    knownOpaqueLoads: ['better-sqlite3 (createRequire in upstream SQLite drivers)'],
    limitations: [
      'Third-party packages are external and not installed, locked or executed by this build.',
      'Computed loads, runtime assets and import.meta.url-relative resources need separate packaging review.',
      'Bot caller registration is wired at the pinned gateway constructor; full runtime boot and Android native model/files/SQLite adapters still need validation.',
      'Do not launch this bundle through the same-UID DSH/PRoot process.',
    ],
  };
  // Publish only after verifying the whole graph; never overwrite caller files.
  mkdirSync(outDir);
  ownsOutput = true;
  writeFileSync(join(outDir, artifactName), bytes, { flag: 'wx' });
  writeFileSync(join(outDir, 'metafile.json'), JSON.stringify(metafile, null, 2), { flag: 'wx' });
  writeFileSync(join(outDir, 'manifest.json'), `${JSON.stringify(report, null, 2)}\n`, { flag: 'wx' });
  execFileSync(process.execPath, ['--check', join(outDir, artifactName)], { stdio: 'pipe' });
  console.log(`Verified ${upstreamInputs.length} pinned upstream inputs; ${report.externalPackages.length} external package specifiers; ${bytes.length} bundle bytes.`);
  console.log('Build graph and syntax passed. Runtime boot, Android and real providers are NOT verified.');
  for (const note of checked.notes) console.log(note);
} catch (error) {
  if (ownsOutput) rmSync(outDir, { recursive: true, force: true });
  throw error;
} finally {
  rmSync(scratch, { recursive: true, force: true });
}
