// SPDX-License-Identifier: AGPL-3.0-only
import 'package:dsh_app/bot/bot_notification_prefs.dart';
import 'package:dsh_app/bot/bot_profile.dart';
import 'package:dsh_app/bot/bot_schedules.dart';
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mbot_settings/mbot_settings.dart';

class _Api extends MiniMaxBotApi {
  _Api() : super(Uri.parse('http://127.0.0.1:8788'));
  final rows = <Map<String, Object?>>[
    {
      'id': 'sch_a',
      'botId': 'bot_a',
      'name': 'A task',
      'prompt': 'A',
      'kind': 'daily',
      'time': '09:00',
      'enabled': true,
    },
    {
      'id': 'sch_b',
      'botId': 'bot_b',
      'name': 'B task',
      'prompt': 'B',
      'kind': 'daily',
      'time': '09:00',
      'enabled': true,
    },
  ];
  int mutations = 0;
  @override
  Future<Map<String, Object?>> state() async => {
    'bots': [
      {'id': 'bot_a', 'name': 'Alice'},
      {'id': 'bot_b', 'name': 'Bob'},
    ],
    'schedules': rows,
  };
  @override
  Future<Map<String, Object?>> createSchedule(Map<String, Object?> schedule) async {
    mutations++;
    final id = schedule['id'] ?? 'sch_new';
    rows.removeWhere((row) => row['id'] == id);
    rows.add({...schedule, 'id': id});
    return {'schedule': rows.last};
  }

  @override
  Future<Map<String, Object?>> deleteSchedule(String id) async {
    mutations++;
    rows.removeWhere((row) => row['id'] == id);
    return {'ok': true};
  }

  @override
  Future<Map<String, Object?>> runSchedule(String id) async {
    mutations++;
    return {'ok': true};
  }
}

void main() {
  test('profile task port scopes reads and refuses accidental cross-Bot mutations', () async {
    final api = _Api();
    addTearDown(api.close);
    final port = BotSchedulesPort(api, 'bot_a');
    final data = await port.load();
    expect(data.bots.single['id'], 'bot_a');
    expect(data.schedules.single['id'], 'sch_a');
    await expectLater(port.delete('sch_b'), throwsStateError);
    await expectLater(port.run('sch_b'), throwsStateError);
    await expectLater(port.save({'id': 'sch_b', 'botId': 'bot_a'}), throwsStateError);
    await expectLater(port.save({'botId': 'bot_b'}), throwsArgumentError);
    expect(api.mutations, 0);
  });
  testWidgets('Bot profile opens its own automations and persists its notification preference', (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(390, 900);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);
    final api = _Api();
    addTearDown(api.close);
    final store = MemoryPrefsStore();
    await tester.pumpWidget(
      MaterialApp(
        theme: ThemeData.dark(),
        home: BotProfilePage(api: api, botId: 'bot_a', store: store),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('Alice'), findsOneWidget);
    expect(find.text('1 已开启'), findsOneWidget);
    expect(find.text('电脑'), findsNothing);
    final notify = find.byKey(const Key('mbot.bot.notifications'));
    await tester.tap(find.descendant(of: notify, matching: find.byType(Switch)));
    await tester.pumpAndSettle();
    expect(store.snapshot[botNotificationPrefKey('bot_a')], isFalse);
    await tester.tap(find.byKey(const Key('mbot.bot.automations')));
    await tester.pumpAndSettle();
    expect(find.text('A task'), findsOneWidget);
    expect(find.text('B task'), findsNothing);
    expect(find.text('执行 Bot'), findsNothing);
    await tester.tap(find.byKey(const Key('mbot.schedule.add')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('mbot.schedule.name')), 'New task');
    await tester.enterText(find.byKey(const Key('mbot.schedule.prompt')), 'New prompt');
    await tester.tap(find.byKey(const Key('mbot.schedule.save')));
    await tester.pumpAndSettle();
    expect(find.text('New task'), findsOneWidget);
    expect(api.rows.last['botId'], 'bot_a');
    await tester.pageBack();
    await tester.pumpAndSettle();
    expect(find.text('2 已开启'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
