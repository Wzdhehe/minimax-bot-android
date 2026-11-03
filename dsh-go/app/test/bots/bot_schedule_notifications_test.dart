// SPDX-License-Identifier: AGPL-3.0-only
import 'package:dsh_app/bot/bot_schedule_notifications.dart';
import 'package:dsh_app/bot/bot_notification_prefs.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mbot_settings/mbot_settings.dart';

Map<String, Object?> _event(String id, {String role = 'schedule'}) => {
  'type': 'message.upsert',
  'chatId': 'bot:bot_test',
  'message': {'id': id, 'role': role, 'text': 'private prompt not used in notices'},
};

void main() {
  test('master/category gates suppress schedule notices and repeated events do not duplicate', () async {
    final store = MemoryPrefsStore();
    final prefs = MbotNotificationPrefsController(store);
    await prefs.load();
    final posted = <String>[];
    final notices = BotScheduleNotifications(
      store: () async => store,
      post: (id) async {
        posted.add(id);
        return true;
      },
    );
    await prefs.setEnabled(false);
    expect(await notices.handle(_event('msg_a')), isFalse);
    await prefs.setEnabled(true);
    await prefs.setCategory(MbotNotifyCategory.scheduleDue, false);
    expect(await notices.handle(_event('msg_b')), isFalse);
    await prefs.setCategory(MbotNotifyCategory.scheduleDue, true);
    expect(await notices.handle(_event('msg_c')), isTrue);
    expect(await notices.handle(_event('msg_c')), isFalse);
    expect(await notices.handle(_event('msg_d', role: 'bot')), isFalse);
    expect(posted, ['msg_c']);
    await store.setBool(botNotificationPrefKey('bot_a'), false);
    expect(await notices.handle({..._event('msg_e'), 'chatId': 'bot:bot_a'}), isFalse);
    expect(posted, ['msg_c']);
  });
  test('a denied native post is not reported as delivered', () async {
    final notices = BotScheduleNotifications(store: () async => MemoryPrefsStore(), post: (_) async => false);
    expect(await notices.handle(_event('msg_a')), isFalse);
  });
}
