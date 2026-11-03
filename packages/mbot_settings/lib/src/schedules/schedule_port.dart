// SPDX-License-Identifier: AGPL-3.0-only
class MbotScheduleData {
  const MbotScheduleData({required this.bots, required this.schedules});
  final List<Map<String, Object?>> bots;
  final List<Map<String, Object?>> schedules;
}

abstract class MbotSchedulePort {
  Future<MbotScheduleData> load();
  Future<void> save(Map<String, Object?> schedule);
  Future<void> delete(String id);
  Future<void> run(String id);
}
