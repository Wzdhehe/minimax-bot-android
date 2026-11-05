// SPDX-License-Identifier: AGPL-3.0-only
import 'dart:async';

import 'package:dsh_app/bot/bot_profile.dart';
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mbot_settings/mbot_settings.dart';

/// A service that answers with its own name, so the page can be seen to
/// re-read the state instead of echoing what was typed.
class _Api extends MiniMaxBotApi {
  _Api() : super(Uri.parse('http://127.0.0.1:8788'));
  /// Every update the page asked for, in order.
  final updates = <Map<String, Object?>>[];
  /// When set, an update waits for it before answering.
  Completer<Map<String, Object?>>? gate;
  /// When set, an update fails with it.
  Object? failure;
  String serverName = 'Alice';

  @override
  Future<Map<String, Object?>> state() async => {
    'bots': [
      {'id': 'bot_a', 'name': serverName, 'role': 'Research'},
    ],
    'schedules': [],
  };

  @override
  Future<Map<String, Object?>> updateBot(String id, Map<String, Object?> properties) async {
    updates.add(Map<String, Object?>.from(properties));
    final wait = gate;
    if (wait != null) await wait.future;
    final error = failure;
    if (error != null) throw error;
    serverName = '${properties['name']}·服务端';
    return {
      'bot': {'id': id, 'name': serverName},
    };
  }
}

void main() {
  /// The entry that opens the edit form, and the form's own buttons.
  Finder editEntry() => find.widgetWithText(TextButton, '编辑');
  Finder saveButton() => find.widgetWithText(TextButton, '保存');
  Finder cancelButton() => find.widgetWithText(TextButton, '取消');
  Finder nameField() => find.widgetWithText(TextField, '名字');
  Finder roleField() => find.widgetWithText(TextField, '角色与职责');

  TextButton buttonOf(WidgetTester tester, Finder finder) => tester.widget<TextButton>(finder);

  /// Pumps until [condition] holds. It never waits on an unfinished future and
  /// never waits for an animation that does not end.
  Future<void> pumpUntil(WidgetTester tester, bool Function() condition) async {
    for (var step = 0; step < 60 && !condition(); step++) {
      await tester.pump(const Duration(milliseconds: 50));
    }
  }

  Future<void> openEdit(WidgetTester tester) async {
    await tester.tap(editEntry());
    await tester.pumpAndSettle();
  }

  testWidgets('the edit form starts from the current name and needs one to save', (tester) async {
    final api = _Api();
    addTearDown(api.close);
    await tester.pumpWidget(
      MaterialApp(
        theme: ThemeData.dark(),
        home: BotProfilePage(api: api, botId: 'bot_a', store: MemoryPrefsStore()),
      ),
    );
    await tester.pumpAndSettle();

    await openEdit(tester);

    // The form starts from what the page shows.
    expect(tester.widget<TextField>(nameField()).controller!.text, 'Alice');
    expect(tester.widget<TextField>(roleField()).controller!.text, 'Research');
    // The current name is enough to save.
    expect(buttonOf(tester, saveButton()).onPressed, isNotNull);

    // A blank name disables the save.
    await tester.enterText(nameField(), '   ');
    await tester.pump();
    expect(buttonOf(tester, saveButton()).onPressed, isNull);
    // A name that says something enables it again.
    await tester.enterText(nameField(), '研究助手');
    await tester.pump();
    expect(buttonOf(tester, saveButton()).onPressed, isNotNull);
    // Emptying it disables the save once more.
    await tester.enterText(nameField(), '');
    await tester.pump();
    expect(buttonOf(tester, saveButton()).onPressed, isNull);

    // The role may stay empty; only the name is required.
    await tester.enterText(nameField(), '研究助手');
    await tester.enterText(roleField(), '');
    await tester.pump();
    expect(buttonOf(tester, saveButton()).onPressed, isNotNull);
  });

  testWidgets('the edit entry is closed while the dialog is open and a cancel asks for nothing', (tester) async {
    final api = _Api();
    addTearDown(api.close);
    await tester.pumpWidget(
      MaterialApp(
        theme: ThemeData.dark(),
        home: BotProfilePage(api: api, botId: 'bot_a', store: MemoryPrefsStore()),
      ),
    );
    await tester.pumpAndSettle();

    await openEdit(tester);

    // The entry is closed for the whole dialog, not only for the request.
    expect(buttonOf(tester, editEntry()).onPressed, isNull);

    await tester.tap(cancelButton());
    await tester.pumpAndSettle();

    // A cancelled edit asks the service for nothing.
    expect(api.updates, isEmpty);
    // The entry comes back once the flow is over.
    expect(buttonOf(tester, editEntry()).onPressed, isNotNull);
  });

  testWidgets('a waiting update runs once and the entry stays closed until it ends', (tester) async {
    final api = _Api();
    final gate = Completer<Map<String, Object?>>();
    api.gate = gate;
    addTearDown(api.close);
    await tester.pumpWidget(
      MaterialApp(
        theme: ThemeData.dark(),
        home: BotProfilePage(api: api, botId: 'bot_a', store: MemoryPrefsStore()),
      ),
    );
    await tester.pumpAndSettle();

    await openEdit(tester);
    await tester.enterText(nameField(), '研究助手');
    await tester.pump();
    await tester.tap(saveButton());
    await pumpUntil(tester, () => api.updates.isNotEmpty);

    // One request, with the trimmed name and the role the form started with.
    expect(api.updates, hasLength(1));
    expect(api.updates.single, {'name': '研究助手', 'role': 'Research'});
    // The entry stays closed while the update waits.
    expect(buttonOf(tester, editEntry()).onPressed, isNull);
    // A second attempt cannot start a second request.
    await tester.tap(editEntry(), warnIfMissed: false);
    await tester.pump();
    expect(api.updates, hasLength(1));

    // The service answers; the page re-reads the state and shows its answer.
    gate.complete({'bot': {'id': 'bot_a', 'name': '研究助手·服务端'}});
    await pumpUntil(tester, () => find.text('研究助手·服务端').evaluate().isNotEmpty);
    expect(find.text('研究助手·服务端'), findsOneWidget);
    expect(api.updates, hasLength(1));
    // The entry comes back after the reload.
    expect(buttonOf(tester, editEntry()).onPressed, isNotNull);
  });

  testWidgets('a failed update is not retried and the page offers its own retry', (tester) async {
    final api = _Api();
    api.failure = StateError('the service refused');
    addTearDown(api.close);
    await tester.pumpWidget(
      MaterialApp(
        theme: ThemeData.dark(),
        home: BotProfilePage(api: api, botId: 'bot_a', store: MemoryPrefsStore()),
      ),
    );
    await tester.pumpAndSettle();

    await openEdit(tester);
    await tester.enterText(nameField(), '研究助手');
    await tester.pump();
    await tester.tap(saveButton());
    await pumpUntil(tester, () => api.updates.isNotEmpty);
    await pumpUntil(tester, () => find.text('重新连接').evaluate().isNotEmpty);

    // One attempt, no retry of its own.
    expect(api.updates, hasLength(1));
    // The page keeps the failure it already showed and the Bot it had.
    expect(find.text('重新连接'), findsOneWidget);
    expect(find.text('Alice'), findsOneWidget);
    expect(find.text('研究助手·服务端'), findsNothing);
    // The entry comes back.
    expect(buttonOf(tester, editEntry()).onPressed, isNotNull);
  });

  testWidgets('a page that leaves while the dialog closes does not update', (tester) async {
    final api = _Api();
    var released = false;
    addTearDown(() {
      if (released) return;
      api.close();
    });
    await tester.pumpWidget(
      MaterialApp(
        theme: ThemeData.dark(),
        home: BotProfilePage(api: api, botId: 'bot_a', store: MemoryPrefsStore()),
      ),
    );
    await tester.pumpAndSettle();

    await openEdit(tester);
    await tester.enterText(nameField(), '研究助手');
    await tester.pump();
    await tester.tap(saveButton());
    // The dialog is on its way out and the user confirmed it.
    await tester.pump();
    // The page goes away before the dialog has finished leaving.
    await tester.pumpWidget(const SizedBox());
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    // A confirmation cannot start an update for a page that is gone.
    expect(api.updates, isEmpty);
    expect(tester.takeException(), isNull);
    api.close();
    released = true;
  });

  for (final width in [390.0, 1024.0]) {
    testWidgets('the Bot profile edit form has no overflow at width $width', (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = Size(width, 900);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final api = _Api();
      addTearDown(api.close);
      await tester.pumpWidget(
        MaterialApp(
          theme: ThemeData.dark(),
          home: BotProfilePage(api: api, botId: 'bot_a', store: MemoryPrefsStore()),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('Alice'), findsOneWidget);
      expect(tester.takeException(), isNull);

      await openEdit(tester);
      expect(find.text('名字'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }
}
