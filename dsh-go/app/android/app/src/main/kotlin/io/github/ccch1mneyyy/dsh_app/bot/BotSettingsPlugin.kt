// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.bot

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodChannel

/** Key material never crosses back to Dart. A future model broker uses this
 * store inside the host UID, outside the untrusted execution process. */
class BotSettingsPlugin : FlutterPlugin {
    private var channel: MethodChannel? = null
    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        val store = BotSettingsStore(binding.applicationContext)
        val providers = BotProviderStore(binding.applicationContext)
        channel = MethodChannel(binding.binaryMessenger, "minimax/bot-settings").also { channel ->
            channel.setMethodCallHandler { call, result ->
                try {
                    when (call.method) {
                        "providers" -> result.success(providers.catalog())
                        "saveProvider" -> {
                            providers.save(call.arguments as? Map<*, *> ?: throw IllegalArgumentException())
                            result.success(providers.catalog())
                        }
                        "deleteProvider" -> {
                            providers.delete(call.argument<String>("providerId") ?: throw IllegalArgumentException())
                            result.success(providers.catalog())
                        }
                        "selectDefaultProviderModel" -> {
                            providers.selectDefault(call.argument<String>("providerId") ?: "", call.argument<String>("modelId") ?: "")
                            result.success(providers.catalog())
                        }
                        "load" -> result.success(store.load())
                        "save" -> {
                            store.save(call.argument<String>("baseUrl") ?: "", call.argument<String>("model") ?: "",
                                call.argument<String>("key"), call.argument<Boolean>("clearKey") == true)
                            result.success(store.load())
                        }
                        else -> result.notImplemented()
                    }
                } catch (_: IllegalArgumentException) {
                    result.error("invalid_settings", "Check the HTTPS base URL and model", null)
                } catch (_: Exception) {
                    result.error("settings_storage", "Cannot access encrypted provider settings", null)
                }
            }
        }
    }
    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
    }
}
