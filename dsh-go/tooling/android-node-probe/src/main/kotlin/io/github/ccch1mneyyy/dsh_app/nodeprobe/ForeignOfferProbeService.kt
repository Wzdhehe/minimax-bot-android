// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.nodeprobe

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process

/** Test-only separate isolated UID. Sends one fixed invalid-origin FD offer. */
class ForeignOfferProbeService : Service() {
    private val messenger by lazy {
        Messenger(Handler(Looper.getMainLooper(), Handler.Callback { message ->
            val fd = try {
                if (Build.VERSION.SDK_INT >= 33) message.data.getParcelable("connection", ParcelFileDescriptor::class.java)
                else { @Suppress("DEPRECATION") (message.data.getParcelable("connection") as? ParcelFileDescriptor) }
            } catch (_: Exception) { null }
            val peer = try { message.data.getBinder("peer") } catch (_: Exception) { null }
            val reply = message.replyTo
            var sent = false
            try {
                if (message.what == 1 && message.sendingUid == applicationInfo.uid && fd != null && peer != null && reply != null) {
                    Messenger(peer).send(Message.obtain(null, 7).apply {
                        data = Bundle().apply { putString("id", "foreign-offer"); putParcelable("bot_connection", fd) }
                        replyTo = reply
                    })
                    sent = true
                }
            } catch (_: Exception) { }
            finally { try { fd?.close() } catch (_: Exception) { } }
            if (message.sendingUid == applicationInfo.uid) try {
                reply?.send(Message.obtain(null, 8).apply { data = Bundle().apply { putString("code", if (sent) "sent" else "unavailable") } })
            } catch (_: Exception) { }
            true
        }))
    }
    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onDestroy() {
        super.onDestroy()
        if (Process.myUid() != applicationInfo.uid) Process.killProcess(Process.myPid())
    }
}
