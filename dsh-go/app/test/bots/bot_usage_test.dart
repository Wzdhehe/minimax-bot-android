// SPDX-License-Identifier: AGPL-3.0-only
import 'package:dsh_app/bot/bot_usage.dart';
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mbot_settings/mbot_settings.dart';

class _Providers implements MbotProviderPort {
  _Providers(this.catalog);
  final MbotProviderCatalog catalog;
  @override
  Future<MbotProviderCatalog> load() async => catalog;
  @override
  Future<MbotProviderCatalog> save(MbotProviderDraft draft, {String? newKey, bool clearKey = false}) =>
      throw UnimplementedError();
  @override
  Future<MbotProviderCatalog> delete(String id) => throw UnimplementedError();
  @override
  Future<MbotProviderCatalog> selectDefault(String id, String model) => throw UnimplementedError();
}

class _Api extends MiniMaxBotApi {
  _Api() : super(Uri.parse('http://127.0.0.1:8788'));
  int calls = 0;
  bool forced = false;
  @override
  Future<Map<String, Object?>> usageQuota({bool forceRefresh = false}) async {
    calls++;
    forced = forceRefresh;
    return {'signedIn': false};
  }
}

void main() {
  test('a selected API provider never invokes the MiniMax account quota path', () async {
    final api = _Api();
    addTearDown(api.close);
    final providers = _Providers(
      MbotProviderCatalog(
        providers: [
          MbotProvider(
            id: 'custom',
            name: 'Example',
            apiFormat: 'openai-completions',
            baseUrl: 'https://example.com',
            models: ['m'],
            enabled: true,
            hasApiKey: true,
          ),
        ],
        defaultProviderId: 'custom',
        defaultModelId: 'm',
      ),
    );
    final result = await BotUsageQuotaPort(api: api, providers: providers).load();
    expect(result.supported, isFalse);
    expect(api.calls, 0);
  });
  test('without a custom provider the account result and refresh flag come from host', () async {
    final api = _Api();
    addTearDown(api.close);
    final result = await BotUsageQuotaPort(
      api: api,
      providers: _Providers(MbotProviderCatalog(providers: [])),
    ).load(forceRefresh: true);
    expect(result.signedIn, isFalse);
    expect(result.supported, isTrue);
    expect(api.calls, 1);
    expect(api.forced, isTrue);
  });
}
