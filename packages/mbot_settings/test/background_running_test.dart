import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mbot_settings/mbot_settings.dart';

void main() {
  testWidgets('shows notification and battery status from fake platform', (tester) async {
    final platform = FakeMbotPlatform(
      notificationsEnabled: false,
      batteryOptimizationIgnored: false,
      vendor: const VendorBackgroundInfo(
        vendorKey: 'xiaomi',
        displayName: '小米 / HyperOS',
        steps: ['设置 → 应用设置 → 应用管理 → M Bot → 省电策略 → 无限制'],
      ),
    );

    await tester.pumpWidget(
      MaterialApp(home: MbotBackgroundRunningPage(platform: platform)),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('mbot.settings.background.list')), findsOneWidget);
    expect(find.textContaining('未授予'), findsOneWidget);
    expect(find.textContaining('未忽略'), findsOneWidget);
    expect(find.textContaining('关掉通知权限'), findsOneWidget);
    expect(find.textContaining('定时任务不会执行'), findsWidgets);
    expect(find.textContaining('不会承诺'), findsOneWidget);
    expect(find.textContaining('小米 / HyperOS'), findsOneWidget);
    expect(find.byKey(const Key('mbot.settings.background.vendor.step.0')), findsOneWidget);

    // No auto permission prompts on open.
    expect(platform.calls.where((c) => c.startsWith('open')), isEmpty);

    await tester.tap(find.byKey(const Key('mbot.settings.background.battery.open')));
    await tester.pumpAndSettle();
    expect(platform.calls, contains('openBatteryOptimizationSettings'));
    expect(find.textContaining('已忽略'), findsOneWidget);
  });

  testWidgets('generic vendor copy when vendor is null', (tester) async {
    final platform = FakeMbotPlatform(
      notificationsEnabled: true,
      batteryOptimizationIgnored: true,
      vendor: null,
    );

    await tester.pumpWidget(
      MaterialApp(home: MbotBackgroundRunningPage(platform: platform)),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('mbot.settings.background.vendor.generic')), findsOneWidget);
    expect(find.textContaining('已授予'), findsOneWidget);
    expect(find.textContaining('已忽略'), findsOneWidget);
  });
}
