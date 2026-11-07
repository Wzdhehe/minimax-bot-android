// SPDX-License-Identifier: AGPL-3.0-only
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mbot_settings/mbot_settings.dart';
import 'package:mbot_settings/src/ui/provider_model_page.dart';

class _Port implements MbotProviderPort {
  MbotProviderCatalog catalog = MbotProviderCatalog(providers: []);
  String? submittedKey;
  MbotProviderDraft? savedDraft;
  @override
  Future<MbotProviderCatalog> load() async => catalog;
  @override
  Future<MbotProviderCatalog> save(MbotProviderDraft draft,
      {String? newKey, bool clearKey = false}) async {
    submittedKey = newKey;
    savedDraft = draft;
    catalog = MbotProviderCatalog(providers: [
      MbotProvider(
          id: 'p1',
          name: draft.name,
          apiFormat: draft.apiFormat,
          baseUrl: draft.baseUrl,
          models: draft.models,
          modelSettings: draft.modelSettings ?? const [],
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
        find.byKey(const Key('mbot.provider.key')), 'new-key-sentinel');
    expect(
        tester
            .widget<TextField>(find.byKey(const Key('mbot.provider.key')))
            .obscureText,
        isTrue);
    await tester.ensureVisible(find.byKey(const Key('mbot.provider.addModel')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('mbot.provider.addModel')));
    await tester.pumpAndSettle();
    await tester.enterText(
        find.byKey(const Key('mbot.model.id')), 'test-model');
    await tester.enterText(
        find.byKey(const Key('mbot.model.context')), '1000000');
    await tester.enterText(
        find.byKey(const Key('mbot.model.output')), '128000');
    await tester.ensureVisible(find.widgetWithText(FilterChip, 'high'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilterChip, 'high'));
    await tester
        .ensureVisible(find.byKey(const ValueKey('mbot.model.input.image')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('mbot.model.input.image')));
    await tester.tap(find.byKey(const Key('mbot.model.save')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('mbot.provider.save')));
    await tester.pumpAndSettle();
    expect(find.text('Example'), findsOneWidget);
    expect(find.textContaining('new-key-sentinel'), findsNothing);
    expect(port.submittedKey, 'new-key-sentinel');
    expect(port.savedDraft!.toMap()['modelSettings'], [
      {
        'modelId': 'test-model',
        'enabled': true,
        'limit': {'context': 1000000, 'output': 128000},
        'effortOptions': ['high'],
        'reasoning': true,
        'modalities': {
          'input': ['text', 'image'],
          'output': ['text']
        }
      }
    ]);
    await tester.tap(find.byType(PopupMenuButton<String>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Set as default'));
    await tester.pumpAndSettle();
    expect(port.catalog.defaultModelId, 'test-model');
    await tester.tap(find.byType(PopupMenuButton<String>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Test connection'));
    await tester.pumpAndSettle();
    expect(probes, 1);
    expect(find.text('The local model service is not ready'), findsOneWidget);
    await tester.tap(find.byType(PopupMenuButton<int>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Delete'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Delete').last);
    await tester.pumpAndSettle();
    expect(port.catalog.providers, isEmpty);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
      'a single model switch saves its metadata without resubmitting a key or changing its sibling',
      (tester) async {
    final port = _Port();
    port.catalog = MbotProviderCatalog(providers: [
      MbotProvider(
          id: 'p1',
          name: 'Example',
          apiFormat: 'openai-completions',
          baseUrl: 'https://example.com/v1',
          models: ['m1', 'm2'],
          enabled: true,
          hasApiKey: true,
          modelSettings: [
            MbotModelSettings(modelId: 'm1', contextLimit: 4096),
            MbotModelSettings(modelId: 'm2')
          ])
    ]);
    await tester
        .pumpWidget(MaterialApp(home: MbotProviderSettingsPage(port: port)));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('mbot.model.toggle.p1.m1')));
    await tester.pumpAndSettle();
    expect(port.submittedKey, isNull);
    expect(port.savedDraft!.modelSettings![0].enabled, isFalse);
    expect(port.savedDraft!.modelSettings![0].contextLimit, 4096);
    expect(port.savedDraft!.modelSettings![1].enabled, isTrue);
    expect(
        tester
            .widget<PopupMenuButton<String>>(
                find.byType(PopupMenuButton<String>).first)
            .enabled,
        isFalse);
  });

  testWidgets(
      'model limits reject zero and duplicate identifiers, cancellation performs no save',
      (tester) async {
    MbotModelSettings? result;
    await tester.pumpWidget(MaterialApp(
        home: Builder(
            builder: (context) => Scaffold(
                body: TextButton(
                    onPressed: () async {
                      result = await Navigator.of(context)
                          .push<MbotModelSettings>(MaterialPageRoute(
                              builder: (_) => MbotModelEditorPage(
                                  model: MbotModelSettings(modelId: 'm1'),
                                  otherIds: const {'m2'})));
                    },
                    child: const Text('open'))))));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('mbot.model.context')), '0');
    await tester.tap(find.byKey(const Key('mbot.model.save')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('mbot.model.id')), findsOneWidget);
    await tester.enterText(find.byKey(const Key('mbot.model.context')), '8192');
    await tester.enterText(find.byKey(const Key('mbot.model.id')), 'm2');
    await tester.tap(find.byKey(const Key('mbot.model.save')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('mbot.model.id')), findsOneWidget);
    await tester.pageBack();
    await tester.pumpAndSettle();
    expect(result, isNull);
  });

  for (final width in [390.0, 1024.0]) {
    testWidgets(
        'provider and model editors scroll at width $width with an open keyboard',
        (tester) async {
      tester.view.physicalSize = Size(width, 844);
      tester.view.devicePixelRatio = 1;
      tester.view.viewInsets = const FakeViewPadding(bottom: 280);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetViewInsets);
      await tester.pumpWidget(
          MaterialApp(home: MbotProviderSettingsPage(port: _Port())));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('mbot.provider.add')));
      await tester.pumpAndSettle();
      expect(find.byType(AlertDialog), findsNothing);
      await tester.scrollUntilVisible(
          find.byKey(const Key('mbot.provider.addModel')), 200,
          scrollable: find.byType(Scrollable).first);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('mbot.provider.addModel')));
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
          find.byKey(const ValueKey('mbot.model.input.video')), 200,
          scrollable: find.byType(Scrollable).first);
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });
  }
}
