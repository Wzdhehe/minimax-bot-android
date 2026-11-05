// SPDX-License-Identifier: AGPL-3.0-only
import 'dart:convert';
import 'dart:io';

import 'package:dsh_app/bot/bot_runtime_endpoint.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

/// The UI may only talk to a Bot service that native says is running, and it
/// must carry the session token that native handed over.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('minimax/bot-runtime');

  void mock(Object? Function(MethodCall call) handler) {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async => handler(call));
  }

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('no running runtime means no service and no invented url', () async {
    mock((_) => null);
    expect(await const BotRuntimeEndpoint().liveService(), isNull);
  });

  test('a live service is addressed with the token native published', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final authorization = <String>[];
    server.listen((request) async {
      authorization.add(request.headers.value('authorization') ?? '');
      request.response.headers.contentType = ContentType.json;
      request.response.write(jsonEncode({'bots': []}));
      await request.response.close();
    });
    addTearDown(() => server.close(force: true));

    mock((_) => {'baseUrl': 'http://127.0.0.1:${server.port}', 'token': 'a' * 64});
    // The test binding answers every request with 400, so the client under
    // test gets a real one while this case runs.
    final previous = HttpOverrides.current;
    HttpOverrides.global = null;
    try {
      final api = await const BotRuntimeEndpoint().liveService();
      expect(api, isNotNull);
      expect(await api!.state(), {'bots': []});
    } finally {
      HttpOverrides.global = previous;
    }
    expect(authorization, ['Bearer ${'a' * 64}']);
  });

  test('an incomplete descriptor is refused instead of half-trusted', () async {
    mock((_) => {'baseUrl': 'http://127.0.0.1:8788'});
    await expectLater(const BotRuntimeEndpoint().liveService(), throwsFormatException);
    mock((_) => {'baseUrl': 'http://127.0.0.1:8788', 'token': ''});
    await expectLater(const BotRuntimeEndpoint().liveService(), throwsFormatException);
  });

  test('a descriptor that is not a loopback service is refused', () async {
    mock((_) => {'baseUrl': 'http://10.0.0.1:8788', 'token': 'a' * 64});
    await expectLater(const BotRuntimeEndpoint().liveService(), throwsArgumentError);
  });
}
