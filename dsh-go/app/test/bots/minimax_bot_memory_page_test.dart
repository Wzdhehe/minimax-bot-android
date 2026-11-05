// SPDX-License-Identifier: AGPL-3.0-only
import 'dart:async';

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
  /// Every write the page tried.
  int writes = 0;
  /// How often the page closed the service it does not own.
  int closed = 0;

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
    final error = failures[scope];
    if (error != null) throw error;
    return answers[scope] ?? {'scope': scope, 'entries': []};
  }

  @override
  Future<Map<String, Object?>> remember(String scope, Map<String, Object?> entry) async {
    writes++;
    return {};
  }

  @override
  Future<Map<String, Object?>> forget(String scope, String entry) async {
    writes++;
    return {};
  }

  @override
  void close() {
    closed++;
    super.close();
  }
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
    // The row sits below the fold on a short window.
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
    await tester.tap(find.widgetWithText(TextButton, '点一下重试'));
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
    await tester.tap(find.widgetWithText(TextButton, '点一下重试'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    expect(api.scopes, ['bot_a', 'bot_a']);
    // The retry entry is closed while it waits.
    expect(tester.widget<TextButton>(find.widgetWithText(TextButton, '点一下重试')).onPressed, isNull);
    await tester.tap(find.widgetWithText(TextButton, '点一下重试'), warnIfMissed: false);
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
}
