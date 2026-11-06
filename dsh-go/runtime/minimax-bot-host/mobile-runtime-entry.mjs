// SPDX-License-Identifier: AGPL-3.0-only
// Product composition: the actual pinned runtime, official Bot, native-only
// model requests, Main persistence and separately granted SQLite snapshots.
import { startBuiltinBotRuntimeFromFd } from '@mbot/compiled-host';
import { createNativeSnapshotSqlitePort } from './runtime-snapshot-port.mjs';
import { createSnapshotFdDatabase } from './runtime-snapshot-database.mjs';
import fs from 'node:fs';

export async function startMobileBotRuntime({ descriptorFd, ownerUid, Database, addon, dataFds,
  files, models, snapshots, connections, filesystem, onReady, signal }) {
  if (!Array.isArray(dataFds) || dataFds.length !== 4 || dataFds.some((fd) => !Number.isInteger(fd) || fd < 3)) throw Error('mobile_database_grant_invalid');
  addon.setFdVfs(...dataFds);
  dataFds.forEach((fd) => fs.closeSync(fd)); // Before any SQLite locks exist.
  if (!filesystem || typeof filesystem.close !== 'function' || filesystem.runtimeRoot !== '/mbot-data/runtime') throw Error('mobile_filesystem_required');
  const snapshotPort = createNativeSnapshotSqlitePort({ ...snapshots,
    openDatabase: createSnapshotFdDatabase({ Database, nativeBinding: addon, closeDescriptor: fs.closeSync }) });
  let handle;
  try {
    handle = await startBuiltinBotRuntimeFromFd({ descriptorFd, expectedOwnerUid: ownerUid,
      runtimeDataDir: filesystem.runtimeRoot, botDataDir: '/mbot-bot', appVersion: 'android-bot', signal,
      files, nativeBinding: models, connections, onReady,
      nativeSqlite: { open: (options) => new Database('state.db', { ...options, nativeBinding: addon }),
        metadata: (slot) => addon.fdVfsMetadata(slot), backup: snapshotPort.backup,
        openBackup: snapshotPort.openBackup, snapshots: snapshotPort.snapshots },
      logger: { info() {}, warn() {}, error() {} },
    });
    let closing;
    return Object.freeze({ ...handle, close() {
      if (!closing) closing = Promise.resolve().then(() => handle.close()).finally(() => {
        snapshotPort.deactivate(); filesystem.close();
      });
      return closing;
    } });
  } catch (error) { snapshotPort.deactivate(); filesystem.close(); throw error; }
}
