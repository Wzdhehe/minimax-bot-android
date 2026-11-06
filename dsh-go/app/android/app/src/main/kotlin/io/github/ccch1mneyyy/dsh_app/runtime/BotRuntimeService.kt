// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.ccch1mneyyy.dsh_app.MainActivity

/** Keeps the local Bot alive independently of a Flutter page. System/user
 * process kills remain interruptions, never a promise of exact background timing. */
class BotRuntimeService : Service() {
    private val observer: () -> Unit = { update() }
    override fun onCreate() {
        super.onCreate()
        BotRuntimeManager.attach(this)
        BotRuntimeManager.observe(observer)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "MiniMax Bot", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        foreground(notification())
        if (intent?.action == STOP) BotRuntimeManager.stop() else BotRuntimeManager.start(this)
        update()
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        val state = BotRuntimeManager.status()["phase"]
        val chinese = resources.configuration.locales[0].language == "zh"
        val text = when (state) {
            "ready" -> if (chinese) "Bot 正在运行" else "Bot is running"
            "stopping" -> if (chinese) "正在停止 Bot" else "Stopping Bot"
            "failed" -> if (chinese) "Bot 启动失败" else "Bot could not start"
            else -> if (chinese) "正在启动 Bot" else "Starting Bot"
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, BotRuntimeService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("MiniMax Bot").setContentText(text).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, if (chinese) "停止" else "Stop", stop).build()).build()
    }
    private fun foreground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, notification)
    }
    private fun update() {
        val phase = BotRuntimeManager.status()["phase"]
        if (phase == "stopped" || phase == "failed") { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        else getSystemService(NotificationManager::class.java).notify(ID, notification())
    }
    override fun onDestroy() { BotRuntimeManager.removeObserver(observer); BotRuntimeManager.stop(); super.onDestroy() }
    companion object {
        const val START = "minimax.bot.START"
        const val STOP = "minimax.bot.STOP"
        private const val CHANNEL = "mbot_runtime"
        private const val ID = 0x4d424f54
    }
}
