// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Wzdhehe
//
// Single source of truth for the upstream MiniMax Code WebUI fork that the
// built-in Bot host binds to.
//
// The host adapter (agent-port.mjs) is a thin layer over the fork's
// process-local agent contract; the upstream gateway smoke
// (run-upstream-gateway-smoke.sh) bundles the fork's real gateway source and
// drives the vendored Bot service through it. Both must resolve the same
// commit as the constants below.
//
// This pin is enforced, not decorative:
//   - upstream-runtime-pin-check.mjs fails when the pin, docs/UPSTREAM-RUNTIME.md
//     and the checked-out WebUI tree disagree;
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
  license: UPSTREAM_RUNTIME_LICENSE,
  contractFiles: UPSTREAM_RUNTIME_CONTRACT_FILES,
});
