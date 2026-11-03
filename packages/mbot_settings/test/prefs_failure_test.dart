import 'package:flutter_test/flutter_test.dart';
import 'package:mbot_settings/mbot_settings.dart';

class _FailingStore implements PrefsStore {
  @override
  Future<bool?> getBool(String key) async => null;
  @override
  Future<void> setBool(String key, bool value) async => throw StateError('disk unavailable');
}

void main() {
  test('failed persistence does not publish a notification setting as saved', () async {
    final controller = MbotNotificationPrefsController(_FailingStore());
    await controller.load();
    var changes = 0;
    controller.addListener((_) => changes++);
    await expectLater(controller.setEnabled(false), throwsStateError);
    expect(controller.value.enabled, isTrue);
    expect(changes, 0);
  });
}
