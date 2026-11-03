// SPDX-License-Identifier: AGPL-3.0-only
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mbot_settings/mbot_settings.dart';

class _Port implements MbotProviderPort {
  MbotProviderCatalog catalog = MbotProviderCatalog(providers: []);
  String? submittedKey;
  @override
  Future<MbotProviderCatalog> load() async => catalog;
  @override
  Future<MbotProviderCatalog> save(MbotProviderDraft draft,
      {String? newKey, bool clearKey = false}) async {
    submittedKey = newKey;
    catalog = MbotProviderCatalog(providers: [
      MbotProvider(
          id: 'p1',
          name: draft.name,
          apiFormat: draft.apiFormat,
          baseUrl: draft.baseUrl,
          models: draft.models,
          enabled: draft.enabled,
          hasApiKey: newKey != null)
    ]);
    return catalog;
  }

  @override
  Future<MbotProviderCatalog> delete(String id) async =>
      catalog = MbotProviderCatalog(providers: []);
  @override
  Future<MbotProviderCatalog> selectDefault(String id, String model) async =>
      catalog = MbotProviderCatalog(
          providers: catalog.providers,
          defaultProviderId: id,
          defaultModelId: model);
}

void main() {
  test('saved provider projections refuse plaintext secret fields', () {
    final metadata = <String, dynamic>{
      'providerId': 'p',
      'name': 'Example',
      'apiFormat': 'openai-completions',
      'baseUrl': 'https://example.com/v1',
      'models': ['m'],
      'hasApiKey': true,
      'enabled': true
    };
    for (final key in ['apiKey', 'key', 'accessToken', 'authorization']) {
      expect(() => MbotProvider.fromMap({...metadata, key: 'secret'}),
          throwsFormatException);
    }
    final provider = MbotProvider.fromMap(metadata);
    expect(provider.hasApiKey, isTrue);
    expect(() => provider.models.add('other'), throwsUnsupportedError);
  });

  testWidgets(
      'add a provider, select a default and delete it without revealing saved keys',
      (tester) async {
    final port = _Port();
    var probes = 0;
    await tester.pumpWidget(MaterialApp(
        home: MbotProviderSettingsPage(
            port: port,
            testProvider: (id, model) async {
              probes++;
              expect(id, 'p1');
              expect(model, 'test-model');
              return {'ok': false, 'code': 'provider_unavailable'};
            })));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('mbot.provider.add')));
    await tester.pumpAndSettle();
    await tester.enterText(
        find.byKey(const Key('mbot.provider.name')), 'Example');
    await tester.enterText(find.byKey(const Key('mbot.provider.baseUrl')),
        'https://example.com/v1');
    await tester.enterText(
        find.byKey(const Key('mbot.provider.models')), 'test-model');
    await tester.enterText(
        find.byKey(const Key('mbot.provider.key')), 'new-key-sentinel');
    expect(
        tester
            .widget<TextField>(find.byKey(const Key('mbot.provider.key')))
            .obscureText,
        isTrue);
    await tester.tap(find.byKey(const Key('mbot.provider.save')));
    await tester.pumpAndSettle();
    expect(find.text('Example'), findsOneWidget);
    expect(find.textContaining('new-key-sentinel'), findsNothing);
    expect(port.submittedKey, 'new-key-sentinel');
    await tester.tap(find.byType(PopupMenuButton<int>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('设为默认：test-model'));
    await tester.pumpAndSettle();
    expect(port.catalog.defaultModelId, 'test-model');
    await tester.tap(find.byType(PopupMenuButton<int>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('测试：test-model'));
    await tester.pumpAndSettle();
    expect(probes, 1);
    expect(find.text('本机模型服务尚未就绪'), findsOneWidget);
    await tester.tap(find.byType(PopupMenuButton<int>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('删除供应商'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('删除'));
    await tester.pumpAndSettle();
    expect(port.catalog.providers, isEmpty);
    expect(tester.takeException(), isNull);
  });
}
