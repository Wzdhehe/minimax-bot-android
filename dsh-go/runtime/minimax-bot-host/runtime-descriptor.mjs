// SPDX-License-Identifier: AGPL-3.0-only
//
// Trusted runtime descriptor hand-off.
//
// The descriptor carries the runtime entry path, the pinned upstream commit,
// the loopback port and the session token. It is written by the Android host
// into its private directory with mode 0600 and read back by the runtime.
// It is never passed through argv or through the environment: the runtime
// reads it from a file the host owns.
//
// A descriptor that arrives from the workspace, from a symlink, from a
// world-readable file or from outside the private roots is rejected. The
// token inside a descriptor is a session-scoped loopback credential, not the
// provider API key; the provider key never reaches this process.

import { chmodSync, lstatSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, isAbsolute, resolve, sep } from 'node:path';

export const RUNTIME_DESCRIPTOR_FILENAME = 'runtime-descriptor.json';

/** Permissions a descriptor file must have: owner only. */
const OWNER_ONLY = 0o600;

/** Environment names the runtime is allowed to inherit from the host. */
const ENV_ALLOWLIST = Object.freeze([
  'PATH', 'HOME', 'LANG', 'LC_ALL', 'LC_CTYPE', 'TZ', 'TMPDIR', 'TMP', 'TEMP',
  'NODE_PATH', 'NODE_OPTIONS', 'SSL_CERT_FILE', 'SSL_CERT_DIR',
  'LANG_ENCODING', 'TERM',
]);

/** Names that may never be added, not even by the host itself. */
const CREDENTIAL_NAME = /(api[-_.]?key|secret|token|credential|password|passwd|cookie|authorization)/i;

/**
 * True when `candidate` is `root` itself or lives under it. Uses path
 * separators, so `/data/private/workspace` never matches
 * `/data/private/workspace-evil`.
 */
function isInside(root, candidate) {
  const base = resolve(root);
  const path = resolve(candidate);
  if (path === base) return true;
  return path.startsWith(base.endsWith(sep) ? base : base + sep);
}

/**
 * Build a validated descriptor. Throws before anything is written when a
 * field is missing or malformed.
 *
 * The optional broker session is the native model broker the guest calls for
 * model requests. It is a loopback credential, like the Bot service token,
 * and it never carries a provider key: the provider key stays in the native
 * side and only the resolved model answer crosses this session.
 */
export function createRuntimeDescriptor({ entryPath, pinCommit, port, token, broker = null }) {
  if (typeof entryPath !== 'string' || !entryPath || entryPath.includes('\0') || !isAbsolute(entryPath)) {
    throw new TypeError('An absolute runtime entry path is required');
  }
  if (typeof pinCommit !== 'string' || !/^[0-9a-f]{40}$/.test(pinCommit)) {
    throw new TypeError('A pinned upstream commit is required');
  }
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new TypeError('A loopback port is required');
  }
  if (typeof token !== 'string' || !/^[0-9a-f]{32,}$/.test(token)) {
    throw new TypeError('A session token is required');
  }
  if (broker === null || broker === undefined) {
    return Object.freeze({ entryPath, pinCommit, port, token, broker: null });
  }
  const { baseUrl, token: brokerToken } = broker ?? {};
  if (typeof baseUrl !== 'string' || !/^http:\/\/127\.0\.0\.1:[0-9]{1,5}$/.test(baseUrl)) {
    throw new TypeError('A loopback broker URL is required');
  }
  if (typeof brokerToken !== 'string' || !/^[0-9a-f]{32,}$/.test(brokerToken)) {
    throw new TypeError('A broker session token is required');
  }
  return Object.freeze({ entryPath, pinCommit, port, token, broker: Object.freeze({ baseUrl, token: brokerToken }) });
}

/**
 * Reject any descriptor location the host does not own: outside the private
 * roots, inside the agent workspace, or a symlink pointing anywhere else.
 */
export function assertDescriptorPathAllowed(path, { allowedRoots = [], workspaceRoot = null } = {}) {
  if (typeof path !== 'string' || !path) throw new TypeError('A descriptor path is required');
  const target = resolve(path);
  if (!allowedRoots.some((root) => isInside(root, target))) {
    const error = new Error('descriptor_path_rejected');
    error.code = 'descriptor_path_rejected';
    throw error;
  }
  if (workspaceRoot && isInside(workspaceRoot, target)) {
    const error = new Error('descriptor_path_rejected');
    error.code = 'descriptor_path_rejected';
    throw error;
  }
  return target;
}

/**
 * Write the descriptor into the host private directory. The file is created
 * with owner-only permissions and re-chmod'ed afterwards so a permissive
 * umask cannot widen it.
 */
export function writeRuntimeDescriptor({ dir, descriptor, allowedRoots = [], workspaceRoot = null }) {
  const root = resolve(dir);
  assertDescriptorPathAllowed(dir, { allowedRoots: allowedRoots.length ? allowedRoots : [root], workspaceRoot });
  const target = resolve(root, RUNTIME_DESCRIPTOR_FILENAME);
  assertDescriptorPathAllowed(target, { allowedRoots: allowedRoots.length ? allowedRoots : [root], workspaceRoot });
  mkdirSync(root, { recursive: true, mode: 0o700 });
  writeFileSync(target, `${JSON.stringify(descriptor, null, 2)}\n`, { mode: OWNER_ONLY });
  chmodSync(target, OWNER_ONLY);
  return target;
}

/**
 * Read a descriptor back. The file must be a regular, owner-only file inside
 * the private roots and outside the workspace.
 */
export function readRuntimeDescriptor({ path, allowedRoots = [], workspaceRoot = null } = {}) {
  const target = assertDescriptorPathAllowed(path, { allowedRoots, workspaceRoot });
  let stats;
  try { stats = lstatSync(target); }
  catch {
    const error = new Error('descriptor_missing');
    error.code = 'descriptor_missing';
    throw error;
  }
  if (!stats.isFile() || stats.isSymbolicLink()) {
    const error = new Error('descriptor_not_a_regular_file');
    error.code = 'descriptor_not_a_regular_file';
    throw error;
  }
  if ((stats.mode & 0o077) !== 0) {
    const error = new Error('descriptor_permissions');
    error.code = 'descriptor_permissions';
    throw error;
  }
  let parsed;
  try { parsed = JSON.parse(readFileSync(target, 'utf8')); }
  catch {
    const error = new Error('descriptor_invalid');
    error.code = 'descriptor_invalid';
    throw error;
  }
  try {
    return createRuntimeDescriptor(parsed);
  } catch {
    const error = new Error('descriptor_invalid');
    error.code = 'descriptor_invalid';
    throw error;
  }
}

/**
 * The environment the runtime child process may see. It is built from an
 * allowlist, so no provider key, session token or broker credential can ride
 * in through an inherited variable.
 */
export function buildRuntimeEnv({ baseEnv = process.env, extra = {} } = {}) {
  const env = {};
  for (const name of ENV_ALLOWLIST) {
    const value = baseEnv[name];
    if (typeof value === 'string' && value) env[name] = value;
  }
  for (const [name, value] of Object.entries(extra)) {
    if (typeof name !== 'string' || !/^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) continue;
    if (CREDENTIAL_NAME.test(name)) continue;
    if (typeof value !== 'string') continue;
    env[name] = value;
  }
  return env;
}

export { isInside };
