import 'dart:convert';
import 'dart:io';

import 'package:dsh_client/dsh_client.dart';
import 'package:test/test.dart';

void main() {
  late Directory temp;
  late Process host;
  late MiniMaxBotApi api;
  late int port;

  setUpAll(() async {
    temp = await Directory.systemTemp.createTemp('minimax-bot-client-');
    final socket = await ServerSocket.bind(InternetAddress.loopbackIPv4, 0);
    port = socket.port;
    await socket.close();
    final root = Directory('../../runtime/vendor/minimax-bot').absolute.path;
    host = await Process.start('node', ['test/fixtures/minimax_bot_host.mjs', root, temp.path, '$port']);
    final line = await host.stdout
        .transform(utf8.decoder)
        .transform(const LineSplitter())
        .first
        .timeout(const Duration(seconds: 15));
    expect(line, 'READY');
    api = MiniMaxBotApi(Uri.parse('http://127.0.0.1:$port'));
  });

  tearDownAll(() async {
    api.close();
    host.kill(ProcessSignal.sigterm);
    await host.exitCode.timeout(const Duration(seconds: 10));
    await temp.delete(recursive: true);
  });

  test('talks to the real MiniMax Bot service for bots, groups and history', () async {
    final initial = await api.state();
    final initialBots = initial['bots'] as List<Object?>;
    expect(initialBots, isNotEmpty);
    final mainBot = (initialBots.first as Map<String, dynamic>)['id'] as String;
    final created = (await api.createBot({'name': '研究员', 'role': '调研'}))['bot'] as Map<String, dynamic>;
    final id = created['id'] as String;
    expect(created['name'], '研究员');
    final changed = (await api.updateBot(id, {'role': '写作'}))['bot'] as Map<String, dynamic>;
    expect(changed['role'], '写作');
    final group =
        (await api.createGroup({
              'name': '协作组',
              'members': [mainBot, id],
            }))['group']
            as Map<String, dynamic>;
    final groupId = group['id'] as String;
    expect(group['memberIds'], containsAll([mainBot, id]));
    final messages = await api.messages('group:$groupId');
    expect(messages['chatId'], 'group:$groupId');
    expect(messages['messages'], isA<List<Object?>>());
    expect((await api.events().first.timeout(const Duration(seconds: 5)))['type'], 'hello');
    await api.deleteGroup(groupId);
    await api.deleteBot(id);
  });

  test('surfaces server errors and refuses non-local or malformed destinations', () async {
    expect(await api.testProvider('unconfigured', 'model'), {'ok': false, 'code': 'provider_unavailable'});
    await expectLater(api.createBot({'name': ''}), throwsA(isA<MiniMaxBotException>()));
    expect(() => MiniMaxBotApi(Uri.parse('https://example.com')), throwsArgumentError);
    expect(() => MiniMaxBotApi(Uri.parse('http://127.0.0.1:$port/?token=secret')), throwsArgumentError);
    expect(() => api.updateBot('../bad', {}), throwsArgumentError);
  });
}
