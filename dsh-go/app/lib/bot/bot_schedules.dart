// SPDX-License-Identifier: AGPL-3.0-only
import 'package:dsh_client/dsh_client.dart';
import 'package:mbot_settings/mbot_settings.dart';

/// A Bot's profile owns its task editor. The shared mbot service remains the
/// authoritative store, including schedules created by its MCP tools.
class BotSchedulesPort implements MbotSchedulePort {
  const BotSchedulesPort(this.api, this.botId);
  final MiniMaxBotApi api;
  final String botId;
  List<Map<String, Object?>> _rows(Object? value) =>
      value is List ? value.whereType<Map>().map((row) => Map<String, Object?>.from(row)).toList() : [];
  @override
  Future<MbotScheduleData> load() async {
    final state = await api.state();
    return MbotScheduleData(
      bots: _rows(state['bots']).where((b) => b['id'] == botId).toList(),
      schedules: _rows(state['schedules']).where((s) => s['botId'] == botId).toList(),
    );
  }

  Future<void> _check(String id) async {
    final data = await load();
    if (!data.schedules.any((row) => row['id'] == id)) throw StateError('Schedule does not belong to this Bot');
  }

  @override
  Future<void> save(Map<String, Object?> schedule) async {
    if (schedule['botId'] != botId) throw ArgumentError('Incorrect Bot');
    if (schedule['id'] != null) await _check(schedule['id'] as String);
    await api.createSchedule({...schedule, 'botId': botId});
  }

  @override
  Future<void> delete(String id) async {
    await _check(id);
    await api.deleteSchedule(id);
  }

  @override
  Future<void> run(String id) async {
    await _check(id);
    await api.runSchedule(id);
  }
}
