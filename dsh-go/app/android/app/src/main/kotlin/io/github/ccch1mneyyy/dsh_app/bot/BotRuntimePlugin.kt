// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.bot

import io.github.ccch1mneyyy.dsh_app.runtime.BotRuntimeEndpoint
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

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methods = MethodChannel(binding.binaryMessenger, CHANNEL).also { it.setMethodCallHandler(this) }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "descriptor" -> result.success(BotRuntimeEndpoint.descriptor())
            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methods?.setMethodCallHandler(null)
        methods = null
    }

    companion object {
        const val CHANNEL = "minimax/bot-runtime"
    }
}
