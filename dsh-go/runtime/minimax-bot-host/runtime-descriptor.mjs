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

import { closeSync, constants, fstatSync, fsyncSync, lstatSync, mkdirSync, mkdtempSync, openSync, readFileSync, readSync, realpathSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { basename, dirname, isAbsolute, join, resolve, sep } from 'node:path';
import { findCatalogViolation, validatedModelSettings } from './runtime-catalog-policy.mjs';

export const RUNTIME_DESCRIPTOR_FILENAME = 'runtime-descriptor.json';

/** Permissions a descriptor file must have: owner only. */
const OWNER_ONLY = 0o600;
const MAX_DESCRIPTOR_BYTES = 1024 * 1024;

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
 * The optional non-secret provider catalog the guest runtime projects into
 * the upstream provider API. A credential or a real upstream endpoint in it
 * would reach the guest, so it is refused before anything is written.
 */
function assertCatalogSafe(catalog) {
  if (!catalog || typeof catalog !== 'object' || Array.isArray(catalog)) {
    throw new TypeError('descriptor_catalog_invalid');
  }
  const violation = findCatalogViolation(catalog);
  if (violation === 'credential') throw new TypeError('descriptor_catalog_contains_credential');
  if (violation === 'endpoint') throw new TypeError('descriptor_catalog_forbidden_field');
  return catalog;
}

/** Freeze the catalog so no later holder can mutate the projected view. */
function freezeCatalog(value, seen = new WeakSet()) {
  if (!value || typeof value !== 'object' || seen.has(value)) return value;
  seen.add(value);
  for (const entry of Object.values(value)) freezeCatalog(entry, seen);
  return Object.freeze(value);
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
export function createRuntimeDescriptor({ entryPath, pinCommit, port, token, broker = null, catalog = null }) {
  if (typeof entryPath !== 'string' || !entryPath || entryPath.includes('\0') || !isAbsolute(entryPath)) {
    throw new TypeError('An absolute runtime entry path is required');
  }
  if (typeof pinCommit !== 'string' || !/^[0-9a-f]{40}$/.test(pinCommit)) {
    throw new TypeError('A pinned upstream commit is required');
  }
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new TypeError('A loopback port is required');
  }
  if (typeof token !== 'string' || !/^[0-9a-f]{32,64}$/.test(token)) {
    throw new TypeError('A session token is required');
  }
  const projectedCatalog = catalog === null || catalog === undefined
    ? null
    : freezeCatalog(assertCatalogSafe(catalog));
  if (broker === null || broker === undefined) {
    return Object.freeze({ entryPath, pinCommit, port, token, broker: null, catalog: projectedCatalog });
  }
  const { baseUrl, token: brokerToken } = broker ?? {};
  const brokerPort = typeof baseUrl === 'string' ? /^http:\/\/127\.0\.0\.1:([0-9]{1,5})$/.exec(baseUrl)?.[1] : undefined;
  if (!brokerPort || Number(brokerPort) < 1 || Number(brokerPort) > 65535 || String(Number(brokerPort)) !== brokerPort) {
    throw new TypeError('A loopback broker URL is required');
  }
  if (typeof brokerToken !== 'string' || !/^[0-9a-f]{32,64}$/.test(brokerToken)) {
    throw new TypeError('A broker session token is required');
  }
  return Object.freeze({
    entryPath, pinCommit, port, token,
    broker: Object.freeze({ baseUrl, token: brokerToken }),
    catalog: projectedCatalog,
  });
}

/**
 * The non-secret provider catalog of a descriptor, or null.
 *
 * The descriptor is the only channel that carries the native catalog into the
 * guest, so the guest reads the catalog from the descriptor it validated
 * itself. A hand-built descriptor is re-validated here: a catalog that breaks
 * the field policy is refused instead of being projected.
 */
export function catalogFromDescriptor(descriptor) {
  if (descriptor === null || descriptor === undefined) return null;
  return createRuntimeDescriptor(descriptor).catalog ?? null;
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

function descriptorFailure(code) {
  return Object.assign(new Error(code), { code });
}

// Resolve existing ancestors before appending missing components. This checks
// parent aliases even when a descriptor directory has not been created yet.
function physicalLocation(path) {
  let ancestor = resolve(path);
  const missing = [];
  for (;;) {
    try { return resolve(realpathSync(ancestor), ...missing); }
    catch (error) {
      if (error.code !== 'ENOENT') throw descriptorFailure('descriptor_path_rejected');
      const parent = dirname(ancestor);
      if (parent === ancestor) throw descriptorFailure('descriptor_path_rejected');
      missing.unshift(basename(ancestor));
      ancestor = parent;
    }
  }
}

function privateDirectoryLocation(directory, { allowedRoots, workspaceRoot }) {
  const physical = physicalLocation(directory);
  assertDescriptorPathAllowed(physical, {
    allowedRoots: allowedRoots.map(physicalLocation),
    workspaceRoot: workspaceRoot ? physicalLocation(workspaceRoot) : null,
  });
  return physical;
}

/**
 * Atomically publish validated descriptor fields in a trusted host-owned
 * directory. Parent aliases cannot redirect it into the workspace; replacing
 * a planted leaf symlink never writes through to the link's target.
 * This is not an openat capability API: directory ancestors must remain under
 * trusted host ownership. It does not defend against concurrent same-UID
 * code replacing those directories, or replace Android UID isolation.
 */
export function writeRuntimeDescriptor({ dir, descriptor, allowedRoots = [], workspaceRoot = null }) {
  const validated = createRuntimeDescriptor(descriptor);
  const root = resolve(dir);
  const policy = { allowedRoots: allowedRoots.length ? allowedRoots : [root], workspaceRoot };
  assertDescriptorPathAllowed(root, policy);
  const target = resolve(root, RUNTIME_DESCRIPTOR_FILENAME);
  assertDescriptorPathAllowed(target, policy);
  const physicalRoot = privateDirectoryLocation(root, policy);
  mkdirSync(physicalRoot, { recursive: true, mode: 0o700 });
  const staging = mkdtempSync(join(physicalRoot, '.runtime-descriptor-'));
  let fd;
  try {
    const stagedFile = join(staging, 'session.json');
    fd = openSync(stagedFile, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, OWNER_ONLY);
    writeFileSync(fd, `${JSON.stringify(validated, null, 2)}\n`);
    fsyncSync(fd);
    closeSync(fd);
    fd = undefined;
    renameSync(stagedFile, join(physicalRoot, RUNTIME_DESCRIPTOR_FILENAME));
  } finally {
    if (fd !== undefined) closeSync(fd);
    rmSync(staging, { recursive: true, force: true });
  }
  return target;
}

/** Read and validate one opened file, without reopening a checked pathname. */
export function readRuntimeDescriptor({ path, allowedRoots = [], workspaceRoot = null } = {}) {
  const target = assertDescriptorPathAllowed(path, { allowedRoots, workspaceRoot });
  const physicalRoot = privateDirectoryLocation(dirname(target), { allowedRoots, workspaceRoot });
  const physicalTarget = join(physicalRoot, basename(target));
  let fd;
  try {
    // Keep the stable leaf-symlink error, including for a dangling link.
    const leaf = lstatSync(physicalTarget);
    if (!leaf.isFile() || leaf.isSymbolicLink()) throw descriptorFailure('descriptor_not_a_regular_file');
    fd = openSync(physicalTarget, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
    const stats = fstatSync(fd);
    if (!stats.isFile()) throw descriptorFailure('descriptor_not_a_regular_file');
    if ((stats.mode & 0o077) !== 0 || (typeof process.getuid === 'function' && stats.uid !== process.getuid())) {
      throw descriptorFailure('descriptor_permissions');
    }
    try { return createRuntimeDescriptor(JSON.parse(readFileSync(fd, 'utf8'))); }
    catch { throw descriptorFailure('descriptor_invalid'); }
  } catch (error) {
    if (error.code?.startsWith('descriptor_')) throw error;
    throw descriptorFailure(error.code === 'ELOOP' ? 'descriptor_not_a_regular_file' : 'descriptor_missing');
  } finally {
    if (fd !== undefined) closeSync(fd);
  }
}

/**
 * Consume a read-only descriptor FD granted by the trusted Android owner.
 * The expected owner UID is supplied by the native launcher, not the Agent
 * or the descriptor itself. An isolated UID cannot open the owner's private
 * pathname; it reads only this explicitly granted regular file. Ownership of
 * fd transfers here and the FD is closed on every valid-FD success/failure.
 */
export function readRuntimeDescriptorFd({ fd, expectedOwnerUid } = {}) {
  if (!Number.isSafeInteger(fd) || fd < 3) throw descriptorFailure('descriptor_invalid');
  try {
    if (!Number.isSafeInteger(expectedOwnerUid) || expectedOwnerUid < 0) throw descriptorFailure('descriptor_invalid');
    const stats = fstatSync(fd);
    // An already-granted generation may have been atomically replaced and
    // therefore have zero directory links; the open FD still owns its inode.
    if (!stats.isFile() || stats.nlink > 1) throw descriptorFailure('descriptor_not_a_regular_file');
    if ((stats.mode & 0o077) !== 0 || stats.uid !== expectedOwnerUid) throw descriptorFailure('descriptor_permissions');
    if (!Number.isSafeInteger(stats.size) || stats.size < 1 || stats.size > MAX_DESCRIPTOR_BYTES) {
      throw descriptorFailure('descriptor_invalid');
    }
    const bytes = Buffer.alloc(stats.size);
    for (let offset = 0; offset < bytes.length;) {
      const count = readSync(fd, bytes, offset, bytes.length - offset, offset);
      if (count <= 0) throw descriptorFailure('descriptor_invalid');
      offset += count;
    }
    const raw = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
    assertTransferredDescriptorShape(raw);
    return createRuntimeDescriptor(raw);
  } catch (error) {
    if (typeof error?.code === 'string' && error.code.startsWith('descriptor_')) throw error;
    throw descriptorFailure('descriptor_invalid');
  } finally {
    try { closeSync(fd); } catch {}
  }
}

// The native encoder uses an allowlist. Refuse extra fields in the transferred
// representation rather than silently accepting a forgotten sensitive field.
function assertTransferredDescriptorShape(raw) {
  const object = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
  const only = (value, names) => object(value) && Object.keys(value).every((name) => names.includes(name));
  if (!only(raw, ['entryPath', 'pinCommit', 'port', 'token', 'broker', 'catalog'])) throw descriptorFailure('descriptor_invalid');
  if (raw.broker != null && !only(raw.broker, ['baseUrl', 'token'])) throw descriptorFailure('descriptor_invalid');
  const catalog = raw.catalog;
  if (catalog == null) return;
  if (!only(catalog, ['providers', 'defaultProviderId', 'defaultModelId']) ||
      (catalog.providers !== undefined && !Array.isArray(catalog.providers))) throw descriptorFailure('descriptor_invalid');
  for (const row of catalog.providers ?? []) {
    if (!only(row, ['providerId', 'name', 'apiFormat', 'enabled', 'models', 'modelSettings', 'hasApiKey']) ||
        (row.models !== undefined && (!Array.isArray(row.models) || row.models.some((value) => typeof value !== 'string'))) ||
        ['providerId', 'name', 'apiFormat'].some((key) => row[key] !== undefined && typeof row[key] !== 'string') ||
        ['enabled', 'hasApiKey'].some((key) => row[key] !== undefined && typeof row[key] !== 'boolean')) {
      throw descriptorFailure('descriptor_invalid');
    }
    try { validatedModelSettings(row); }
    catch { throw descriptorFailure('descriptor_invalid'); }
  }
  for (const key of ['defaultProviderId', 'defaultModelId']) {
    if (catalog[key] != null && typeof catalog[key] !== 'string') throw descriptorFailure('descriptor_invalid');
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
    if (!ENV_ALLOWLIST.includes(name)) continue;
    if (CREDENTIAL_NAME.test(name)) continue;
    if (typeof value !== 'string') continue;
    env[name] = value;
  }
  return env;
}

export { isInside };
