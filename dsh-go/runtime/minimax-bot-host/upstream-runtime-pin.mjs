// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Wzdhehe
//
// Single source of truth for the upstream MiniMax Code WebUI fork that the
// built-in Bot host binds to.
//
// The host adapter (agent-port.mjs) is a thin layer over the fork's
// process-local agent contract; the upstream gateway smoke
// (run-upstream-gateway-smoke.sh) bundles the fork's real gateway source and
// drives the vendored Bot service through it.
//
// This pin is enforced, not decorative:
//   - upstream-runtime-pin-check.mjs fails when the pin, docs/UPSTREAM-RUNTIME.md
//     and the checked-out WebUI tree disagree, when the tree is dirty, or when
//     any file that enters the bundle does not match its pinned fingerprint;
//   - .github/workflows/bot-host.yml checks the fork out at this commit.
//
// Bumping the pin is a deliberate act: update the constants, update
// docs/UPSTREAM-RUNTIME.md, then re-run the acceptance commands. A pin bump
// that is not re-run fails the check, by design.

/** Fork this project tracks, in the `owner/repo` form used by checkout tools. */
export const UPSTREAM_RUNTIME_REPOSITORY = 'mcode-webui/minimax-code';

/** Fork URL for humans and for the upstream pin document. */
export const UPSTREAM_RUNTIME_REPOSITORY_URL = 'https://github.com/mcode-webui/minimax-code';

/** Official upstream. Fork updates are merged in by the team, never pinned directly. */
export const UPSTREAM_RUNTIME_UPSTREAM = 'MiniMax-AI/minimax-code';

/** Official upstream URL for humans. */
export const UPSTREAM_RUNTIME_UPSTREAM_URL = 'https://github.com/MiniMax-AI/minimax-code';

/** Pinned commit. Move it only together with a re-run of the acceptance commands. */
export const UPSTREAM_RUNTIME_COMMIT = '97818b2890b4acae1c6bd1200a226ff506680628';

/** Committer date of the pinned commit, UTC ISO-8601. */
export const UPSTREAM_RUNTIME_COMMIT_DATE = '2026-10-31T15:27:15Z';

/**
 * Tree object of the pinned commit. It is the content address of the whole
 * upstream tree, so a checkout whose HEAD resolves to this tree is the pinned
 * tree - regardless of how many files it holds.
 */
export const UPSTREAM_RUNTIME_TREE = '5d79407ffcd54715567b49e0fac46c8ccc6e2ec8';

/** Subject line of the pinned commit, so the pin document cannot drift from it silently. */
export const UPSTREAM_RUNTIME_COMMIT_SUBJECT = 'style(webui): refine button states and layout alignment';

/** License of the pinned upstream tree. Verified against the fork's LICENSE at that commit. */
export const UPSTREAM_RUNTIME_LICENSE = 'MIT';

/**
 * Upstream source files the host adapter binds to, with the bundle alias each
 * one is reached through.
 *
 * The smoke aliases these paths into the esbuild bundle. Keeping the alias and
 * the path together means one pin bump moves both, and the check fails fast
 * when a listed file is missing from the pinned tree.
 */
export const UPSTREAM_RUNTIME_CONTRACT_ALIASES = Object.freeze([
  Object.freeze({
    alias: '@mcode-test/agent-gateway',
    path: 'packages/local-runtime-v2/src/service/miniapp/agent/gateway.ts',
  }),
  Object.freeze({
    alias: '@mcode-test/process-local',
    path: 'packages/local-runtime-v2/src/application/session/process-local-miniapp-agent-application.ts',
  }),
  Object.freeze({
    alias: '@mavis/shared/miniapp-surface',
    path: 'packages/shared/src/miniapp-surface.ts',
  }),
]);

/** The upstream paths the host binds to. */
export const UPSTREAM_RUNTIME_CONTRACT_FILES = Object.freeze(
  UPSTREAM_RUNTIME_CONTRACT_ALIASES.map((entry) => entry.path),
);

/**
 * Every upstream file that enters the esbuild bundle, with the SHA-256 of its
 * bytes at the pinned commit.
 *
 * The bundle is built from the checkout's working tree, so the commit sha
 * alone does not say which bytes ran. These fingerprints do: the smoke passes
 * esbuild's metafile to the checker, and the checker requires every upstream
 * input of the bundle to be listed here and to match byte for byte.
 *
 * A pin bump that changes the upstream import graph changes this list. The
 * smoke fails on a stale list instead of silently running new upstream code.
 */
export const UPSTREAM_RUNTIME_BUNDLE_SHA256 = Object.freeze({
  'packages/local-runtime-v2/src/service/miniapp/agent/gateway.ts':
    'e94ce21aa3b8ac944f2a947dd9017fede03aa4fe759776b6f7eae807e4d6b9ca',
  'packages/local-runtime-v2/src/service/miniapp/agent/contracts.ts':
    '91f28385853617ab334eee2e149a8c18e10d14ef89e5851c6a95eb09145e9590',
  'packages/local-runtime-v2/src/service/miniapp/agent/model-selection.ts':
    'ee777ee25dadec09a67cafbfa340a8330e8fa4ae80c80d3aaa71ca7c7fd25258',
  'packages/local-runtime-v2/src/service/miniapp/contracts.ts':
    '810f72467717c4cd6fbbf0caefbcaa15192924f9366a869db841780139704c99',
  'packages/local-runtime-v2/src/application/session/process-local-miniapp-agent-application.ts':
    'd4dc6af1f4a81b7987afd5b8699e2c535375ad49473e8d04a6cb7daecc24ceab',
  'packages/shared/src/miniapp-surface.ts':
    'ece0a8588bb4854083a8de004388cb6cb6c27c4784aa6c7831fb01dd75b7e888',
});

/**
 * The esbuild `--alias:` arguments that bind the upstream contract files of a
 * given WebUI checkout. Keeps the smoke free of hard-coded upstream paths.
 */
export function upstreamRuntimeEsbuildAliases(webuiSource) {
  return UPSTREAM_RUNTIME_CONTRACT_ALIASES.map(
    ({ alias, path }) => `--alias:${alias}=${webuiSource}/${path}`,
  );
}

/** The pin as one frozen object, for callers that prefer a single import. */
export const UPSTREAM_RUNTIME_PIN = Object.freeze({
  repository: UPSTREAM_RUNTIME_REPOSITORY,
  repositoryUrl: UPSTREAM_RUNTIME_REPOSITORY_URL,
  upstream: UPSTREAM_RUNTIME_UPSTREAM,
  upstreamUrl: UPSTREAM_RUNTIME_UPSTREAM_URL,
  commit: UPSTREAM_RUNTIME_COMMIT,
  commitDate: UPSTREAM_RUNTIME_COMMIT_DATE,
  tree: UPSTREAM_RUNTIME_TREE,
  commitSubject: UPSTREAM_RUNTIME_COMMIT_SUBJECT,
  license: UPSTREAM_RUNTIME_LICENSE,
  contractFiles: UPSTREAM_RUNTIME_CONTRACT_FILES,
  bundleSha256: UPSTREAM_RUNTIME_BUNDLE_SHA256,
});
