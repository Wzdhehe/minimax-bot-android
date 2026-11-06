// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Requires the real private Service registration and the packaged backup JNI
 * library. Missing packaging must FAIL, never skip/fallback. Fixture lives under
 * a unique cache root; no user database/preferences/Key is touched. Not run yet.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeSnapshotOwnerTest {
    @Test fun aRealWorkerPublishesAnImmutableCopyAndOnlyItsOwnerCanRemoveIt() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "snapshot-owner-${UUID.randomUUID()}")
        Os.mkdir(root.path, 448)
        val context = object : ContextWrapper(base) {
            override fun getNoBackupFilesDir(): File = root
        }
        var safeToDeleteFixture = false
        try {
            val databaseFile = RuntimePrivateDirectory.open(context, listOf("v2", "sqlite")).use {
                File(it.file, "runtime-state.sqlite")
            }
            // Seed BEFORE granting raw source FDs, and close SQLite completely.
            // Never keep a Main SQLite connection alive during alias closure.
            SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { database ->
                database.execSQL("CREATE TABLE snapshot_rows(value TEXT NOT NULL)")
                database.execSQL("INSERT INTO snapshot_rows VALUES('你好'),('second')")
            }
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                val file = File(databaseFile.path + suffix)
                if (file.exists()) Os.chmod(file.path, 384)
            }
            RuntimeDatabaseFiles.open(context).use { source ->
                RuntimeSnapshotOwner.open(context, source) { true }.use { owner ->
                    val name = "runtime-state-before-v2-migration-${System.currentTimeMillis()}-${UUID.randomUUID()}.sqlite"
                    assertEquals(RuntimeSnapshotOwner.Status.PUBLISHED, owner.create(name))
                    val info = owner.catalog().single()
                    assertEquals(name, info.fileName)
                    assertTrue(info.bytes > 0)
                    val published = File(databaseFile.parentFile, "backups/$name")
                    // This test is trusted Main and knows its private fixture.
                    // Production callers get only the owner's read-only FD.
                    SQLiteDatabase.openDatabase(published.path, null,
                        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { copy ->
                        copy.rawQuery("SELECT value FROM snapshot_rows ORDER BY rowid", null).use { rows ->
                            assertTrue(rows.moveToNext()); assertEquals("你好", rows.getString(0))
                            assertTrue(rows.moveToNext()); assertEquals("second", rows.getString(0))
                            assertFalse(rows.moveToNext())
                        }
                    }
                    try { owner.openReadOnly(name, "wrong").close(); fail("wrong identity admitted") }
                    catch (failure: RuntimeDataFailure) { assertEquals(RuntimeDataCode.DENIED, failure.code) }
                    try { owner.removeCreated(name, "wrong"); fail("wrong identity removed a snapshot") }
                    catch (failure: RuntimeDataFailure) { assertEquals(RuntimeDataCode.DENIED, failure.code) }
                    owner.openReadOnly(name, info.identity).use { readonly ->
                        assertEquals(OsConstants.O_RDONLY,
                            Os.fcntlInt(readonly.fileDescriptor, OsConstants.F_GETFL, 0) and OsConstants.O_ACCMODE)
                        try { Os.write(readonly.fileDescriptor, byteArrayOf(1), 0, 1); fail("snapshot was writable") }
                        catch (failure: ErrnoException) { assertEquals(OsConstants.EBADF, failure.errno) }
                        assertEquals(RuntimeDeletionResult.REMOVED, owner.removeCreated(name, info.identity))
                        assertFalse(published.exists())
                        assertTrue(owner.catalog().isEmpty())
                        // Removal is NOT revocation: an already granted RO FD
                        // can still read its immutable old inode until closed.
                        val header = ByteArray(16)
                        assertEquals(16, Os.pread(readonly.fileDescriptor, header, 0, 16, 0L))
                        assertEquals("SQLite format 3\u0000", header.toString(Charsets.UTF_8))
                    }
                }
            }
            safeToDeleteFixture = true // Successful publication proved worker retirement.
        } finally {
            // Failure/UNKNOWN might still have a writer. Leave a unique private
            // fixture rather than deleting it while the worker may own FDs.
            if (safeToDeleteFixture) assertTrue(root.deleteRecursively())
        }
    }
}
