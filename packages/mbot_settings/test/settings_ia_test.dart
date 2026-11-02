import 'package:flutter_test/flutter_test.dart';
import 'package:mbot_settings/mbot_settings.dart';

void main() {
  test('IA lists the eight E.2 groups with stable titles', () {
    expect(MbotSettingsIa.groups.map((g) => g.title).toList(), [
      '账号与模型',
      '插件',
      '通知',
      '定时任务',
      '后台运行',
      '工作区与存储',
      '外观',
      '关于',
    ]);
    expect(
      MbotSettingsIa.byId(MbotSettingsGroupId.notifications).kind,
      MbotSettingsEntryKind.implemented,
    );
    expect(
      MbotSettingsIa.byId(MbotSettingsGroupId.backgroundRunning).kind,
      MbotSettingsEntryKind.implemented,
    );
    expect(
      MbotSettingsIa.groups.where((g) => g.kind == MbotSettingsEntryKind.implemented).length,
      2,
    );
  });

  test('each notify category maps to a unique channel id', () {
    final ids = MbotNotifyCategory.values.map(MbotNotificationChannels.forCategory).toSet();
    expect(ids.length, MbotNotifyCategory.values.length);
    expect(ids, containsAll(MbotNotificationChannels.all));
  });
}
