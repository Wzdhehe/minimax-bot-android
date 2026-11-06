// SPDX-License-Identifier: AGPL-3.0-only
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/services.dart';

/// The live Bot service the on-device runtime published.
///
/// Native owns the session: it starts the guest runtime, hands over the
/// authenticated descriptor and only then reports a live service. A missing
/// endpoint means no Bot service is running, and this client says so instead
/// of addressing a made up port. The session token belongs to the app's own
/// UI; it is never handed to the Agent or written into a file the guest can
/// read.
class BotRuntimeEndpoint {
  const BotRuntimeEndpoint({MethodChannel? channel}) : _channel = channel ?? const MethodChannel('minimax/bot-runtime');

  final MethodChannel _channel;

  /// Opening/reconnecting the product asks native to start its own service.
  /// Native keeps this idempotent; it never replaces a live or retiring run.
  Future<void> ensureRunning() => _channel.invokeMethod<void>('start');

  /// The live service, or null when the runtime is not running.
  Future<MiniMaxBotApi?> liveService() async {
    final value = await _channel.invokeMapMethod<String, dynamic>('descriptor');
    if (value == null) return null;
    final baseUrl = value['baseUrl'];
    final token = value['token'];
    if (baseUrl is! String || token is! String || baseUrl.isEmpty || token.isEmpty) {
      throw const FormatException('Bot service descriptor is incomplete');
    }
    return MiniMaxBotApi(Uri.parse(baseUrl), headers: {'Authorization': 'Bearer $token'});
  }
}
