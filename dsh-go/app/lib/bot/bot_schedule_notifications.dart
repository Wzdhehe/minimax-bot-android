// SPDX-License-Identifier: AGPL-3.0-only
import 'package:flutter/services.dart';
import 'package:mbot_settings/mbot_settings.dart';

import '../l10n/l10n.dart';
import 'bot_notification_prefs.dart';

/// Process-lifetime schedule notices. No replay, alarm, keep-alive or catch-up.
class BotScheduleNotifications {
  BotScheduleNotifications({Future<PrefsStore> Function()? store, Future<bool> Function(String)? post})
    : _store = store ?? SharedPreferencesPrefsStore.create,
      _post = post ?? _nativePost;
  final Future<PrefsStore> Function() _store;
  final Future<bool> Function(String) _post;
  final _seen = <String>{};
  static const _channel = MethodChannel('mbot/system-settings');
  static Future<bool> _nativePost(String id) async {
    await _channel.invokeMethod<void>('ensureChannels', [
      for (final spec in NotificationChannelSpec.mbotDefaults())
        {'id': spec.id, 'name': spec.name, 'description': spec.description, 'importance': spec.importance},
    ]);
    return await _channel.invokeMethod<bool>('postSchedule', {
          'id': id,
          'title': appL10n.mobileBotScheduleNoticeTitle,
          'body': appL10n.mobileBotScheduleNoticeBody,
        }) ??
        false;
  }

  Future<bool> handle(Map<String, Object?> event) async {
    final message = event['message'];
    if (event['type'] != 'message.upsert' || message is! Map || message['role'] != 'schedule') return false;
    final chat = event['chatId'];
    if (chat is! String || !RegExp(r'^bot:bot_[A-Za-z0-9]+$').hasMatch(chat)) return false;
    final id = message['id'];
    if (id is! String || !RegExp(r'^msg_[A-Za-z0-9]+$').hasMatch(id) || !_seen.add(id)) return false;
    if (_seen.length > 256) _seen.remove(_seen.first);
    try {
      final store = await _store();
      final prefs = MbotNotificationPrefsController(store);
      await prefs.load();
      if (!prefs.value.allows(MbotNotifyCategory.scheduleDue)) return false;
      if (await store.getBool(botNotificationPrefKey(chat.substring(4))) == false) {
        return false;
      }
      return await _post(id);
    } catch (_) {
      return false;
    }
  }
}
