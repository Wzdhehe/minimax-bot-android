// SPDX-License-Identifier: AGPL-3.0-only
// Preserve Node's module API; wrap only the reviewed DB import's createRequire.
export * from 'node:module';
export { createRuntimeSqliteRequire as createRequire } from './runtime-sqlite-binding.mjs';
