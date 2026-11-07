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

  /// Every create call, in the order the page made them.
  final creates = <String>[];

  /// When set, a create waits for it before answering.
  Completer<Map<String, Object?>>? gate;

  /// When set, a create fails with it.
  Object? failure;

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
  Future<Map<String, Object?>> models() async => {'models': []};
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
    List<String> attachments = const [],
  }) async {
    sends++;
    throw TimeoutException('Delivery unknown');
  }

  @override
  Future<Map<String, Object?>> createBot(Map<String, Object?> properties) async {
    creates.add('bot');
    final wait = gate;
    if (wait != null) await wait.future;
    final error = failure;
    if (error != null) throw error;
    return {
      'bot': {'id': 'bot_new', 'name': properties['name'], 'role': properties['role']},
    };
  }

  @override
  Future<Map<String, Object?>> createGroup(Map<String, Object?> properties) async {
    creates.add('group');
    final wait = gate;
    if (wait != null) await wait.future;
    final error = failure;
    if (error != null) throw error;
    return {
      'group': {'id': 'grp_new', 'name': properties['name'], 'members': properties['members']},
    };
  }
}

void main() {
  /// The create button of the open dialog.
  Finder createButton() => find.widgetWithText(TextButton, '创建');
  Finder cancelButton() => find.widgetWithText(TextButton, '取消');
  Finder nameField() => find.widgetWithText(TextField, '名字');

  TextButton buttonOf(WidgetTester tester) => tester.widget<TextButton>(createButton());

  /// Whether the add menu is open for a new creation.
  bool entryEnabled(WidgetTester tester) {
    final found = find.byType(PopupMenuButton<bool>).evaluate().toList();
    return found.isNotEmpty && (found.first.widget as PopupMenuButton<bool>).enabled;
  }

  /// Pumps until [condition] holds. It never waits on an unfinished future and
  /// never waits for an animation that does not end.
  Future<void> pumpUntil(WidgetTester tester, bool Function() condition) async {
    for (var step = 0; step < 60 && !condition(); step++) {
      await tester.pump(const Duration(milliseconds: 50));
    }
  }

  Future<void> openDialog(WidgetTester tester, String menuItem) async {
    await tester.tap(find.byIcon(Icons.add));
    await tester.pumpAndSettle();
    await tester.tap(find.text(menuItem));
    await tester.pumpAndSettle();
  }

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

  testWidgets('creating a Bot needs a name before the button is usable', (tester) async {
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

    await openDialog(tester, '创建 Bot');

    // The flow owns the entry while the dialog is open.
    expect(entryEnabled(tester), isFalse);
    // An empty name leaves the button unusable.
    expect(buttonOf(tester).onPressed, isNull);
    await tester.enterText(nameField(), '   ');
    await tester.pump();
    expect(buttonOf(tester).onPressed, isNull);
    // A name that says something makes it usable right away.
    await tester.enterText(nameField(), '研究助手');
    await tester.pump();
    expect(buttonOf(tester).onPressed, isNotNull);
    // Emptying it again takes the button back.
    await tester.enterText(nameField(), '');
    await tester.pump();
    expect(buttonOf(tester).onPressed, isNull);
  });

  testWidgets('creating a group needs a member before the button is usable', (tester) async {
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

    await openDialog(tester, '创建群聊');

    await tester.enterText(nameField(), '研究小组');
    await tester.pump();
    expect(buttonOf(tester).onPressed, isNull);
    await tester.tap(find.widgetWithText(CheckboxListTile, 'Alice'));
    await tester.pump();
    expect(buttonOf(tester).onPressed, isNotNull);
  });

  testWidgets('cancelling the creation dialog asks the service for nothing', (tester) async {
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

    await openDialog(tester, '创建 Bot');
    await tester.enterText(nameField(), '研究助手');
    await tester.pump();
    expect(buttonOf(tester).onPressed, isNotNull);

    await tester.tap(cancelButton());
    await tester.pumpAndSettle();

    expect(api.creates, isEmpty);
    // The entry is free again after a cancelled flow.
    expect(entryEnabled(tester), isTrue);
  });

  testWidgets('a waiting creation cannot be started twice and the entry comes back', (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(1024, 900);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final api = _Api();
    final gate = Completer<Map<String, Object?>>();
    api.gate = gate;
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

    await openDialog(tester, '创建 Bot');
    await tester.enterText(nameField(), '研究助手');
    await tester.pump();
    await tester.tap(createButton());
    // The dialog closes and the request waits on the gate.
    await pumpUntil(tester, () => api.creates.isNotEmpty);
    expect(api.creates, ['bot']);

    // The entry is closed while the flow runs, so a second one cannot start.
    expect(entryEnabled(tester), isFalse);
    await tester.tap(find.byIcon(Icons.add), warnIfMissed: false);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.text('创建 Bot'), findsNothing);
    expect(api.creates, ['bot']);

    // The flow ends and the entry works again.
    gate.complete({
      'bot': {'id': 'bot_new', 'name': '研究助手', 'role': ''},
    });
    await pumpUntil(tester, () => entryEnabled(tester));
    expect(api.creates, ['bot']);
    await tester.tap(find.byIcon(Icons.add));
    await tester.pumpAndSettle();
    expect(find.text('创建 Bot'), findsOneWidget);
  });

  testWidgets('a failed creation is not retried and the entry comes back', (tester) async {
    final api = _Api();
    api.failure = StateError('the service refused');
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

    await openDialog(tester, '创建 Bot');
    await tester.enterText(nameField(), '研究助手');
    await tester.pump();
    await tester.tap(createButton());
    await pumpUntil(tester, () => api.creates.isNotEmpty);
    await pumpUntil(tester, () => entryEnabled(tester));

    // One attempt, no retry of its own.
    expect(api.creates, hasLength(1));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.text('无法加载 Bot 服务，请检查本机运行状态后重试。'), findsOneWidget);
    expect(api.creates, hasLength(1));
  });

  testWidgets('a page that leaves while the dialog closes does not create', (tester) async {
    final api = _Api();
    final controller = BotController(api);
    var released = false;
    addTearDown(() {
      if (released) return;
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

    await openDialog(tester, '创建 Bot');
    await tester.enterText(nameField(), '研究助手');
    await tester.pump();
    await tester.tap(createButton());
    // The dialog is on its way out and the user confirmed it.
    await tester.pump();
    // The page goes away before the dialog has finished leaving.
    await tester.pumpWidget(const SizedBox());
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    // A confirmation cannot start an operation for a page that is gone.
    expect(api.creates, isEmpty);
    expect(tester.takeException(), isNull);
    // The controller is released once, and the release is clean.
    controller.dispose();
    api.close();
    released = true;
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
