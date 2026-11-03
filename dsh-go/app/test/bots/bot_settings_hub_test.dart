// SPDX-License-Identifier: AGPL-3.0-only
import 'package:dsh_app/bot/bot_settings_hub.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mbot_settings/mbot_settings.dart';

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
      expect(find.byKey(Key('mbot.settings.hub.${group.id.name}')), findsOneWidget);
    }
    expect(find.textContaining('远控'), findsNothing);
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
}
