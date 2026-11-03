// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.bot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodChannel

/** Read system state and open settings after a user action. No permission
 * prompts, background starts, wake locks or battery exemption requests. */
class MbotSystemSettingsPlugin : FlutterPlugin {
    private var channel: MethodChannel? = null
    private val channelIds = setOf("mbot_schedule_due", "mbot_turn_done", "mbot_failed", "mbot_approval", "mbot_question")
    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        val context = binding.applicationContext
        val notifications = context.getSystemService(NotificationManager::class.java)
        channel = MethodChannel(binding.binaryMessenger, "mbot/system-settings").also { channel ->
            channel.setMethodCallHandler { call, result ->
                fun open(intent: Intent): Boolean = try {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (_: Exception) { false }
                try {
                    when (call.method) {
                        "notificationsEnabled" -> result.success(notifications.areNotificationsEnabled())
                        "batteryIgnored" -> result.success(context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName))
                        "openNotifications" -> result.success(open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)))
                        "openChannel" -> {
                            val id = call.argument<String>("id")
                            require(id in channelIds)
                            result.success(open(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, id)))
                        }
                        "openBattery" -> result.success(open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)))
                        "openDetails" -> result.success(open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))))
                        "ensureChannels" -> {
                            val rows = call.arguments as? List<*> ?: error("Expected channel specs")
                            val channels = rows.map { value ->
                                val row = value as? Map<*, *> ?: error("Expected channel spec")
                                val id = row["id"] as? String ?: error("Expected channel id")
                                require(id in channelIds)
                                val name = row["name"] as? String ?: error("Expected channel name")
                                val importance = (row["importance"] as? Number)?.toInt() ?: 3
                                require(importance in 1..4)
                                NotificationChannel(id, name.take(80), importance).apply {
                                    description = (row["description"] as? String)?.take(300)
                                }
                            }
                            notifications.createNotificationChannels(channels)
                            result.success(null)
                        }
                        else -> result.notImplemented()
                    }
                } catch (_: Exception) {
                    result.error("system_settings_unavailable", "Cannot access system settings", null)
                }
            }
        }
    }
    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
    }
}
