// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.nodeprobe

/** Private verification APK only. No Binder/MethodChannel or Agent entry. */
internal object NativeFilePublicationProbe {
    init { System.loadLibrary("mbot_file_publication_probe") }
    external fun publish(directory: Int, source: String, target: String): Int
}
