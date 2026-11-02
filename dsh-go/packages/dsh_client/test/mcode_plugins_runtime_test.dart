import 'dart:async';
import 'dart:io';

import 'package:dsh_client/dsh_client.dart';
import 'package:test/test.dart';

void main() {
  final entry = Platform.environment['MCODE_WEBUI_ENTRY'];
  test(
    'reads installed plugins from a full upstream WebUI runtime',
    () async {
      final data = await Directory.systemTemp.createTemp('mcode-market-test-');
      final probe = await ServerSocket.bind(InternetAddress.loopbackIPv4, 0);
      final port = probe.port;
      await probe.close();
      final host = await Process.start(
        'node',
        [entry!],
        environment: {'MINIMAX_DATA_DIR': data.path, 'WEBUI_SERVER_PORT': '$port'},
      );
      // Only fresh temporary data is used. Do not copy runtime diagnostics into
      // the test report: production logs can contain account information.
      final stdoutDone = host.stdout.drain<void>();
      final stderrDone = host.stderr.drain<void>();
      addTearDown(() async {
        host.kill();
        await host.exitCode.timeout(
          const Duration(seconds: 10),
          onTimeout: () {
            host.kill(ProcessSignal.sigkill);
            return -1;
          },
        );
        await Future.wait([stdoutDone, stderrDone]);
        await data.delete(recursive: true);
      });
      final http = HttpClient()..connectionTimeout = const Duration(milliseconds: 300);
      var ready = false;
      try {
        for (var attempt = 0; attempt < 200; attempt++) {
          try {
            final request = await http.getUrl(Uri.parse('http://127.0.0.1:$port/'));
            final response = await request.close().timeout(const Duration(seconds: 1));
            await response.drain<void>();
            if (response.statusCode == 200) {
              ready = true;
              break;
            }
          } on Exception {
            await Future<void>.delayed(const Duration(milliseconds: 100));
          }
        }
      } finally {
        http.close(force: true);
      }
      expect(ready, isTrue, reason: 'upstream WebUI did not start');
      final api = McodePluginsApi(Uri.parse('ws://127.0.0.1:$port/'), token: '');
      final result = await api.installed();
      expect(result, isA<Map<String, dynamic>>());
      expect((result as Map)['plugins'], isA<List<dynamic>>());
    },
    skip: entry == null ? 'Set MCODE_WEBUI_ENTRY to the built upstream launcher' : false,
    timeout: const Timeout(Duration(minutes: 2)),
  );
}
