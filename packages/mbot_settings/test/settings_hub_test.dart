import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mbot_settings/mbot_settings.dart';

void main() {
  late FakeMbotPlatform platform;
  late MemoryPrefsStore store;
  late MbotNotificationPrefsController prefs;

  setUp(() async {
    platform = FakeMbotPlatform();
    store = MemoryPrefsStore();
    prefs = MbotNotificationPrefsController(store);
    await prefs.load();
  });

  Future<void> pumpHub(WidgetTester tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: MbotSettingsHubPage(platform: platform, notificationPrefs: prefs),
      ),
    );
  }

  testWidgets('hub lists every E.2 group', (tester) async {
    await pumpHub(tester);

    expect(find.byKey(const Key('mbot.settings.hub.list')), findsOneWidget);
    for (final group in MbotSettingsIa.groups) {
      expect(find.byKey(Key('mbot.settings.hub.${group.id.name}')), findsOneWidget);
      expect(find.text(group.title), findsOneWidget);
    }
    expect(find.text('即将实现'), findsNWidgets(
      MbotSettingsIa.groups.where((g) => g.kind == MbotSettingsEntryKind.placeholder).length,
    ));
  });

  testWidgets('notifications entry opens notify page', (tester) async {
    await pumpHub(tester);
    await tester.tap(find.byKey(const Key('mbot.settings.hub.notifications')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('mbot.settings.notify.list')), findsOneWidget);
    expect(find.byKey(const Key('mbot.settings.notify.master')), findsOneWidget);
  });

  testWidgets('background entry opens background page', (tester) async {
    await pumpHub(tester);
    await tester.tap(find.byKey(const Key('mbot.settings.hub.backgroundRunning')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('mbot.settings.background.list')), findsOneWidget);
    expect(find.byKey(const Key('mbot.settings.background.disclaimer')), findsOneWidget);
  });
}
