// SPDX-License-Identifier: AGPL-3.0-only
import { createBotFileLease } from './bot-file-lease.mjs';
import { createBotMemoryFilesystem } from './bot-memory-filesystem.mjs';
import { createBotStorePersistence } from './bot-store-persistence.mjs';

/** Construct both built-in Bot stores around exactly one trusted file lease. */
export function createBotPersistence({ dataDir, files } = {}) {
  const lease = createBotFileLease(files);
  const storePersistence = createBotStorePersistence({ dataDir, lease });
  const memoryFilesystem = createBotMemoryFilesystem({ dataDir, lease });
  return Object.freeze({ storePersistence, memoryFilesystem });
}
