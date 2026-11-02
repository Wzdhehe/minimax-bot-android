// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Wzdhehe
//
// Verifies that the upstream runtime pin is consistent everywhere it appears:
//
//   1. upstream-runtime-pin.mjs      - the machine-readable pin
//   2. docs/UPSTREAM-RUNTIME.md       - the human-readable pin document
//   3. the WebUI checkout under test  - the tree the host actually binds to
//
// A mismatch is a hard failure. Bumping the pin without re-running the
// acceptance commands therefore fails CI and the local smoke alike.
//
// Usage:
//   node upstream-runtime-pin-check.mjs [--source <webui-checkout>]
//
// Exit codes: 0 = consistent, 1 = inconsistent (or unreadable pin).

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, realpathSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  UPSTREAM_RUNTIME_COMMIT,
  UPSTREAM_RUNTIME_COMMIT_DATE,
  UPSTREAM_RUNTIME_CONTRACT_FILES,
  UPSTREAM_RUNTIME_LICENSE,
  UPSTREAM_RUNTIME_PIN,
  UPSTREAM_RUNTIME_REPOSITORY,
  UPSTREAM_RUNTIME_REPOSITORY_URL,
  UPSTREAM_RUNTIME_UPSTREAM,
  UPSTREAM_RUNTIME_UPSTREAM_URL,
} from './upstream-runtime-pin.mjs';

const scriptDir = dirname(fileURLToPath(import.meta.url));

/** Repository root: dsh-go/runtime/minimax-bot-host -> dsh-go/runtime -> dsh-go -> root. */
export const repoRoot = resolve(scriptDir, '..', '..', '..');

/** The pin document that must repeat the pin verbatim. */
export const pinDocumentPath = join(repoRoot, 'docs', 'UPSTREAM-RUNTIME.md');

const PIN_FIELDS = Object.freeze([
  ['repository', UPSTREAM_RUNTIME_REPOSITORY],
  ['repositoryUrl', UPSTREAM_RUNTIME_REPOSITORY_URL],
  ['upstream', UPSTREAM_RUNTIME_UPSTREAM],
  ['upstreamUrl', UPSTREAM_RUNTIME_UPSTREAM_URL],
  ['commit', UPSTREAM_RUNTIME_COMMIT],
  ['commitDate', UPSTREAM_RUNTIME_COMMIT_DATE],
  ['license', UPSTREAM_RUNTIME_LICENSE],
]);

/** Extracts the machine-readable pin block from the pin document. */
export function readDocumentPin(documentText) {
  const block = documentText.match(/<!--\s*pin:begin\s*-->[\s\S]*?```json\s*([\s\S]*?)```[\s\S]*?<!--\s*pin:end\s*-->/);
  if (!block) return null;
  try {
    return JSON.parse(block[1]);
  } catch {
    return null;
  }
}

/**
 * Resolves the commit of a WebUI checkout.
 *
 * A git checkout is read from HEAD. When HEAD cannot be resolved (an exported
 * archive, or a checkout without commits), the commit must be declared through
 * WEBUI_SOURCE_COMMIT, so the caller cannot silently smoke an arbitrary tree.
 */
export function resolveSourceCommit(sourceDir) {
  if (existsSync(join(sourceDir, '.git'))) {
    try {
      const head = execFileSync('git', ['-C', sourceDir, 'rev-parse', 'HEAD'], {
        encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'],
      }).trim();
      if (head) return head;
    } catch {
      // No resolvable HEAD: fall through to the declared commit.
    }
  }
  const declared = process.env.WEBUI_SOURCE_COMMIT?.trim();
  return declared ? declared : null;
}

/**
 * Checks the pin against the document and, when given, against a checkout.
 * Returns { ok, errors, notes } instead of throwing, so callers decide how to report.
 */
export function checkUpstreamRuntimePin({ sourceDir } = {}) {
  const errors = [];
  const notes = [];

  let documentPin = null;
  if (!existsSync(pinDocumentPath)) {
    errors.push(`pin document is missing: ${pinDocumentPath}`);
  } else {
    const documentText = readFileSync(pinDocumentPath, 'utf8');
    documentPin = readDocumentPin(documentText);
    if (!documentPin) {
      errors.push(`pin document has no readable 'pin:begin ... pin:end' JSON block: ${pinDocumentPath}`);
    } else {
      for (const [field, expected] of PIN_FIELDS) {
        if (documentPin[field] !== expected) {
          errors.push(
            `pin document '${field}' is ${JSON.stringify(documentPin[field])}, expected ${JSON.stringify(expected)}`,
          );
        }
      }
    }
  }

  if (!errors.length) {
    notes.push(`pin document matches the pin file: ${pinDocumentPath}`);
  }

  if (sourceDir) {
    const source = resolve(sourceDir);
    if (!existsSync(source)) {
      errors.push(`WebUI source does not exist: ${source}`);
    } else {
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

      for (const contractFile of UPSTREAM_RUNTIME_CONTRACT_FILES) {
        if (!existsSync(join(source, contractFile))) {
          errors.push(`contract file is missing from the pinned tree: ${contractFile}`);
        }
      }
      if (!errors.length) {
        notes.push(`all ${UPSTREAM_RUNTIME_CONTRACT_FILES.length} contract files are present`);
      }
    }
  }

  return { ok: errors.length === 0, errors, notes, documentPin, pin: UPSTREAM_RUNTIME_PIN };
}

function main(argv) {
  let sourceDir;
  for (let index = 0; index < argv.length; index += 1) {
    if (argv[index] === '--source') {
      sourceDir = argv[index + 1];
      index += 1;
      if (!sourceDir) {
        console.error('FAIL: --source needs a directory');
        return 1;
      }
    } else {
      console.error(`FAIL: unknown argument ${argv[index]}`);
      return 1;
    }
  }

  const result = checkUpstreamRuntimePin({ sourceDir });
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
}
