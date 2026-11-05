// SPDX-License-Identifier: AGPL-3.0-only
import 'dart:async';
import 'dart:io';

import 'package:dsh_app/bot/bot_memory_page.dart';
import 'package:dsh_app/bot/bot_profile.dart';
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mbot_settings/mbot_settings.dart';

/// A service that answers per scope and can be held open.
class _Api extends MiniMaxBotApi {
  _Api() : super(Uri.parse('http://127.0.0.1:8788'));
  /// Every scope that was read.
  final scopes = <String>[];
  /// The payload a scope answers with.
  final answers = <String, Map<String, Object?>>{};
  /// A scope whose read waits for its gate.
  final gates = <String, Completer<Map<String, Object?>>>{};
  /// A scope whose read fails.
  final failures = <String, Object>{};
  /// When set, the next read fails and the flag clears itself.
  bool failNextRead = false;

  /// Every write the page tried.
  int writes = 0;
  /// The body of every remember the page tried.
  final remembers = <Map<String, Object?>>[];
  /// The id of every forget the page tried.
  final forgets = <String>[];
  /// A scope whose write waits for its gate.
  final writeGates = <String, Completer<Map<String, Object?>>>{};
  /// A scope whose write fails.
  final writeFailures = <String, Object>{};
  /// How often the page closed the service it does not own.
  int closed = 0;

  int _ids = 0;

  @override
  Future<Map<String, Object?>> state() async => {
    'bots': [
      {'id': 'bot_a', 'name': 'Alice'},
    ],
    'schedules': [],
  };

  @override
  Future<Map<String, Object?>> memory(String scope) async {
    scopes.add(scope);
    final gate = gates[scope];
    if (gate != null) await gate.future;
    if (failNextRead) {
      failNextRead = false;
      throw const SocketException('the service did not answer');
    }
    final error = failures[scope];
    if (error != null) throw error;
    return answers[scope] ?? {'scope': scope, 'entries': []};
  }

  @override
  Future<Map<String, Object?>> remember(String scope, Map<String, Object?> entry) async {
    writes++;
    remembers.add(Map<String, Object?>.from(entry));
    final gate = writeGates[scope];
    if (gate != null) await gate.future;
    final error = writeFailures[scope];
    if (error != null) throw error;
    final rows = _rows(scope);
    final text = entry['text'] as String;
    final replaceId = entry['replaceId'];
    if (replaceId is String) {
      final index = rows.indexWhere((row) => row['id'] == replaceId);
      if (index >= 0) {
        rows[index] = {'id': replaceId, 'revision': _revision(text), 'text': text};
      }
    } else {
      rows.add({'id': _nextId(), 'revision': _revision(text), 'text': text});
    }
    _store(scope, rows);
    return {'entry': rows.last};
  }

  @override
  Future<Map<String, Object?>> forget(String scope, String entry) async {
    writes++;
    forgets.add(entry);
    final gate = writeGates[scope];
    if (gate != null) await gate.future;
    final error = writeFailures[scope];
    if (error != null) throw error;
    final rows = _rows(scope)..removeWhere((row) => row['id'] == entry);
    _store(scope, rows);
    return {'ok': true};
  }

  @override
  void close() {
    closed++;
    super.close();
  }

  List<Map<String, Object?>> _rows(String scope) {
    final entries = answers[scope]?['entries'];
    if (entries is! List) return <Map<String, Object?>>[];
    return [for (final entry in entries) if (entry is Map) Map<String, Object?>.from(entry)];
  }

  void _store(String scope, List<Map<String, Object?>> rows) {
    answers[scope] = {'scope': scope, 'entries': rows};
  }

  String _nextId() => (0x10000000 + _ids++).toRadixString(16);

  static String _revision(String text) => 'rev-${text.hashCode.toUnsigned(32).toRadixString(16)}';
}

void main() {
  /// Pumps a bounded number of frames until [condition] holds. It never waits
  /// on an unfinished future and never waits for an animation that does not
  /// end; a condition that never holds is a failure, not a silent timeout.
  Future<void> pumpUntil(WidgetTester tester, bool Function() condition, {int frames = 60}) async {
    for (var step = 0; step < frames && !condition(); step++) {
      await tester.pump(const Duration(milliseconds: 50));
    }
    expect(condition(), isTrue, reason: 'the page did not reach the expected state');
  }

  Widget profile(_Api api) => MaterialApp(
    theme: ThemeData.dark(),
    home: BotProfilePage(api: api, botId: 'bot_a', store: MemoryPrefsStore()),
  );

  Widget memory(_Api api, {String botId = 'bot_a', Key? key}) => MaterialApp(
    theme: ThemeData.dark(),
    home: BotMemoryPage(key: key, api: api, botId: botId),
  );

  /// The add entry of the page.
  Finder addEntry() => find.byIcon(Icons.add);
  Finder editEntry() => find.widgetWithText(TextButton, '编辑');
  Finder deleteEntry() => find.widgetWithText(TextButton, '删除').first;
  Finder dialogDelete() =>
      find.descendant(of: find.byType(AlertDialog), matching: find.widgetWithText(TextButton, '删除'));
  Finder saveButton() => find.widgetWithText(TextButton, '保存');
  Finder cancelButton() => find.widgetWithText(TextButton, '取消');
  Finder retryButton() => find.widgetWithText(TextButton, '点一下重试');
  Finder formField() => find.byType(TextField);

  /// Whether the add entry is open.
  bool addEnabled(WidgetTester tester) =>
      tester.widget<IconButton>(find.byType(IconButton)).onPressed != null;

  /// The form is closed and the text is on the page itself.
  bool listed(WidgetTester tester, String text) =>
      formField().evaluate().isEmpty && find.text(text).evaluate().isNotEmpty;

  testWidgets('the memory page reads the Bot it was given and shows its entries', (tester) async {
    final api = _Api();
    api.answers['bot_a'] = {
      'scope': 'bot_a',
      'entries': [
        {'id': 'mem_1', 'revision': 3, 'text': '喜欢简短的回答'},
        {'id': 'mem_2', 'revision': 4, 'text': '周五前交初稿'},
      ],
    };
    addTearDown(api.close);
    await tester.pumpWidget(profile(api));
    await pumpUntil(tester, () => find.text('Alice').evaluate().isNotEmpty);
    // The row is below the fold on a short window.
    await tester.scrollUntilVisible(
      find.byKey(const Key('mbot.bot.memory')),
      120,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.tap(find.byKey(const Key('mbot.bot.memory')));
    await pumpUntil(tester, () => find.text('喜欢简短的回答').evaluate().isNotEmpty);

    // Only this Bot's scope was read, and only a read happened.
    expect(api.scopes, ['bot_a']);
    expect(api.writes, 0);
    // The service belongs to the parent; the page does not close it.
    expect(api.closed, 0);
    // The texts are shown and nothing else of the payload is.
    expect(find.text('喜欢简短的回答'), findsOneWidget);
    expect(find.text('周五前交初稿'), findsOneWidget);
    expect(find.textContaining('mem_1'), findsNothing);
    expect(find.textContaining('revision'), findsNothing);
  });

  testWidgets('a Bot without memories says so', (tester) async {
    final api = _Api();
    api.answers['bot_a'] = {'scope': 'bot_a', 'entries': []};
    addTearDown(api.close);
    await tester.pumpWidget(memory(api));
    await pumpUntil(tester, () => find.text('还没有记忆').evaluate().isNotEmpty);

    expect(find.text('还没有记忆'), findsOneWidget);
    expect(find.text('没能读取记忆'), findsNothing);
    expect(api.scopes, ['bot_a']);
  });

  testWidgets('a failed read is a failure, not an empty memory, and a retry can recover', (tester) async {
    final api = _Api();
    api.failures['bot_a'] = StateError('服务拒绝了这次读取');
    addTearDown(api.close);
    await tester.pumpWidget(memory(api));
    await pumpUntil(tester, () => find.text('没能读取记忆').evaluate().isNotEmpty);

    expect(find.text('没能读取记忆'), findsOneWidget);
    // A failure is never shown as an empty memory.
    expect(find.text('还没有记忆'), findsNothing);
    // The raw exception is not shown either.
    expect(find.textContaining('服务拒绝了这次读取'), findsNothing);
    // There is no retry of its own.
    expect(api.scopes, ['bot_a']);

    api.failures.remove('bot_a');
    api.answers['bot_a'] = {
      'scope': 'bot_a',
      'entries': [
        {'text': '恢复后的记忆'},
      ],
    };
    await tester.tap(retryButton());
    await pumpUntil(tester, () => find.text('恢复后的记忆').evaluate().isNotEmpty);

    expect(find.text('恢复后的记忆'), findsOneWidget);
    expect(find.text('没能读取记忆'), findsNothing);
    expect(api.scopes, ['bot_a', 'bot_a']);
  });

  testWidgets('a retry that is running cannot be repeated', (tester) async {
    final api = _Api();
    api.failures['bot_a'] = StateError('refused');
    addTearDown(api.close);
    await tester.pumpWidget(memory(api));
    await pumpUntil(tester, () => find.text('没能读取记忆').evaluate().isNotEmpty);

    api.failures.remove('bot_a');
    api.answers['bot_a'] = {
      'scope': 'bot_a',
      'entries': [
        {'text': '好了'},
      ],
    };
    final gate = Completer<Map<String, Object?>>();
    api.gates['bot_a'] = gate;
    await tester.tap(retryButton());
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    expect(api.scopes, ['bot_a', 'bot_a']);
    // The retry entry is closed while it waits.
    expect(tester.widget<TextButton>(retryButton()).onPressed, isNull);
    await tester.tap(retryButton(), warnIfMissed: false);
    await tester.pump();
    expect(api.scopes, ['bot_a', 'bot_a']);

    gate.complete({
      'scope': 'bot_a',
      'entries': [
        {'text': '好了'},
      ],
    });
    await pumpUntil(tester, () => find.text('好了').evaluate().isNotEmpty);
    expect(find.text('好了'), findsOneWidget);
    expect(api.scopes, ['bot_a', 'bot_a']);
  });

  final badPayloads = <String, Map<String, Object?>>{
    'no scope': {
      'entries': [
        {'text': '没有 scope 的条目'},
      ],
    },
    'another Bot': {
      'scope': 'bot_other',
      'entries': [
        {'text': '别的 Bot 的记忆'},
      ],
    },
    'entries that are not a list': {
      'scope': 'bot_a',
      'entries': '不是列表',
    },
    'an entry whose text is not a string': {
      'scope': 'bot_a',
      'entries': [
        {'text': 42},
      ],
    },
  };
  for (final bad in badPayloads.entries) {
    testWidgets('a payload with ${bad.key} is a failed read', (tester) async {
      final api = _Api();
      api.answers['bot_a'] = bad.value;
      addTearDown(api.close);
      await tester.pumpWidget(memory(api));
      await pumpUntil(tester, () => find.text('没能读取记忆').evaluate().isNotEmpty);

      expect(find.text('没能读取记忆'), findsOneWidget);
      expect(find.text('还没有记忆'), findsNothing);
      // Nothing of that payload reaches the screen.
      final entries = bad.value['entries'];
      if (entries is List) {
        for (final entry in entries) {
          if (entry is Map && entry['text'] is String) {
            expect(find.text(entry['text'] as String), findsNothing);
          }
        }
      }
    });
  }

  testWidgets('a late answer from the previous Bot cannot replace the new one', (tester) async {
    final api = _Api();
    final gateA = Completer<Map<String, Object?>>();
    final gateB = Completer<Map<String, Object?>>();
    api.gates['bot_a'] = gateA;
    api.gates['bot_b'] = gateB;
    api.answers['bot_b'] = {
      'scope': 'bot_b',
      'entries': [
        {'text': 'B 的记忆'},
      ],
    };
    addTearDown(api.close);
    final key = GlobalKey();
    await tester.pumpWidget(memory(api, botId: 'bot_a', key: key));
    await tester.pump();
    expect(api.scopes, ['bot_a']);

    // The same State now reads the other Bot.
    await tester.pumpWidget(memory(api, botId: 'bot_b', key: key));
    await tester.pump();
    expect(api.scopes, ['bot_a', 'bot_b']);

    gateB.complete({
      'scope': 'bot_b',
      'entries': [
        {'text': 'B 的记忆'},
      ],
    });
    await pumpUntil(tester, () => find.text('B 的记忆').evaluate().isNotEmpty);
    expect(find.text('B 的记忆'), findsOneWidget);

    // The old answer arrives late and may not replace it.
    gateA.complete({
      'scope': 'bot_a',
      'entries': [
        {'text': 'A 的记忆'},
      ],
    });
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));
    expect(find.text('B 的记忆'), findsOneWidget);
    expect(find.text('A 的记忆'), findsNothing);
  });

  testWidgets('a late failure from the previous Bot cannot replace the new one', (tester) async {
    final api = _Api();
    final gateA = Completer<Map<String, Object?>>();
    final gateB = Completer<Map<String, Object?>>();
    api.gates['bot_a'] = gateA;
    api.gates['bot_b'] = gateB;
    api.answers['bot_b'] = {
      'scope': 'bot_b',
      'entries': [
        {'text': 'B 的记忆'},
      ],
    };
    addTearDown(api.close);
    final key = GlobalKey();
    await tester.pumpWidget(memory(api, botId: 'bot_a', key: key));
    await tester.pump();

    await tester.pumpWidget(memory(api, botId: 'bot_b', key: key));
    await tester.pump();

    gateB.complete({
      'scope': 'bot_b',
      'entries': [
        {'text': 'B 的记忆'},
      ],
    });
    await pumpUntil(tester, () => find.text('B 的记忆').evaluate().isNotEmpty);

    // The old Bot fails late; the page may not turn into a failure.
    gateA.completeError(StateError('A 的读取迟到的失败'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));
    expect(find.text('B 的记忆'), findsOneWidget);
    expect(find.text('没能读取记忆'), findsNothing);
    expect(find.textContaining('迟到的失败'), findsNothing);
  });

  testWidgets('an answer that arrives after the page is gone changes nothing', (tester) async {
    final api = _Api();
    final gate = Completer<Map<String, Object?>>();
    api.gates['bot_a'] = gate;
    addTearDown(api.close);
    await tester.pumpWidget(memory(api));
    await tester.pump();

    await tester.pumpWidget(const SizedBox());
    gate.complete({
      'scope': 'bot_a',
      'entries': [
        {'text': '迟到的记忆'},
      ],
    });
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    expect(tester.takeException(), isNull);
  });

  for (final width in [390.0, 1024.0]) {
    testWidgets('long memory text scrolls without overflow at width $width', (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = Size(width, 900);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final api = _Api();
      api.answers['bot_a'] = {
        'scope': 'bot_a',
        'entries': [
          {'text': List.filled(40, '这是一条很长的记忆').join()},
          {'text': List.filled(10, 'This is a long memory entry that has to wrap somewhere sensible').join(' ')},
        ],
      };
      addTearDown(api.close);
      await tester.pumpWidget(memory(api));
      await pumpUntil(tester, () => find.textContaining('这是一条很长的记忆').evaluate().isNotEmpty);

      expect(tester.takeException(), isNull);
      // The content scrolls.
      await tester.drag(find.byType(ListView), const Offset(0, -200));
      await tester.pump();
      expect(tester.takeException(), isNull);
    });
  }

  // ── The changes ───────────────────────────────────────────────────────────

  testWidgets('a new memory is sent once and the list shows what the service stored', (tester) async {
    final api = _Api();
    api.answers['bot_a'] = {'scope': 'bot_a', 'entries': []};
    addTearDown(api.close);
    await tester.pumpWidget(memory(api));
    await pumpUntil(tester, () => find.text('还没有记忆').evaluate().isNotEmpty);

    await tester.tap(addEntry());
    await pumpUntil(tester, () => formField().evaluate().isNotEmpty);
    // A blank text cannot be saved.
    expect(tester.widget<TextButton>(saveButton()).onPressed, isNull);
    await tester.enterText(formField(), '新的记忆');
    await tester.pump();
    expect(tester.widget<TextButton>(saveButton()).onPressed, isNotNull);
    await tester.tap(saveButton());
    await pumpUntil(tester, () => listed(tester, '新的记忆'));

    // One request, with nothing but the text.
    expect(api.writes, 1);
    expect(api.remembers, hasLength(1));
    expect(api.remembers.single, {'text': '新的记忆'});
    // The list shows what the service stored.
    expect(find.text('新的记忆'), findsOneWidget);
  });

  testWidgets('a new memory is not sent twice while the request waits', (tester) async {
    final api = _Api();
    api.answers['bot_a'] = {'scope': 'bot_a', 'entries': []};
    final gate = Completer<Map<String, Object?>>();
    api.writeGates['bot_a'] = gate;
    addTearDown(api.close);
    await tester.pumpWidget(memory(api));
    await pumpUntil(tester, () => find.text('还没有记忆').evaluate().isNotEmpty);

    await tester.tap(addEntry());
    await pumpUntil(tester, () => formField().evaluate().isNotEmpty);
    await tester.enterText(formField(), '新的记忆');
    await tester.pump();
    await tester.tap(saveButton());
    await pumpUntil(tester, () => api.remembers.isNotEmpty);

    expect(api.remembers, hasLength(1));
    // The entry is closed while the request waits.
    expect(tester.widget<IconButton>(find.byType(IconButton)).onPressed, isNull);
    await tester.tap(addEntry(), warnIfMissed: false);
    await tester.pump();
    expect(api.remembers, hasLength(1));

    gate.complete({
      'entry': {'id': '10000000', 'revision': 'rev-1', 'text': '新的记忆'},
    });
    await pumpUntil(tester, () => find.text('新的记忆').evaluate().isNotEmpty);
    expect(api.remembers, hasLength(1));
  });

  testWidgets('editing sends the id and the revision the entry came with', (tester) async {
    final api = _Api();
    api.answers['bot_a'] = {
      'scope': 'bot_a',
      'entries': [
        {'id': 'a1b2c3d4', 'revision': 'rev-alpha', 'text': '第一条'},
      ],
    };
    addTearDown(api.close);
    await tester.pumpWidget(memory(api));
    await pumpUntil(tester, () => find.text('第一条').evaluate().isNotEmpty);

    await tester.tap(editEntry());
    await pumpUntil(tester, () => formField().evaluate().isNotEmpty);
    // The form starts from the entry.
    expect(tester.widget<TextField>(formField()).controller!.text, '第一条');
    await tester.enterText(formField(), '改过的第一条');
    await tester.pump();
    await tester.tap(saveButton());
    await pumpUntil(tester, () => listed(tester, '改过的第一条'));

    // The id and the revision are the ones the entry came with, and nothing
    // else is sent.
    expect(api.remembers, hasLength(1));
    expect(api.remembers.single, {
      'text': '改过的第一条',
      'replaceId': 'a1b2c3d4',
      'expectedRevision': 'rev-alpha',
    });
  });

  testWidgets('a conflicting change is not repeated and keeps its draft', (tester) async {
    final api = _Api();
    api.answers['bot_a'] = {
      'scope': 'bot_a',
      'entries': [
        {'id': 'a1b2c3d4', 'revision': 'rev-alpha', 'text': '第一条'},
      ],
    };
    api.writeFailures['bot_a'] = MiniMaxBotException(409, 'memory_conflict', 'the entry changed');
    addTearDown(api.close);
    await tester.pumpWidget(memory(api));
    await pumpUntil(tester, () => find.text('第一条').evaluate().isNotEmpty);

    await tester.tap(editEntry());
    await pumpUntil(tester, () => formField().evaluate().isNotEmpty);
    await tester.enterText(formField(), '改过的第一条');
    await tester.pump();
    await tester.tap(saveButton());
    await pumpUntil(tester, () => find.textContaining('已经被改动').evaluate().isNotEmpty);

    // One attempt, no retry of its own.
    expect(api.remembers, hasLength(1));
    // The conflict reads as a conflict and the draft is still there.
    expect(find.textContaining('已经被改动'), findsOneWidget);
    expect(find.text('改过的第一条'), findsOneWidget);
    // The entry was not changed on screen.
    expect(find.text('第一条'), findsOneWidget);
    // The write entries stay closed until a read succeeds.
    expect(tester.widget<TextButton>(editEntry()).onPressed, isNull);
    expect(tester.widget<TextButton>(deleteEntry()).onPressed, isNull);
    expect(tester.widget<IconButton>(find.byType(IconButton)).onPressed, isNull);
  });

  testWidgets('an unknown outcome is not repeated and the draft stays', (tester) async {
    final api = _Api();
    api.answers['bot_a'] = {'scope': 'bot_a', 'entries': []};
    api.writeFailures['bot_a'] = TimeoutException('the service did not answer');
    addTearDown(api.close);
    await tester.pumpWidget(memory(api));
    await pumpUntil(tester, () => find.text('还没有记忆').evaluate().isNotEmpty);

    await tester.tap(addEntry());
    await pumpUntil(tester, () => formField().evaluate().isNotEmpty);
    await tester.enterText(formField(), '新的记忆');
    await tester.pump();
    await tester.tap(saveButton());
    await pumpUntil(tester, () => find.textContaining('无法确认').evaluate().isNotEmpty);

    // One request and no resend: the outcome is unknown, not "not written".
    expect(api.writes, 1);
    expect(find.textContaining('无法确认'), findsOneWidget);
    expect(find.text('新的记忆'), findsOneWidget);
    // The write entries stay closed.
    expect(tester.widget<IconButton>(find.byType(IconButton)).onPressed, isNull);

    // A successful read opens them again but repeats nothing.
    api.writeFailures.remove('bot_a');
    await tester.tap(retryButton());
    await pumpUntil(tester, () => addEnabled(tester));
    expect(api.writes, 1);
    expect(addEnabled(tester), isTrue);
  });

  testWidgets('a written change that cannot be read again asks for a refresh', (tester) async {
    final api = _Api();
    api.answers['bot_a'] = {'scope': 'bot_a', 'entries': []};
    addTearDown(api.close);
    await tester.pumpWidget(memory(api));
    await pumpUntil(tester, () => find.text('还没有记忆').evaluate().isNotEmpty);

    await tester.tap(addEntry());
    await pumpUntil(tester, () => formField().evaluate().isNotEmpty);
    await tester.enterText(formField(), '新的记忆');
    await tester.pump();
    // The write succeeds but the read that follows does not.
    api.failNextRead = true;
    await tester.tap(saveButton());
    await pumpUntil(tester, () => find.textContaining('没能重新读取记忆').evaluate().isNotEmpty);

    // The write was accepted, so this is not the failure of a write.
    expect(api.writes, 1);
    expect(find.textContaining('没能重新读取记忆'), findsOneWidget);
    expect(find.textContaining('无法确认'), findsNothing);
    expect(find.textContaining('没有接受'), findsNothing);
    // The list did not fall back to an empty memory.
    expect(find.text('还没有记忆'), findsNothing);
  });

  testWidgets('deleting asks first, then removes one entry of that scope', (tester) async {
    final api = _Api();
    api.answers['bot_a'] = {
      'scope': 'bot_a',
      'entries': [
        {'id': 'a1b2c3d4', 'revision': 'rev-alpha', 'text': '第一条'},
        {'id': 'b2c3d4e5', 'revision': 'rev-beta', 'text': '第二条'},
      ],
    };
    addTearDown(api.close);
    await tester.pumpWidget(memory(api));
    await pumpUntil(tester, () => find.text('第一条').evaluate().isNotEmpty);

    await tester.tap(deleteEntry());
    await pumpUntil(tester, () => find.textContaining('删除当前这条记忆').evaluate().isNotEmpty);
    // A cancelled delete asks for nothing.
    await tester.tap(cancelButton());
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(api.writes, 0);

    await tester.tap(deleteEntry());
    await pumpUntil(tester, () => find.textContaining('删除当前这条记忆').evaluate().isNotEmpty);
    await tester.tap(dialogDelete());
    await pumpUntil(tester, () => find.text('第一条').evaluate().isEmpty);

    // One call, for that scope and that id.
    expect(api.writes, 1);
    expect(api.forgets, ['a1b2c3d4']);
    expect(find.text('第二条'), findsOneWidget);
  });

  testWidgets('a failed delete keeps the entry and is not repeated', (tester) async {
    final api = _Api();
    api.answers['bot_a'] = {
      'scope': 'bot_a',
      'entries': [
        {'id': 'a1b2c3d4', 'revision': 'rev-alpha', 'text': '第一条'},
      ],
    };
    api.writeFailures['bot_a'] = MiniMaxBotException(500, 'internal', 'the service broke');
    addTearDown(api.close);
    await tester.pumpWidget(memory(api));
    await pumpUntil(tester, () => find.text('第一条').evaluate().isNotEmpty);

    await tester.tap(deleteEntry());
    await pumpUntil(tester, () => find.textContaining('删除当前这条记忆').evaluate().isNotEmpty);
    await tester.tap(dialogDelete());
    await pumpUntil(tester, () => find.textContaining('无法确认').evaluate().isNotEmpty);

    // One attempt, no retry, and the entry is not optimistically gone.
    expect(api.writes, 1);
    expect(find.text('第一条'), findsOneWidget);
    expect(find.textContaining('无法确认'), findsOneWidget);
  });

  testWidgets('a page that leaves while the form closes does not write', (tester) async {
    final api = _Api();
    api.answers['bot_a'] = {'scope': 'bot_a', 'entries': []};
    addTearDown(api.close);
    await tester.pumpWidget(memory(api));
    await pumpUntil(tester, () => find.text('还没有记忆').evaluate().isNotEmpty);

    await tester.tap(addEntry());
    await pumpUntil(tester, () => formField().evaluate().isNotEmpty);
    await tester.enterText(formField(), '新的记忆');
    await tester.pump();
    await tester.tap(saveButton());
    // The page goes away while the form is still leaving.
    await tester.pumpWidget(const SizedBox());
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    expect(api.writes, 0);
    expect(tester.takeException(), isNull);
  });

  testWidgets('a change of Bot does not write for the old one', (tester) async {
    final api = _Api();
    api.answers['bot_a'] = {'scope': 'bot_a', 'entries': []};
    api.answers['bot_b'] = {'scope': 'bot_b', 'entries': []};
    addTearDown(api.close);
    final key = GlobalKey();
    await tester.pumpWidget(memory(api, botId: 'bot_a', key: key));
    await pumpUntil(tester, () => find.text('还没有记忆').evaluate().isNotEmpty);

    await tester.tap(addEntry());
    await pumpUntil(tester, () => formField().evaluate().isNotEmpty);
    await tester.enterText(formField(), '新的记忆');
    await tester.pump();
    // The Bot changes while the form is open.
    await tester.pumpWidget(memory(api, botId: 'bot_b', key: key));
    await tester.pump();
    await tester.tap(saveButton());
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    // Nothing was written, and certainly not for the old Bot.
    expect(api.writes, 0);
    expect(api.remembers, isEmpty);
  });

  testWidgets('another service is a different owner and the old one is never closed', (tester) async {
    final first = _Api();
    final second = _Api();
    first.answers['bot_a'] = {'scope': 'bot_a', 'entries': []};
    second.answers['bot_a'] = {'scope': 'bot_a', 'entries': []};
    addTearDown(first.close);
    addTearDown(second.close);
    final key = GlobalKey();
    await tester.pumpWidget(memory(first, key: key));
    await pumpUntil(tester, () => find.text('还没有记忆').evaluate().isNotEmpty);

    await tester.pumpWidget(memory(second, key: key));
    await pumpUntil(tester, () => first.scopes.isNotEmpty && second.scopes.isNotEmpty);

    // The page reads from the new service and closes neither of them.
    expect(second.scopes, ['bot_a']);
    expect(first.closed, 0);
    expect(second.closed, 0);
  });

  for (final width in [390.0, 1024.0]) {
    testWidgets('the change form has no overflow at width $width', (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = Size(width, 900);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final api = _Api();
      api.answers['bot_a'] = {'scope': 'bot_a', 'entries': []};
      addTearDown(api.close);
      await tester.pumpWidget(memory(api));
      await pumpUntil(tester, () => find.text('还没有记忆').evaluate().isNotEmpty);

      await tester.tap(addEntry());
      await pumpUntil(tester, () => formField().evaluate().isNotEmpty);
      await tester.enterText(formField(), List.filled(40, '很长的一段记忆').join());
      await tester.pump();
      expect(tester.takeException(), isNull);
    });
  }
}
