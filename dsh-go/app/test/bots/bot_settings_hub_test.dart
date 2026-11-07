// SPDX-License-Identifier: AGPL-3.0-only
import 'package:dsh_app/bot/bot_settings_hub.dart';
import 'package:dsh_app/bot/bot_runtime_endpoint.dart';
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mbot_settings/mbot_settings.dart';

class _ProbeApi extends MiniMaxBotApi {
  _ProbeApi() : super(Uri.parse('http://127.0.0.1:8788'));
  int probes = 0;
  bool closed = false;
  @override
  Future<Map<String, Object?>> testProvider(String provider, String model) async {
    probes++;
    return {'ok': true};
  }

  @override
  void close() {
    closed = true;
    super.close();
  }
}

class _Endpoint extends BotRuntimeEndpoint {
  _Endpoint(this.current);
  MiniMaxBotApi? current;
  int reads = 0;
  @override
  Future<MiniMaxBotApi?> liveService() async {
    reads++;
    return current;
  }
}

void main() {
  testWidgets('settings start without an engine and preserve notification switches', (tester) async {
    final store = MemoryPrefsStore();
    final platform = FakeMbotPlatform(notificationsEnabled: true);
    await tester.pumpWidget(
      MaterialApp(
        home: BotSettingsHub(store: store, platform: platform),
      ),
    );
    await tester.pumpAndSettle();
    for (final group in MbotSettingsIa.groups) {
      expect(
        find.byKey(Key('mbot.settings.hub.${group.id.name}')),
        group.id == MbotSettingsGroupId.schedules ? findsNothing : findsOneWidget,
      );
    }
    expect(find.textContaining('远控'), findsNothing);
    expect(find.byKey(const Key('mbot.settings.hub.schedules')), findsNothing);
    expect(find.textContaining('手机操控'), findsNothing);
    expect(platform.calls.where((call) => call.startsWith('open')), isEmpty);
    await tester.tap(find.byKey(const Key('mbot.settings.hub.notifications')));
    await tester.pumpAndSettle();
    final master = find.byKey(const Key('mbot.settings.notify.master'));
    await tester.tap(find.descendant(of: master, matching: find.byType(Switch)));
    await tester.pumpAndSettle();
    expect(store.snapshot['mbot_notify_enabled'], isFalse);
    for (final category in MbotNotifyCategory.values) {
      expect(tester.widget<SwitchListTile>(find.byKey(Key('mbot.settings.notify.${category.name}'))).onChanged, isNull);
    }
    await tester.pageBack();
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('mbot.settings.hub.backgroundRunning')));
    await tester.pumpAndSettle();
    expect(find.textContaining('进程被系统杀掉之后，定时任务不会执行'), findsOneWidget);
    expect(platform.calls.where((call) => call.startsWith('open')), isEmpty);
  });
  testWidgets('provider tests resolve the current native endpoint instead of retaining the settings client', (
    tester,
  ) async {
    const settingsChannel = MethodChannel('minimax/bot-settings');
    final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(settingsChannel, (_) async => {'providers': []});
    addTearDown(() => messenger.setMockMethodCallHandler(settingsChannel, null));
    final old = _ProbeApi(), current = _ProbeApi();
    final endpoint = _Endpoint(current);
    addTearDown(old.close);
    await tester.pumpWidget(
      MaterialApp(
        home: BotSettingsHub(store: MemoryPrefsStore(), platform: FakeMbotPlatform(), botApi: old, endpoint: endpoint),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('mbot.settings.hub.accountAndModel')));
    await tester.pumpAndSettle();
    final probe = tester.widget<MbotProviderSettingsPage>(find.byType(MbotProviderSettingsPage)).testProvider!;
    expect(await probe('p', 'm'), {'ok': true});
    expect(endpoint.reads, 1);
    expect(old.probes, 0);
    expect(old.closed, false);
    expect(current.probes, 1);
    expect(current.closed, true);
    endpoint.current = null;
    expect(await probe('p', 'm'), {'ok': false, 'code': 'provider_unavailable'});
    expect(endpoint.reads, 2);
    expect(old.probes, 0);
  });
}
