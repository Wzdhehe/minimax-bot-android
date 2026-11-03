// SPDX-License-Identifier: AGPL-3.0-only
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mbot_settings/mbot_settings.dart';

void main() {
  Widget frame(
          {bool reduced = false, bool visible = true, bool loading = true}) =>
      MaterialApp(
          home: MediaQuery(
        data: MediaQueryData(disableAnimations: reduced),
        child: TickerMode(
            enabled: visible,
            child: Scaffold(
                body: MbotLoadTransition(
                    loading: loading, child: const Text('Ready')))),
      ));

  testWidgets('skeleton stops ticking for reduced motion and offscreen routes',
      (tester) async {
    await tester.pumpWidget(frame());
    await tester.pump(const Duration(milliseconds: 50));
    expect(tester.binding.transientCallbackCount, greaterThan(0));
    await tester.pumpWidget(frame(reduced: true));
    await tester.pump();
    expect(tester.binding.transientCallbackCount, 0);
    await tester.pumpWidget(frame(visible: false));
    await tester.pump();
    expect(tester.binding.transientCallbackCount, 0);
    await tester.pumpWidget(frame());
    await tester.pump();
    expect(tester.binding.transientCallbackCount, greaterThan(0));
  });

  testWidgets(
      'backgrounding pauses pulse; loaded content has no persistent ticker',
      (tester) async {
    await tester.pumpWidget(frame());
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    await tester.pump();
    expect(tester.binding.transientCallbackCount, 0);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();
    expect(tester.binding.transientCallbackCount, greaterThan(0));
    await tester.pumpWidget(frame(loading: false));
    await tester.pumpAndSettle();
    expect(find.text('Ready'), findsOneWidget);
    expect(find.byType(MbotSkeleton), findsNothing);
    expect(tester.binding.transientCallbackCount, 0);
    expect(tester.takeException(), isNull);
  });

  testWidgets('reduced motion swaps loading content immediately',
      (tester) async {
    await tester.pumpWidget(frame(reduced: true));
    await tester.pumpWidget(frame(reduced: true, loading: false));
    expect(find.text('Ready'), findsOneWidget);
    expect(find.byType(MbotSkeleton), findsNothing);
    expect(tester.binding.transientCallbackCount, 0);
  });

  testWidgets('streaming content does not restart the pane entrance',
      (tester) async {
    Widget pane(String id, String text) => MaterialApp(
        home: Scaffold(
            body: MbotPaneTransition(identity: id, child: Text(text))));
    await tester.pumpWidget(pane('chat_a', 'first token'));
    await tester.pumpAndSettle();
    await tester.pumpWidget(pane('chat_a', 'latest token'));
    expect(find.text('latest token'), findsOneWidget);
    expect(tester.binding.transientCallbackCount, 0);
    await tester.pumpWidget(pane('chat_b', 'another chat'));
    expect(tester.binding.transientCallbackCount, greaterThan(0));
    await tester.pumpAndSettle();
    expect(tester.binding.transientCallbackCount, 0);
  });
}
