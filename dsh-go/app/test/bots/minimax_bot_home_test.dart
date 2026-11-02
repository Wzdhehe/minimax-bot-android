import 'dart:async';

import 'package:dsh_app/bot/bot_controller.dart';
import 'package:dsh_app/bot/bot_home.dart';
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

class _Api extends MiniMaxBotApi {
  _Api() : super(Uri.parse('http://127.0.0.1:8788'));
  final pending = <String, Completer<Map<String, Object?>>>{};
  int sends = 0;
  @override
  Stream<Map<String, Object?>> events() => const Stream.empty();
  @override
  Future<Map<String, Object?>> state() async => {
    'bots': [
      {'id': 'bot_a', 'name': 'Alice', 'role': 'Research'},
      {'id': 'bot_b', 'name': 'Bob'},
    ],
    'groups': [],
  };
  @override
  Future<Map<String, Object?>> messages(String chat, {int limit = 200, String? before}) =>
      pending[chat]?.future ??
      Future.value({
        'messages': [
          {'role': 'bot', 'text': 'Hello $chat', 'status': 'done'},
        ],
      });
  @override
  Future<Map<String, Object?>> sendMessage(
    String chat,
    String text, {
    required String clientMessageId,
    List<Map<String, Object?>> attachments = const [],
  }) async {
    sends++;
    throw TimeoutException('Delivery unknown');
  }
}

void main() {
  test('late history from a previous selection does not overwrite the current chat', () async {
    final api = _Api();
    final controller = BotController(api);
    addTearDown(() {
      controller.dispose();
      api.close();
    });
    api.pending['bot:bot_a'] = Completer();
    final old = controller.select('bot:bot_a');
    await controller.select('bot:bot_b');
    api.pending['bot:bot_a']!.complete({
      'messages': [
        {'text': 'stale'},
      ],
    });
    await old;
    expect(controller.messages.single['text'], 'Hello bot:bot_b');
    expect(await controller.send('question'), isFalse);
    expect(api.sends, 1);
  });

  for (final width in [390.0, 1024.0]) {
    testWidgets('Bot conversation layout at width $width', (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = Size(width, 900);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final api = _Api();
      final controller = BotController(api);
      addTearDown(() {
        controller.dispose();
        api.close();
      });
      await tester.pumpWidget(
        MaterialApp(
          theme: ThemeData.dark(),
          home: BotHome(controller: controller),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('Alice'), findsOneWidget);
      await tester.tap(find.text('Alice'));
      await tester.pumpAndSettle();
      expect(find.text('Hello bot:bot_a'), findsOneWidget);
      expect(find.text('Bob'), width >= 840 ? findsOneWidget : findsNothing);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
    });
  }
}
