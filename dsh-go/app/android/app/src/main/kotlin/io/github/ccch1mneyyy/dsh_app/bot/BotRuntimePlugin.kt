// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.bot

import io.github.ccch1mneyyy.dsh_app.runtime.BotRuntimeManager
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Hands the live Bot service endpoint to the app's own UI.
 *
 * The endpoint exists only while a Bot service is running. There is no
 * placeholder URL and no default port: an app without a running runtime gets
 * null and says so, instead of addressing a service that is not there.
 */
internal class BotRuntimePlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private var methods: MethodChannel? = null
    private var context: android.content.Context? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        BotRuntimeManager.attach(binding.applicationContext)
        methods = MethodChannel(binding.binaryMessenger, CHANNEL).also { it.setMethodCallHandler(this) }
        BotRuntimeManager.requestStart(binding.applicationContext)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "descriptor" -> BotRuntimeManager.descriptorWhenSettled { result.success(it) }
            "status" -> result.success(BotRuntimeManager.status())
            "start" -> { context?.let { BotRuntimeManager.requestStart(it) }; result.success(null) }
            "stop" -> { BotRuntimeManager.stop(); result.success(null) }
            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methods?.setMethodCallHandler(null)
        methods = null
        context = null
    }

    companion object {
        const val CHANNEL = "minimax/bot-runtime"
    }
}
