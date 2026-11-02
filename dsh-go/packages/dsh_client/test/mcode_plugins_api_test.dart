import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:dsh_client/dsh_client.dart';
import 'package:test/test.dart';

void main() {
  late HttpServer server;
  late McodePluginsApi api;
  final requests = <Map<String, dynamic>>[];
  final sockets = <WebSocket>[];
  String responseMode = 'normal';

  setUp(() async {
    requests.clear();
    responseMode = 'normal';
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    api = McodePluginsApi(
      Uri.parse('ws://127.0.0.1:${server.port}/'),
      token: 'launch-secret',
      timeout: const Duration(milliseconds: 150),
    );
    server.listen((request) async {
      expect(request.uri.queryParameters['token'], 'launch-secret');
      final socket = await WebSocketTransformer.upgrade(request);
      sockets.add(socket);
      socket.listen((data) {
        final frame = jsonDecode(data as String) as Map<String, dynamic>;
        requests.add(frame);
        if (responseMode == 'silent') return;
        if (responseMode == 'anonymous') {
          socket.add(
            jsonEncode({
              'protocolVersion': 1,
              'kind': 'error',
              'requestId': 'anonymous',
              'code': 'shutting_down',
              'message': 'Closing',
            }),
          );
          return;
        }
        // Unrelated responses must never settle this mutation.
        socket.add(
          jsonEncode({
            'protocolVersion': 1,
            'kind': 'response',
            'requestId': 'unrelated',
            'body': {'wrong': true},
          }),
        );
        socket.add(
          jsonEncode({
            'protocolVersion': 1,
            'kind': 'response',
            'requestId': frame['requestId'],
            'body': {'ok': true},
          }),
        );
      });
    });
  });
  tearDown(() async {
    for (final socket in sockets) {
      await socket.close();
    }
    sockets.clear();
    await server.close(force: true);
  });

  test('uses the WebUI envelope for plugin operations and pinned GitHub imports', () async {
    expect(await api.marketplace(keyword: 'search', source: 1), {'ok': true});
    await api.previewGithub('https://github.com/example/plugins');
    await api.importGithub(repositoryUrl: 'https://github.com/example/plugins', commitSha: 'a' * 40, subPath: 'plugin');
    await api.enable('example', source: 2);
    expect(requests.map((r) => r['operation']).toSet(), {'pluginManagement'});
    expect(requests.map((r) => r['requestId']).toSet().length, 4);
    final bodies = requests.map((r) => r['body'] as Map<String, dynamic>).toList();
    expect(bodies.map((b) => b['action']), [
      'listMarketplacePlugins',
      'previewGithubPlugin',
      'importGithubPlugin',
      'enablePlugin',
    ]);
    expect((bodies[2]['input'] as Map)['source'], {
      'repositoryUrl': 'https://github.com/example/plugins',
      'commitSha': 'a' * 40,
      'subPath': 'plugin',
    });
  });
  test('preserves anonymous server refusals', () async {
    responseMode = 'anonymous';
    await expectLater(
      api.installed(),
      throwsA(isA<McodePluginException>().having((e) => e.code, 'code', 'shutting_down')),
    );
  });
  test('times out a mutation without retrying it', () async {
    responseMode = 'silent';
    await expectLater(api.install('example'), throwsA(isA<TimeoutException>()));
    expect(requests, hasLength(1));
  });
  test('refuses remote and credential-bearing endpoints', () {
    for (final value in ['ws://example.com/', 'ws://127.0.0.1/?token=bad', 'ws://user@127.0.0.1/']) {
      expect(() => McodePluginsApi(Uri.parse(value), token: ''), throwsArgumentError);
    }
  });
}
