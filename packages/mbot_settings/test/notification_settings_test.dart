import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mbot_settings/mbot_settings.dart';

void main() {
  late FakeMbotPlatform platform;
  late MemoryPrefsStore store;
  late MbotNotificationPrefsController prefs;

  setUp(() async {
    platform = FakeMbotPlatform(notificationsEnabled: true);
    store = MemoryPrefsStore();
    prefs = MbotNotificationPrefsController(store);
    await prefs.load();
  });

  Future<void> pumpPage(WidgetTester tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: MbotNotificationSettingsPage(platform: platform, prefs: prefs),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('master off greys out category switches', (tester) async {
    await pumpPage(tester);

    final master = find.byKey(const Key('mbot.settings.notify.master'));
    expect(tester.widget<SwitchListTile>(master).value, isTrue);

    await tester.tap(find.descendant(of: master, matching: find.byType(Switch)));
    await tester.pumpAndSettle();

    expect(prefs.value.enabled, isFalse);
    for (final category in MbotNotifyCategory.values) {
      final categoryFinder = find.byKey(Key('mbot.settings.notify.${category.name}'));
      expect(categoryFinder, findsOneWidget, reason: '${category.name} tile missing');
      final tile = tester.widget<SwitchListTile>(categoryFinder);
      expect(tile.onChanged, isNull, reason: '${category.name} should be disabled');
    }
  });

  testWidgets('category switches persist through PrefsStore', (tester) async {
    await pumpPage(tester);

    await tester.tap(find.byKey(const Key('mbot.settings.notify.scheduleDue')));
    await tester.pumpAndSettle();
    expect(prefs.value.scheduleDue, isFalse);
    expect(store.snapshot['mbot_notify_schedule_due'], isFalse);

    final reloaded = MbotNotificationPrefsController(store);
    await reloaded.load();
    expect(reloaded.value.scheduleDue, isFalse);
    expect(reloaded.value.enabled, isTrue);
  });

  testWidgets('channel row asks platform to open matching channel id', (tester) async {
    await pumpPage(tester);
    await tester.tap(find.byKey(const Key('mbot.settings.notify.channel.scheduleDue')));
    await tester.pumpAndSettle();
    expect(platform.openedChannels, contains(MbotNotificationChannels.scheduleDue));
    expect(platform.ensuredChannels.map((c) => c.id), containsAll(MbotNotificationChannels.all));
  });

  test('allows() respects master and category', () {
    const on = MbotNotificationPrefs();
    expect(on.allows(MbotNotifyCategory.scheduleDue), isTrue);
    expect(const MbotNotificationPrefs(enabled: false).allows(MbotNotifyCategory.scheduleDue), isFalse);
    expect(const MbotNotificationPrefs(scheduleDue: false).allows(MbotNotifyCategory.scheduleDue), isFalse);
  });
}
