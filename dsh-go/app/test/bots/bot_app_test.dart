// SPDX-License-Identifier: AGPL-3.0-only
import 'dart:async';

import 'package:dsh_app/bot/bot_app.dart';
import 'package:dsh_app/bot/bot_home.dart';
import 'package:dsh_app/bot/bot_runtime_endpoint.dart';
import 'package:dsh_app/bot/bot_settings_hub.dart';
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// A service that answers without a socket, and counts how often it was closed.
class _Api extends MiniMaxBotApi {
  _Api({String session = 'fixture-session', this.name = 'Alice'})
    : super(Uri.parse('http://127.0.0.1:8788'), headers: {'Authorization': 'Bearer $session'});
  int closes = 0;
  int stateReads = 0;
  String name;
  Object? failure;
  @override
  Stream<Map<String, Object?>> events() => const Stream.empty();
  @override
  Future<Map<String, Object?>> state() async {
    stateReads++;
    if (failure != null) throw failure!;
    return {
      'bots': [
        {'id': 'bot_a', 'name': name},
      ],
      'groups': [],
    };
  }

  @override
  void close() {
    closes++;
    super.close();
  }
}

/// The phone's own answer to "is a Bot service running?", counted.
class _Endpoint extends BotRuntimeEndpoint {
  _Endpoint({this.service});
  MiniMaxBotApi? service;

  /// When set, the phone fails to answer.
  Object? failure;

  /// How often the phone was asked.
  int reads = 0;
  int starts = 0;
  @override
  Future<void> ensureRunning() async {
    starts++;
  }

  /// How many answers were in flight at the same time.
  int running = 0;
  int peak = 0;

  /// Reads that wait for the test to let them answer.
  final gates = <Completer<MiniMaxBotApi?>>[];

  @override
  Future<MiniMaxBotApi?> liveService() async {
    reads++;
    running++;
    if (running > peak) peak = running;
    try {
      if (gates.isNotEmpty) {
        final gate = gates.removeAt(0);
        return await gate.future;
      }
      final failure = this.failure;
      if (failure != null) throw failure;
      return service;
    } finally {
      running--;
    }
  }
}

/// A settings page that only says it was there.
class _Settings extends StatelessWidget {
  const _Settings();
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('设置')),
    body: Center(
      child: TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('返回')),
    ),
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    SharedPreferences.setMockInitialValues({});
  });

  Future<void> pumpUntil(WidgetTester tester, bool Function() condition, {int frames = 60}) async {
    for (var step = 0; step < frames && !condition(); step++) {
      await tester.pump(const Duration(milliseconds: 50));
    }
    expect(condition(), isTrue, reason: 'the page did not reach the expected state');
  }

  Widget app(BotRuntimeEndpoint endpoint, {WidgetBuilder? settings}) => MaterialApp(
    theme: ThemeData.dark(),
    home: BotEntry(endpoint: endpoint, settingsPage: settings),
  );

  Finder reconnect() => find.widgetWithText(FilledButton, '重新连接');
  Finder settingsButton() => find.widgetWithText(OutlinedButton, '设置');

  testWidgets('without a running service the entry says so and offers a way out', (tester) async {
    final endpoint = _Endpoint();
    await tester.pumpWidget(app(endpoint));
    await pumpUntil(tester, () => find.text('本机没有运行中的 Bot 服务').evaluate().isNotEmpty);

    expect(find.byType(BotHome), findsNothing);
    expect(reconnect(), findsOneWidget);
    expect(settingsButton(), findsOneWidget);
    // The phone was asked once, and only for the service.
    expect(endpoint.reads, 1);
  });

  testWidgets('a running service opens the official Bot interface', (tester) async {
    final api = _Api();
    final endpoint = _Endpoint(service: api);
    await tester.pumpWidget(app(endpoint));
    await pumpUntil(tester, () => find.byType(BotHome).evaluate().isNotEmpty);
    await pumpUntil(tester, () => find.text('Alice').evaluate().isNotEmpty);

    expect(find.text('Alice'), findsOneWidget);
    // The entry itself is gone; the Bot list is what is shown.
    expect(find.text('本机没有运行中的 Bot 服务'), findsNothing);
  });

  testWidgets('a phone that cannot answer is a failure, not an empty page', (tester) async {
    final endpoint = _Endpoint()..failure = const FormatException('descriptor incomplete');
    await tester.pumpWidget(app(endpoint));
    await pumpUntil(tester, () => find.text('无法连接本机 Bot 服务').evaluate().isNotEmpty);

    expect(find.text('本机没有运行中的 Bot 服务'), findsNothing);
    expect(reconnect(), findsOneWidget);
    expect(settingsButton(), findsOneWidget);
  });

  testWidgets('the Bot banner reconnect obtains the newly published endpoint and releases the failed run', (
    tester,
  ) async {
    final old = _Api(session: 'old');
    final fresh = _Api(session: 'new', name: 'Recovered');
    final endpoint = _Endpoint(service: old);
    await tester.pumpWidget(app(endpoint));
    await pumpUntil(tester, () => find.text('Alice').evaluate().isNotEmpty);
    final controller = tester.widget<BotHome>(find.byType(BotHome)).controller;
    old.failure = StateError('fixture-old-service-stopped');
    await controller.refresh();
    await tester.pump();
    expect(find.byType(MaterialBanner), findsOneWidget);
    endpoint.service = fresh;
    final ready = Completer<MiniMaxBotApi?>();
    endpoint.gates.add(ready);
    final reads = old.stateReads;
    await tester.tap(find.descendant(of: find.byType(MaterialBanner), matching: find.byType(TextButton)));
    await tester.pump();
    expect(tester.widget<PopupMenuButton<bool>>(find.byType(PopupMenuButton<bool>)).enabled, isFalse);
    ready.complete(fresh);
    await pumpUntil(tester, () => find.text('Recovered').evaluate().isNotEmpty);
    expect(endpoint.reads, 2);
    expect(old.stateReads, reads, reason: 'a retired endpoint must not be polled to validate the new run');
    expect(old.closes, 1);
    expect(fresh.closes, 0);
    expect(find.byType(MaterialBanner), findsNothing);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('leaving Bot settings adopts a new token even when the old port still answers', (tester) async {
    final old = _Api(session: 'old');
    final fresh = _Api(session: 'new', name: 'New run');
    final endpoint = _Endpoint(service: old);
    await tester.pumpWidget(app(endpoint));
    await pumpUntil(tester, () => find.text('Alice').evaluate().isNotEmpty);
    await tester.tap(find.byTooltip('设置'));
    await tester.pumpAndSettle();
    expect(find.byType(BotSettingsHub), findsOneWidget);
    final reads = old.stateReads;
    endpoint.service = fresh;
    Navigator.of(tester.element(find.byType(BotSettingsHub))).pop();
    await pumpUntil(tester, () => find.text('New run').evaluate().isNotEmpty);
    expect(endpoint.reads, 2);
    expect(old.stateReads, reads);
    expect(old.closes, 1);
    expect(fresh.closes, 0);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('reconnecting the same run reopens SSE without closing the client it still owns', (tester) async {
    final api = _Api();
    final endpoint = _Endpoint(service: api);
    await tester.pumpWidget(app(endpoint));
    await pumpUntil(tester, () => find.text('Alice').evaluate().isNotEmpty);
    await tester.tap(find.widgetWithText(TextButton, '重新连接'));
    await pumpUntil(tester, () => endpoint.reads == 2);
    expect(api.closes, 0);
    await tester.pumpWidget(const SizedBox());
    expect(api.closes, 1);
  });

  testWidgets('the settings are reachable without a running service', (tester) async {
    final endpoint = _Endpoint();
    await tester.pumpWidget(app(endpoint));
    await pumpUntil(tester, () => settingsButton().evaluate().isNotEmpty);

    await tester.tap(settingsButton());
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    // The real settings page: the key, the base URL and the model live there.
    expect(find.byType(BotSettingsHub), findsOneWidget);
  });

  testWidgets('the phone is asked again after the settings close', (tester) async {
    final endpoint = _Endpoint();
    await tester.pumpWidget(app(endpoint, settings: (_) => const _Settings()));
    await pumpUntil(tester, () => settingsButton().evaluate().isNotEmpty);
    expect(endpoint.reads, 1);

    await tester.tap(settingsButton());
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.byType(_Settings), findsOneWidget);

    Navigator.of(tester.element(find.byType(_Settings))).pop();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    // The service may have started while the settings were open.
    expect(endpoint.reads, 2);
  });

  testWidgets('a refresh asked for while the phone answers waits its turn', (tester) async {
    final endpoint = _Endpoint();
    final gate = Completer<MiniMaxBotApi?>();
    endpoint.gates.add(gate);
    await tester.pumpWidget(app(endpoint, settings: (_) => const _Settings()));
    await tester.pump();
    expect(endpoint.reads, 1);

    // The settings open and close while the phone is still answering.
    await tester.tap(find.byIcon(Icons.settings_outlined));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    Navigator.of(tester.element(find.byType(_Settings))).pop();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    expect(endpoint.reads, 1, reason: 'a second read ran while the first one was still in flight');

    gate.complete(null);
    await pumpUntil(tester, () => endpoint.reads > 1);

    // One more read, and never two at once.
    expect(endpoint.reads, 2);
    expect(endpoint.peak, 1);
  });

  testWidgets('leaving the page lets go of its client and leaves the engine alone', (tester) async {
    final api = _Api();
    final endpoint = _Endpoint(service: api);
    await tester.pumpWidget(app(endpoint));
    await pumpUntil(tester, () => find.byType(BotHome).evaluate().isNotEmpty);

    // The page goes away.
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();

    expect(api.closes, 1);
  });

  testWidgets('the entry requests native start and its descriptor without stopping the service', (tester) async {
    const channel = MethodChannel('minimax/bot-runtime');
    final calls = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call.method);
      return null;
    });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null),
    );

    await tester.pumpWidget(app(const BotRuntimeEndpoint()));
    await pumpUntil(tester, () => find.text('本机没有运行中的 Bot 服务').evaluate().isNotEmpty);

    expect(calls, ['start', 'descriptor']);
    await tester.pumpWidget(const SizedBox());
    expect(calls, ['start', 'descriptor']);
  });
}
