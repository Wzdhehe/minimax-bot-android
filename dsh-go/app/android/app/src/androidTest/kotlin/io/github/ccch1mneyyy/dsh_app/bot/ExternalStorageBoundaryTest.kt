// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.bot

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalStorageBoundaryTest {
    @Test
    fun appCannotReadAnUnselectedSharedStorageFile() {
        assumeTrue("Scoped storage requires Android 11+", Build.VERSION.SDK_INT >= 30)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(instrumentation.targetContext.applicationInfo.targetSdkVersion >= 36)
        val path = "/sdcard/Download/mbot-boundary-${UUID.randomUUID()}.txt"
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }
        try {
            // Shell seeds a non-media file; the application receives no SAF grant.
            shell("mkdir -p /sdcard/Download\nprintf 'boundary-sentinel' > $path")
            assertEquals("boundary-sentinel", shell("cat $path"))
            try {
                File(path).readText()
                fail("Application read shared storage without a user grant")
            } catch (_: IOException) {
                // Expected: scoped storage rejects the application's open.
            } catch (_: SecurityException) {
                // Also an explicit denial.
            }
        } finally {
            shell("rm -f $path")
        }
    }
}
