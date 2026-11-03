// SPDX-License-Identifier: AGPL-3.0-only
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mbot_settings/mbot_settings.dart';

class _QuotaPort implements MbotUsageQuotaPort {
  _QuotaPort(this.value);
  MbotUsageQuota value;
  bool forced = false;
  @override
  Future<MbotUsageQuota> load({bool forceRefresh = false}) async {
    forced = forceRefresh;
    return value;
  }
}

void main() {
  test(
      'quota projection rejects credential fields and does not invent numeric limits',
      () {
    expect(() => MbotUsageQuota.fromMap({'signedIn': true, 'apiKey': 'secret'}),
        throwsFormatException);
    final value = MbotUsageQuota.fromMap({
      'signedIn': true,
      'quota': {
        'fiveHour': {'usedPercent': 200},
        'weekly': {'usedPercent': double.nan}
      }
    });
    expect(value.fiveHour?.usedPercent, isNull);
    expect(value.weekly?.usedPercent, isNull);
    expect(MbotSettingsIa.byId(MbotSettingsGroupId.accountAndModel).items,
        contains(MbotSettingsItemId.usageQuota));
  });
  for (final supported in [true, false]) {
    testWidgets(
        'signed out and third-party states remain distinct ($supported)',
        (tester) async {
      final port =
          _QuotaPort(MbotUsageQuota(signedIn: false, supported: supported));
      await tester
          .pumpWidget(MaterialApp(home: MbotUsageQuotaPage(port: port)));
      await tester.pumpAndSettle();
      expect(find.textContaining(supported ? '未登录 MiniMax' : '当前供应商不支持'),
          findsOneWidget);
      expect(find.byType(LinearProgressIndicator), findsNothing);
      await tester.tap(find.byTooltip('刷新'));
      await tester.pumpAndSettle();
      expect(port.forced, isTrue);
    });
  }
  testWidgets(
      'fixture account renders plan, credits and both percentage windows',
      (tester) async {
    final port = _QuotaPort(const MbotUsageQuota(
        signedIn: true,
        planName: 'M Plan',
        creditBalance: '123.45',
        fiveHour: MbotQuotaWindow(usedPercent: 17),
        weekly: MbotQuotaWindow(usedPercent: 32)));
    await tester.pumpWidget(MaterialApp(home: MbotUsageQuotaPage(port: port)));
    await tester.pumpAndSettle();
    expect(find.text('M Plan'), findsOneWidget);
    expect(find.text('积分余额：123.45'), findsOneWidget);
    expect(find.text('已用 17%'), findsOneWidget);
    expect(find.text('已用 32%'), findsOneWidget);
    expect(find.byType(LinearProgressIndicator), findsNWidgets(2));
  });
}
