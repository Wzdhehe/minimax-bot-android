// SPDX-License-Identifier: AGPL-3.0-only
import 'package:flutter/services.dart';
import 'package:mbot_settings/mbot_settings.dart';

/// This port manages private metadata and submits newly entered keys to native
/// storage. It never requests or reconstructs a previously saved API key.
class NativeBotProviders implements MbotProviderPort {
  const NativeBotProviders();
  static const _channel = MethodChannel('minimax/bot-settings');
  Future<MbotProviderCatalog> _call(String method, [Object? arguments]) async {
    final value = await _channel.invokeMapMethod<String, dynamic>(method, arguments);
    if (value == null) throw const FormatException('Provider catalog missing');
    return MbotProviderCatalog.fromMap(value);
  }

  @override
  Future<MbotProviderCatalog> load() => _call('providers');
  @override
  Future<MbotProviderCatalog> save(MbotProviderDraft draft, {String? newKey, bool clearKey = false}) =>
      _call('saveProvider', {...draft.toMap(), 'key': ?newKey, 'clearKey': clearKey});
  @override
  Future<MbotProviderCatalog> delete(String id) => _call('deleteProvider', {'providerId': id});
  @override
  Future<MbotProviderCatalog> selectDefault(String id, String model) =>
      _call('selectDefaultProviderModel', {'providerId': id, 'modelId': model});
}
