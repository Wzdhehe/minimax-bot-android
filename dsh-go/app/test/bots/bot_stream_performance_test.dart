// SPDX-License-Identifier: AGPL-3.0-only
import 'dart:async';

import 'package:dsh_app/bot/bot_controller.dart';
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter_test/flutter_test.dart';

class _LiveApi extends MiniMaxBotApi {
  _LiveApi() : super(Uri.parse('http://127.0.0.1:8788'));
  final feed = StreamController<Map<String, Object?>>();
  Completer<Map<String, Object?>>? history;
  Completer<Map<String, Object?>>? stateRead;
  int states = 0;
  int histories = 0;
  @override
  Stream<Map<String, Object?>> events() => feed.stream;
  @override
  Future<Map<String, Object?>> state() {
    states++;
    return stateRead?.future ?? Future.value({'bots': [], 'chats': {}});
  }

  @override
  Future<Map<String, Object?>> messages(String chat, {int limit = 200, String? before}) {
    histories++;
    return history?.future ?? Future.value({'messages': []});
  }
}

void main() {
  testWidgets('token burst does no HTTP reads and late history keeps live text', (tester) async {
    final api = _LiveApi();
    final bot = BotController(api);
    addTearDown(() async {
      bot.dispose();
      await api.feed.close();
      api.close();
    });
    await bot.start();
    api.history = Completer();
    final selected = bot.select('bot:bot_a');
    var paints = 0;
    bot.addListener(() => paints++);
    for (var i = 0; i < 100; i++) {
      api.feed.add({
        'type': 'message.upsert',
        'chatId': 'bot:bot_a',
        'message': {'id': 'msg_reply', 'text': 'token $i', 'role': 'bot'},
      });
    }
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 40));
    expect(paints, lessThanOrEqualTo(2));
    expect(bot.messages.single['text'], 'token 99');
    api.history!.complete({
      'messages': [
        {'id': 'msg_old', 'text': 'earlier message'},
        {'id': 'msg_reply', 'text': 'stale'},
      ],
    });
    await selected;
    await tester.pump(const Duration(seconds: 1));
    expect(bot.messages.map((m) => m['text']), ['earlier message', 'token 99']);
    expect(api.states, 1);
    expect(api.histories, 1);
  });

  testWidgets('late state keeps newer summaries; metadata events still reconcile', (tester) async {
    final api = _LiveApi();
    final bot = BotController(api);
    addTearDown(() async {
      bot.dispose();
      await api.feed.close();
      api.close();
    });
    await bot.start();
    api.stateRead = Completer();
    final refresh = bot.refresh();
    api.feed.add({
      'type': 'chat.summary',
      'chatId': 'bot:bot_a',
      'summary': {'preview': 'fresh'},
    });
    await tester.pump();
    api.stateRead!.complete({
      'chats': {
        'bot:bot_a': {'preview': 'stale'},
      },
    });
    await refresh;
    expect(((bot.snapshot['chats'] as Map)['bot:bot_a'] as Map)['preview'], 'fresh');
    api.stateRead = null;
    api.feed.add({'type': 'state.changed'});
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 220));
    expect(api.states, 3);
  });
}
