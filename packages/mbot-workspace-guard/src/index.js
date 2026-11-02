/**
 * Workspace path boundary guard.
 *
 * All public API lives in this file so later architecture.md signatures can
 * be aligned in one place. Original MIT code; not derived from DSH/AGPL.
 *
 * Guarantees (best-effort on the resolved path string):
 * - Rejects escapes via `..`, absolute paths outside the root, and symlink
 *   targets that leave the workspace.
 * - Treats `workspace` and `workspace-evil` as distinct (separator-aware).
 *
 * Non-goals: isolatedProcess / UID isolation, TOCTOU-proof open, hardlink
 * content policy, Android SAF URI mapping.
 */

import { realpath, lstat, readFile, writeFile, mkdir } from 'node:fs/promises';
import {
  basename,
  dirname,
  isAbsolute,
  join,
  normalize,
  relative,
  resolve,
  sep,
} from 'node:path';

export class WorkspacePathError extends Error {
  /**
   * @param {string} message
   * @param {{ input?: string, root?: string, code?: string, cause?: unknown }} [detail]
   */
  constructor(message, detail = {}) {
    super(message, detail.cause !== undefined ? { cause: detail.cause } : undefined);
    this.name = 'WorkspacePathError';
    this.code = detail.code ?? 'WORKSPACE_PATH';
    this.input = detail.input;
    this.root = detail.root;
  }
}

/**
 * @param {string} rootReal
 * @param {string} candidateReal
 */
function isInsideRoot(rootReal, candidateReal) {
  if (candidateReal === rootReal) return true;
  const rel = relative(rootReal, candidateReal);
  if (rel === '') return true;
  if (isAbsolute(rel)) return false;
  if (rel === '..' || rel.startsWith(`..${sep}`)) return false;
  return true;
}

/**
 * realpath the deepest existing ancestor, then re-join missing segments.
 * @param {string} absolutePath
 */
async function realpathExistingPrefix(absolutePath) {
  const normalized = normalize(absolutePath);
  const missing = [];
  let cursor = normalized;

  for (;;) {
    try {
      const existing = await realpath(cursor);
      return missing.length === 0 ? existing : normalize(join(existing, ...missing));
    } catch (err) {
      const code = /** @type {NodeJS.ErrnoException} */ (err).code;
      if (code !== 'ENOENT' && code !== 'ENOTDIR') throw err;
      // A dangling link is not a missing path component. Rejoining it would
      // validate a lexical path whose later open/mkdir follows an outside link.
      const entry = await lstat(cursor).catch((error) => {
        if (error.code === 'ENOENT' || error.code === 'ENOTDIR') return undefined;
        throw error;
      });
      if (entry?.isSymbolicLink()) {
        throw new WorkspacePathError('Cannot resolve symbolic link', { code: 'UNRESOLVABLE' });
      }
      const parent = dirname(cursor);
      if (parent === cursor) {
        throw new WorkspacePathError(`Cannot resolve path: ${absolutePath}`, {
          input: absolutePath,
          code: 'UNRESOLVABLE',
        });
      }
      missing.unshift(basename(cursor));
      cursor = parent;
    }
  }
}

/**
 * @param {unknown} inputPath
 * @param {string} rootReal
 */
function rejectDangerousInput(inputPath, rootReal) {
  if (typeof inputPath !== 'string' || inputPath.length === 0) {
    throw new WorkspacePathError('Path must be a non-empty string', {
      input: String(inputPath),
      root: rootReal,
      code: 'INVALID',
    });
  }
  if (inputPath.includes('\0')) {
    throw new WorkspacePathError('Path contains NUL', {
      input: inputPath,
      root: rootReal,
      code: 'INVALID',
    });
  }
}

/**
 * Assert candidate stays inside rootReal. Returns boundary-checked absolute path.
 * @param {string} inputPath
 * @param {string} rootReal
 */
export async function assertWithinWorkspace(inputPath, rootReal) {
  rejectDangerousInput(inputPath, rootReal);

  const joined = isAbsolute(inputPath)
    ? normalize(inputPath)
    : resolve(rootReal, inputPath);

  let finalPath;
  try {
    finalPath = await realpathExistingPrefix(joined);
  } catch (err) {
    if (err instanceof WorkspacePathError) throw err;
    throw new WorkspacePathError(`Cannot resolve path: ${inputPath}`, {
      input: inputPath,
      root: rootReal,
      code: 'UNRESOLVABLE',
      cause: err,
    });
  }

  if (!isInsideRoot(rootReal, finalPath)) {
    throw new WorkspacePathError('Path outside workspace', {
      input: inputPath,
      root: rootReal,
      code: 'OUTSIDE',
    });
  }

  return finalPath;
}

/**
 * @param {string} inputPath
 * @param {string} workspaceRoot
 */
export async function resolveWithinWorkspace(inputPath, workspaceRoot) {
  const rootReal = await realpath(workspaceRoot);
  return assertWithinWorkspace(inputPath, rootReal);
}

/** Strict Agent-facing API. Internal canonical paths must not be submitted as
 * user paths. This is a path fence, not a TOCTOU-proof filesystem sandbox. */
export async function resolveWorkspacePath(root, userPath) {
  if (typeof userPath !== 'string' || userPath.trim().length === 0 ||
      userPath.includes('\0') || isAbsolute(userPath) || userPath.includes('\\') ||
      /^[a-zA-Z]:/.test(userPath) || userPath.split('/').includes('..')) {
    throw new WorkspacePathError('Expected a workspace-relative path without traversal', { code: 'INVALID' });
  }
  return resolveWithinWorkspace(userPath, root);
}

/**
 * Bind a guard to an existing workspace directory.
 * @param {string} workspaceRoot
 */
export async function createWorkspaceGuard(workspaceRoot) {
  if (typeof workspaceRoot !== 'string' || workspaceRoot.length === 0) {
    throw new WorkspacePathError('workspaceRoot must be a non-empty string', {
      code: 'INVALID',
    });
  }

  let rootReal;
  try {
    rootReal = await realpath(workspaceRoot);
  } catch (err) {
    throw new WorkspacePathError(`workspaceRoot is not accessible: ${workspaceRoot}`, {
      root: workspaceRoot,
      code: 'ROOT',
      cause: err,
    });
  }

  const st = await lstat(rootReal);
  if (!st.isDirectory()) {
    throw new WorkspacePathError('workspaceRoot must be a directory', {
      root: rootReal,
      code: 'ROOT',
    });
  }

  return {
    /** @type {string} Canonical workspace root. */
    root: rootReal,

    /** @param {string} inputPath */
    async resolve(inputPath) {
      return assertWithinWorkspace(inputPath, rootReal);
    },

    /** Alias of resolve (name reserved for architecture.md alignment). */
    async assert(inputPath) {
      return assertWithinWorkspace(inputPath, rootReal);
    },

    /**
     * Re-check then read (narrows trivial races; not TOCTOU-proof).
     * @param {string} inputPath
     * @param {BufferEncoding|{ encoding?: BufferEncoding|null }} [options]
     */
    async readFile(inputPath, options = 'utf8') {
      const abs = await assertWithinWorkspace(inputPath, rootReal);
      const again = await assertWithinWorkspace(abs, rootReal);
      return readFile(again, options);
    },

    /**
     * Re-check then write. Parent directories must already resolve inside the
     * workspace (mkdir of missing parents is allowed only after the final
     * path check passes).
     * @param {string} inputPath
     * @param {string|Buffer|Uint8Array} data
     * @param {BufferEncoding|import('node:fs').WriteFileOptions} [options]
     */
    async writeFile(inputPath, data, options = 'utf8') {
      const abs = await assertWithinWorkspace(inputPath, rootReal);
      const parent = dirname(abs);
      if (parent !== rootReal) {
        await assertWithinWorkspace(parent, rootReal);
        await mkdir(parent, { recursive: true });
        // Directory could have been replaced by a symlink during mkdir.
        await assertWithinWorkspace(parent, rootReal);
      }
      const again = await assertWithinWorkspace(abs, rootReal);
      await writeFile(again, data, options);
    },
  };
}

export default {
  WorkspacePathError,
  assertWithinWorkspace,
  resolveWithinWorkspace,
  createWorkspaceGuard,
  resolveWorkspacePath,
};
