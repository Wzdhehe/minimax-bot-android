// SPDX-License-Identifier: AGPL-3.0-only
import 'dart:async';

import 'package:dsh_app/bot/bot_attachments.dart';
import 'package:dsh_app/bot/bot_controller.dart';
import 'package:dsh_app/bot/bot_home.dart';
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

class _Api extends MiniMaxBotApi {
  _Api() : super(Uri.parse('http://127.0.0.1:8788'));
  Map<String, Object?>? created;
  final sent = <List<String>>[];
  int uploads = 0;
  List<Map<String, Object?>> messageRows = [];
  bool refuseSend = false, noDefault = false;
  Completer<Map<String, Object?>>? uploadGate;
  @override
  Stream<Map<String, Object?>> events() => const Stream.empty();
  @override
  Future<Map<String, Object?>> state() async => {
    'bots': [
      {'id': 'bot_a', 'name': 'Chief', 'isMain': true},
      {'id': 'bot_b', 'name': 'Writer'},
      if (created != null) {'id': 'bot_new', ...created!},
    ],
    'groups': [],
  };
  @override
  Future<Map<String, Object?>> models() async => {
    'models': [
      {
        'providerId': 'custom_provider:p',
        'modelId': 'm1',
        'name': 'Research model',
        'thinking': {
          'efforts': ['high', 'low'],
        },
        'variants': ['thinking', ''],
      },
    ],
    if (!noDefault) 'default': {'providerId': 'custom_provider:p', 'modelId': 'm1'},
  };
  @override
  Future<Map<String, Object?>> messages(String chat, {int limit = 200, String? before}) async => {
    'messages': messageRows,
  };
  @override
  Future<Map<String, Object?>> createBot(Map<String, Object?> properties) async {
    created = properties;
    return {
      'bot': {'id': 'bot_new', ...properties},
    };
  }

  @override
  Future<Map<String, Object?>> upload(String name, Stream<List<int>> data, {required int length}) async {
    uploads++;
    expect(await data.expand((bytes) => bytes).toList(), [1, 2, 3]);
    return uploadGate?.future ??
        Future.value({'id': 'aaaaaaaaaaaa-$name', 'name': name, 'size': length, 'kind': 'file'});
  }

  @override
  Future<Map<String, Object?>> sendMessage(
    String chat,
    String text, {
    required String clientMessageId,
    List<String> attachments = const [],
  }) async {
    sent.add(attachments);
    if (refuseSend) throw TimeoutException('fixture');
    return {'message': {}};
  }
}

void main() {
  Future<void> scrollTo(WidgetTester tester, Finder finder) async {
    await tester.scrollUntilVisible(finder, 220, scrollable: find.byType(Scrollable).first);
    await tester.pumpAndSettle();
  }

  Future<BotController> mount(WidgetTester tester, _Api api, Future<List<BotPickedAttachment>> Function() pick) async {
    final controller = BotController(api);
    await tester.pumpWidget(
      MaterialApp(
        home: BotHome(controller: controller, pickAttachments: pick),
      ),
    );
    await tester.pumpAndSettle();
    addTearDown(() {
      controller.dispose();
      api.close();
    });
    return controller;
  }

  BotPickedAttachment picked() => BotPickedAttachment(name: 'notes.txt', length: 3, data: Stream.value([1, 2, 3]));

  testWidgets('creation sends actual mbot instructions, model effort, helpers, main role and artwork', (tester) async {
    final api = _Api();
    await mount(tester, api, () async => []);
    await tester.tap(find.byType(PopupMenuButton<bool>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('创建 Bot'));
    await tester.pumpAndSettle();
    expect(find.byType(AlertDialog), findsNothing);
    await tester.enterText(find.byKey(const Key('mbot.create.name')), 'invalid@name');
    await tester.pump();
    expect(tester.widget<TextButton>(find.widgetWithText(TextButton, '创建')).onPressed, isNull);
    await tester.enterText(find.byKey(const Key('mbot.create.name')), 'Researcher');
    await scrollTo(tester, find.byKey(const Key('mbot.create.instructions')));
    await tester.enterText(find.byKey(const Key('mbot.create.instructions')), 'Cite sources.');
    await scrollTo(tester, find.byKey(const Key('mbot.create.model')));
    await tester.tap(find.byKey(const Key('mbot.create.model')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Research model').last);
    await tester.pumpAndSettle();
    await scrollTo(tester, find.byKey(const ValueKey('mbot.create.effort.custom_provider:p/m1')));
    await tester.tap(find.byKey(const ValueKey('mbot.create.effort.custom_provider:p/m1')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('high').last);
    await tester.pumpAndSettle();
    await scrollTo(tester, find.byKey(const Key('mbot.create.helpers')));
    await tester.tap(find.byKey(const Key('mbot.create.helpers')));
    await tester.pump();
    await scrollTo(tester, find.byKey(const Key('mbot.create.main')));
    await tester.tap(find.byKey(const Key('mbot.create.main')));
    await tester.pump();
    await scrollTo(tester, find.byKey(const ValueKey('mbot.create.skin.gold')));
    await tester.tap(find.byKey(const ValueKey('mbot.create.skin.gold')));
    await tester.pump();
    await tester.tap(find.widgetWithText(TextButton, '创建'));
    await tester.pumpAndSettle();
    expect(api.created, containsPair('instructions', 'Cite sources.'));
    expect(api.created, containsPair('skin', 'gold'));
    expect(api.created, containsPair('isMain', true));
    expect(api.created, containsPair('autoHelper', false));
    expect(api.created!['model'], {
      'providerId': 'custom_provider:p',
      'modelId': 'm1',
      'variant': 'thinking',
      'thinking': {'effort': 'high'},
    });
  });

  testWidgets('a catalog without a default still offers an explicit model and its efforts', (tester) async {
    final api = _Api()..noDefault = true;
    await mount(tester, api, () async => []);
    await tester.tap(find.byType(PopupMenuButton<bool>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('创建 Bot'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('mbot.create.name')), 'Researcher');
    await scrollTo(tester, find.byKey(const Key('mbot.create.model')));
    expect(find.text('尚未设置默认模型。可在这里选择专用模型，或在账号与模型中设置默认模型。'), findsOneWidget);
    expect(find.byKey(const ValueKey('mbot.create.effort.')), findsNothing);
    await tester.tap(find.byKey(const Key('mbot.create.model')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Research model').last);
    await tester.pumpAndSettle();
    final effort = find.byKey(const ValueKey('mbot.create.effort.custom_provider:p/m1'));
    await scrollTo(tester, effort);
    await tester.tap(effort);
    await tester.pumpAndSettle();
    await tester.tap(find.text('high').last);
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(TextButton, '创建'));
    await tester.pumpAndSettle();
    expect(api.created!['model'], {
      'providerId': 'custom_provider:p',
      'modelId': 'm1',
      'variant': 'thinking',
      'thinking': {'effort': 'high'},
    });
    expect(api.noDefault, true);
  });

  testWidgets('an empty failed Bot reply displays safe feedback without raw provider diagnostics', (tester) async {
    final api = _Api()
      ..messageRows = [
        {'id': 'msg_1', 'role': 'bot', 'text': '', 'status': 'error', 'error': 'fixture-private-provider-error'},
      ];
    final controller = await mount(tester, api, () async => []);
    await controller.select('bot:bot_a');
    await tester.pumpAndSettle();
    expect(find.text('回复未完成，请检查供应商设置与连接后再试。'), findsOneWidget);
    expect(find.text('fixture-private-provider-error'), findsNothing);
  });

  testWidgets('attachment-only sending uses upload ids and keeps the draft after a single uncertain send', (
    tester,
  ) async {
    final api = _Api()..refuseSend = true;
    final controller = await mount(tester, api, () async => [picked()]);
    await controller.select('bot:bot_a');
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('mbot.chat.attach')));
    await tester.pumpAndSettle();
    expect(find.widgetWithText(InputChip, 'notes.txt'), findsOneWidget);
    await tester.tap(find.byTooltip('发送'));
    await tester.pumpAndSettle();
    expect(api.sent, [
      ['aaaaaaaaaaaa-notes.txt'],
    ]);
    expect(api.uploads, 1);
    expect(find.widgetWithText(InputChip, 'notes.txt'), findsOneWidget);
  });

  testWidgets('an upload finishing after switching chats stays in its original chat', (tester) async {
    final gate = Completer<Map<String, Object?>>();
    final api = _Api()..uploadGate = gate;
    final controller = await mount(tester, api, () async => [picked()]);
    await controller.select('bot:bot_a');
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('mbot.chat.attach')));
    await tester.pump();
    await controller.select('bot:bot_b');
    await tester.pump();
    gate.complete({'id': 'aaaaaaaaaaaa-notes.txt', 'name': 'notes.txt', 'size': 3, 'kind': 'file'});
    await tester.pumpAndSettle();
    expect(find.widgetWithText(InputChip, 'notes.txt'), findsNothing);
    await controller.select('bot:bot_a');
    await tester.pumpAndSettle();
    expect(find.widgetWithText(InputChip, 'notes.txt'), findsOneWidget);
    expect(api.sent, isEmpty);
  });

  testWidgets('oversized selections fail before opening their stream or making an upload', (tester) async {
    final api = _Api();
    var opened = false;
    final controller = await mount(
      tester,
      api,
      () async => [
        BotPickedAttachment(
          name: 'large.bin',
          length: MiniMaxBotApi.maxUploadBytes + 1,
          data: Stream<List<int>>.multi((sink) {
            opened = true;
            sink.close();
          }),
        ),
      ],
    );
    await controller.select('bot:bot_a');
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('mbot.chat.attach')));
    await tester.pumpAndSettle();
    expect(api.uploads, 0);
    expect(opened, false);
    expect(find.textContaining('16 MiB'), findsOneWidget);
  });
}
