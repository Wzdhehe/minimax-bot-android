// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Wzdhehe
//
// Verifies that the upstream runtime pin is consistent everywhere it appears,
// and that the code the smoke actually executes is the code that was pinned.
//
//   1. upstream-runtime-pin.mjs      - the machine-readable pin
//   2. docs/UPSTREAM-RUNTIME.md       - the human-readable pin document
//   3. the WebUI checkout under test  - the tree the host actually binds to
//   4. the esbuild metafile           - the files that really entered the bundle
//
// A mismatch is a hard failure. Bumping the pin without re-running the
// acceptance commands therefore fails CI and the local smoke alike.
//
// Usage:
//   node upstream-runtime-pin-check.mjs [--source <webui-checkout>]
//                                       [--doc <pin-document>]
//                                       [--bundle-metafile <esbuild metafile>]
//
// Exit codes: 0 = consistent, 1 = inconsistent, 2 = the check itself failed.

import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, realpathSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  UPSTREAM_RUNTIME_BUNDLE_SHA256,
  UPSTREAM_RUNTIME_COMMIT,
  UPSTREAM_RUNTIME_COMMIT_DATE,
  UPSTREAM_RUNTIME_COMMIT_SUBJECT,
  UPSTREAM_RUNTIME_CONTRACT_ALIASES,
  UPSTREAM_RUNTIME_CONTRACT_FILES,
  UPSTREAM_RUNTIME_LICENSE,
  UPSTREAM_RUNTIME_PIN,
  UPSTREAM_RUNTIME_REPOSITORY,
  UPSTREAM_RUNTIME_REPOSITORY_URL,
  UPSTREAM_RUNTIME_TREE,
  UPSTREAM_RUNTIME_UPSTREAM,
  UPSTREAM_RUNTIME_UPSTREAM_URL,
} from './upstream-runtime-pin.mjs';

const scriptDir = dirname(fileURLToPath(import.meta.url));

/** Repository root: dsh-go/runtime/minimax-bot-host -> dsh-go/runtime -> dsh-go -> root. */
export const repoRoot = resolve(scriptDir, '..', '..', '..');

/** The pin document that must repeat the pin verbatim. */
export const pinDocumentPath = join(repoRoot, 'docs', 'UPSTREAM-RUNTIME.md');

/** Compares two plain JSON-ish values deeply. */
function deepEqual(a, b) {
  if (a === b) return true;
  if (typeof a !== typeof b) return false;
  if (a === null || b === null) return false;
  if (Array.isArray(a) !== Array.isArray(b)) return false;
  if (typeof a !== 'object') return false;
  const aKeys = Object.keys(a).sort();
  const bKeys = Object.keys(b).sort();
  if (aKeys.length !== bKeys.length) return false;
  if (aKeys.some((key, index) => key !== bKeys[index])) return false;
  return aKeys.every((key) => deepEqual(a[key], b[key]));
}

/**
 * Extracts the machine-readable pin block from the pin document.
 *
 * Returns { pin } on success, or { error } describing why the block is
 * unusable. "No block" and "a block that cannot be parsed" are different
 * failures and are reported differently.
 */
export function readDocumentPin(documentText) {
  const block = documentText.match(/<!--\s*pin:begin\s*-->[\s\S]*?```json\s*([\s\S]*?)```[\s\S]*?<!--\s*pin:end\s*-->/);
  if (!block) {
    const hasBegin = /<!--\s*pin:begin\s*-->/.test(documentText);
    const hasEnd = /<!--\s*pin:end\s*-->/.test(documentText);
    if (hasBegin || hasEnd) {
      return { error: 'the pin block markers are present but the fenced json block is missing or malformed' };
    }
    return { error: 'no pin:begin / pin:end block found' };
  }
  const jsonText = block[1];
  let parsed;
  try {
    parsed = JSON.parse(jsonText);
  } catch (error) {
    return { error: `the pin block is not valid json: ${error.message}` };
  }
  // JSON.parse keeps the last of a repeated key, so a duplicated key could
  // hide a contradicting value in the document text. Refuse duplicated keys.
  const topLevelKeys = Object.keys(parsed);
  for (const key of topLevelKeys) {
    const occurrences = (jsonText.match(new RegExp(`"${key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}"\\s*:`, 'g')) || []).length;
    if (occurrences > 1) {
      return { error: `the pin block repeats the key '${key}'` };
    }
  }
  return { pin: parsed };
}

/**
 * Checks the pin's own internal consistency: the contract list must not be
 * empty, every contract file must have a pinned fingerprint, and the alias
 * map must cover exactly the contract list.
 */
export function contractIntegrityErrors({
  contractFiles = UPSTREAM_RUNTIME_CONTRACT_FILES,
  bundleSha256 = UPSTREAM_RUNTIME_BUNDLE_SHA256,
  aliases = UPSTREAM_RUNTIME_CONTRACT_ALIASES,
} = {}) {
  const errors = [];
  if (!Array.isArray(contractFiles) || contractFiles.length === 0) {
    errors.push('the pinned contract file list is empty');
  }
  const bundlePaths = Object.keys(bundleSha256 || {});
  if (bundlePaths.length === 0) {
    errors.push('the pinned bundle fingerprint list is empty');
  }
  for (const path of contractFiles) {
    if (!bundlePaths.includes(path)) {
      errors.push(`contract file has no pinned fingerprint: ${path}`);
    }
  }
  const aliasPaths = aliases.map((entry) => entry.path);
  if (!deepEqual(aliasPaths, [...contractFiles])) {
    errors.push('the alias map and the contract file list disagree');
  }
  return errors;
}

/** Runs git in a directory and returns { ok, stdout, stderr }. */
function runGit(sourceDir, args) {
  try {
    const stdout = execFileSync('git', ['-C', sourceDir, ...args], {
      encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'],
    });
    return { ok: true, stdout: stdout.trim(), stderr: '' };
  } catch (error) {
    return {
      ok: false,
      stdout: '',
      stderr: (error.stderr || error.message || '').toString().trim(),
    };
  }
}

/**
 * Verify that a file in the checkout is byte-identical to the same path in a
 * pinned tree.
 *
 * A tree object is a content address, so a matching blob id proves the bytes
 * are the pinned bytes. That lets a bundle carry the upstream files it really
 * needs without a hand-maintained fingerprint list per file: every upstream
 * input is checked against the pinned tree instead of against a fixed set.
 *
 * Returns null when the file matches, or a message describing the refusal.
 */
export function pinnedTreeFileError({ source, tree, path }) {
  if (typeof source !== 'string' || !source) {
    return 'the upstream file cannot be verified without a source checkout';
  }
  if (typeof tree !== 'string' || !/^[0-9a-f]{40,64}$/.test(tree)) {
    return 'the pinned tree is not a tree object';
  }
  // A git tree path is always slash separated, whatever the host separator is.
  const treePath = typeof path === 'string' ? path.split(/[\\/]/).join('/') : '';
  if (!treePath || treePath.startsWith('/') || treePath.split('/').includes('..')) {
    return `the upstream file path is not a tree path: ${path}`;
  }
  const blob = runGit(source, ['rev-parse', `${tree}:${treePath}`]);
  if (!blob.ok) return `the upstream file is not in the pinned tree: ${treePath}`;
  const actual = runGit(source, ['hash-object', join(source, treePath)]);
  if (!actual.ok) return `cannot hash the upstream file: ${treePath}`;
  if (actual.stdout.trim() !== blob.stdout.trim()) {
    return `the upstream file does not match the pinned tree bytes: ${treePath}`;
  }
  return null;
}

/**
 * Resolves the commit of a WebUI checkout.
 *
 * A git checkout is read from HEAD. When HEAD cannot be resolved (an exported
 * archive, or a checkout without commits), the commit must be declared through
 * WEBUI_SOURCE_COMMIT, so the caller cannot silently smoke an arbitrary tree.
 * The declared value only states identity; the bytes are bound by the
 * fingerprint check either way.
 */
export function resolveSourceCommit(sourceDir) {
  if (existsSync(join(sourceDir, '.git'))) {
    const head = runGit(sourceDir, ['rev-parse', 'HEAD']);
    if (head.ok && head.stdout) return head.stdout;
  }
  const declared = process.env.WEBUI_SOURCE_COMMIT?.trim();
  return declared ? declared : null;
}

/** SHA-256 of a file, or null when it cannot be read. */
function sha256Of(filePath) {
  try {
    return createHash('sha256').update(readFileSync(filePath)).digest('hex');
  } catch {
    return null;
  }
}

/**
 * Checks the pin against the document and, when given, against a checkout and
 * a bundle metafile.
 *
 * Returns { ok, errors, notes } instead of throwing, so callers decide how to report.
 */
export function checkUpstreamRuntimePin({
  sourceDir,
  docPath = pinDocumentPath,
  bundleMetafile,
} = {}) {
  const errors = [];
  const notes = [];

  // 1. The pin's own consistency.
  for (const error of contractIntegrityErrors()) errors.push(error);

  // 2. The document must repeat the pin exactly.
  if (!existsSync(docPath)) {
    errors.push(`pin document is missing: ${docPath}`);
  } else {
    const documentText = readFileSync(docPath, 'utf8');
    const result = readDocumentPin(documentText);
    if (result.error) {
      errors.push(`pin document ${docPath}: ${result.error}`);
    } else if (!deepEqual(result.pin, UPSTREAM_RUNTIME_PIN)) {
      const pinKeys = Object.keys(UPSTREAM_RUNTIME_PIN);
      const documentKeys = Object.keys(result.pin);
      for (const key of pinKeys) {
        if (!documentKeys.includes(key)) {
          errors.push(`pin document is missing the field '${key}'`);
        } else if (!deepEqual(result.pin[key], UPSTREAM_RUNTIME_PIN[key])) {
          errors.push(`pin document '${key}' is ${JSON.stringify(result.pin[key])}, expected ${JSON.stringify(UPSTREAM_RUNTIME_PIN[key])}`);
        }
      }
      for (const key of documentKeys) {
        if (!pinKeys.includes(key)) {
          errors.push(`pin document has the extra field '${key}'`);
        }
      }
    } else {
      notes.push(`pin document matches the pin file: ${docPath}`);
    }
  }

  if (sourceDir) {
    const source = resolve(sourceDir);
    if (!existsSync(source)) {
      errors.push(`WebUI source does not exist: ${source}`);
    } else {
      const isGitCheckout = existsSync(join(source, '.git'));

      // 3. Identity: which commit is this?
      const commit = resolveSourceCommit(source);
      if (!commit) {
        errors.push(
          `cannot determine the commit of ${source}: no resolvable git HEAD and WEBUI_SOURCE_COMMIT is unset`,
        );
      } else if (commit !== UPSTREAM_RUNTIME_COMMIT) {
        errors.push(`WebUI source ${source} is at ${commit}, expected ${UPSTREAM_RUNTIME_COMMIT}`);
      } else {
        notes.push(`WebUI source is at the pinned commit ${commit}`);
      }

      // 4. Content: the tree object of that commit.
      if (isGitCheckout) {
        const tree = runGit(source, ['rev-parse', 'HEAD^{tree}']);
        if (!tree.ok) {
          errors.push(`cannot resolve the tree of ${source}: ${tree.stderr || 'git rev-parse HEAD^{tree} failed'}`);
        } else if (tree.stdout !== UPSTREAM_RUNTIME_TREE) {
          errors.push(`WebUI source tree is ${tree.stdout}, expected ${UPSTREAM_RUNTIME_TREE}`);
        } else {
          notes.push(`WebUI source tree matches the pinned tree ${tree.stdout}`);
        }

        // 5. The working tree must be the committed tree: no local edits.
        const status = runGit(source, ['status', '--porcelain']);
        if (!status.ok) {
          errors.push(`cannot read the working tree status of ${source}: ${status.stderr || 'git status failed'}`);
        } else if (status.stdout) {
          const changed = status.stdout.split('\n').slice(0, 5).join('; ');
          errors.push(`WebUI source has local modifications, so the smoke would not run the pinned code: ${changed}`);
        } else {
          notes.push('WebUI source working tree is clean');
        }
      }

      // 6. Content: the bytes of every file that enters the bundle.
      for (const [path, expected] of Object.entries(UPSTREAM_RUNTIME_BUNDLE_SHA256)) {
        const filePath = join(source, path);
        if (!existsSync(filePath)) {
          errors.push(`pinned upstream file is missing from the source: ${path}`);
          continue;
        }
        const actual = sha256Of(filePath);
        if (actual !== expected) {
          errors.push(`pinned upstream file does not match the pinned bytes: ${path} (sha256 ${actual}, expected ${expected})`);
        }
      }
      if (!errors.length) {
        notes.push(`all ${Object.keys(UPSTREAM_RUNTIME_BUNDLE_SHA256).length} pinned upstream files match their fingerprints`);
      }
    }
  }

  // 7. What the bundle really consumed.
  if (bundleMetafile) {
    if (!existsSync(bundleMetafile)) {
      errors.push(`bundle metafile does not exist: ${bundleMetafile}`);
    } else {
      let metafile;
      try {
        metafile = JSON.parse(readFileSync(bundleMetafile, 'utf8'));
      } catch (error) {
        errors.push(`bundle metafile is not valid json: ${error.message}`);
      }
      if (metafile) {
        const base = process.cwd();
        const source = sourceDir ? resolve(sourceDir) : null;
        const upstreamInputs = new Set();
        for (const inputPath of Object.keys(metafile.inputs || {})) {
          const absolute = resolve(base, inputPath);
          if (source && (absolute === source || absolute.startsWith(source + sep))) {
            // The upstream tree addresses files with slashes on every host.
            upstreamInputs.add(relative(source, absolute).split(/[\\/]/).join('/'));
          }
        }
        const pinnedPaths = Object.keys(UPSTREAM_RUNTIME_BUNDLE_SHA256);
        for (const path of upstreamInputs) {
          if (!pinnedPaths.includes(path)) {
            // A file outside the hand-listed fingerprints is still allowed when
            // it is byte-identical to the pinned tree: the tree is the content
            // address of the whole pinned upstream, so this proves the same
            // thing the fingerprints prove, for every file the bundle needs.
            const treeError = pinnedTreeFileError({ source, tree: UPSTREAM_RUNTIME_TREE, path });
            if (treeError) errors.push(treeError);
          }
        }
        for (const path of pinnedPaths) {
          if (!upstreamInputs.has(path)) {
            errors.push(`a pinned upstream file did not enter the bundle, so the pin is stale: ${path}`);
          }
        }
        if (!errors.length) {
          notes.push(`the bundle consumed ${upstreamInputs.size} verified upstream files (${pinnedPaths.length} explicit fingerprints; remaining inputs checked against the pinned tree)`);
        }
      }
    }
  }

  return { ok: errors.length === 0, errors, notes, pin: UPSTREAM_RUNTIME_PIN };
}

function parseArgs(argv) {
  const options = {};
  for (let index = 0; index < argv.length; index += 1) {
    const flag = argv[index];
    const value = argv[index + 1];
    if (flag === '--source') { options.sourceDir = value; index += 1; }
    else if (flag === '--doc') { options.docPath = value; index += 1; }
    else if (flag === '--bundle-metafile') { options.bundleMetafile = value; index += 1; }
    else { return { error: `unknown argument ${flag}` }; }
    if (options.sourceDir === undefined && flag === '--source') return { error: '--source needs a directory' };
    if (options.docPath === undefined && flag === '--doc') return { error: '--doc needs a file' };
    if (options.bundleMetafile === undefined && flag === '--bundle-metafile') return { error: '--bundle-metafile needs a file' };
  }
  return options;
}

function main(argv) {
  const parsed = parseArgs(argv);
  if (parsed.error) {
    console.error(`FAIL: ${parsed.error}`);
    return 1;
  }

  const result = checkUpstreamRuntimePin(parsed);
  for (const note of result.notes) console.log(`OK: ${note}`);
  for (const error of result.errors) console.error(`FAIL: ${error}`);
  if (!result.ok) {
    console.error('FAIL: upstream runtime pin is inconsistent; see docs/UPSTREAM-RUNTIME.md');
    return 1;
  }
  console.log('PASS: upstream runtime pin is consistent');
  return 0;
}

/**
 * True when this module is the process entry point.
 *
 * The comparison goes through realpathSync on both sides: a checkout reached
 * through a symlinked or mount-aliased path has a different argv[1] than its
 * import.meta.url, and comparing them raw would silently skip the check.
 */
function isEntryPoint() {
  const entry = process.argv[1];
  if (!entry) return false;
  try {
    return realpathSync(entry) === realpathSync(fileURLToPath(import.meta.url));
  } catch {
    return false;
  }
}

if (isEntryPoint()) {
  process.exit(main(process.argv.slice(2)));
} else if (!process.argv[1]) {
  // No script argument: the module was pulled in through `node -e` or stdin,
  // where nobody can read the check's result. Say so instead of exiting 0 in
  // silence, which would look like a passing check.
  console.error('FAIL: upstream-runtime-pin-check.mjs must be run as a script; `node -e` cannot carry its result');
  process.exit(2);
}
