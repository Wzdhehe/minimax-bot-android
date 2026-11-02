import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

class McodePluginException implements Exception {
  const McodePluginException(this.code, this.message);
  final String code;
  final String message;
  @override
  String toString() => 'McodePluginException($code): $message';
}

/// Plugin-only client for the pinned MiniMax Code WebUI wire protocol.
/// The host supplies a per-launch credential. Mutations are never retried.
class McodePluginsApi {
  McodePluginsApi(Uri endpoint, {required String token, this.timeout = const Duration(minutes: 2)})
    : _endpoint = _validate(endpoint),
      _token = token;

  final Uri _endpoint;
  final String _token;
  final Duration timeout;
  static final _random = Random.secure();

  static Uri _validate(Uri uri) {
    final address = InternetAddress.tryParse(uri.host);
    if (uri.scheme != 'ws' ||
        address == null ||
        !address.isLoopback ||
        uri.hasQuery ||
        uri.hasFragment ||
        uri.userInfo.isNotEmpty) {
      throw ArgumentError('Expected a local WebUI WebSocket endpoint');
    }
    return uri;
  }

  Future<Object?> _call(String action, Map<String, Object?> input) async {
    final client = HttpClient()..connectionTimeout = const Duration(seconds: 15);
    // Restrict redirect/proxy connections to the original loopback endpoint.
    client.findProxy = (_) => 'DIRECT';
    client.connectionFactory = (uri, proxyHost, proxyPort) {
      if (uri.host != _endpoint.host || uri.port != _endpoint.port) {
        throw const McodePluginException('destination_changed', 'WebUI changed its destination');
      }
      return Socket.startConnect(uri.host, uri.port);
    };
    WebSocket? socket;
    try {
      try {
        socket = await WebSocket.connect(
          _endpoint.replace(queryParameters: {'token': _token}).toString(),
          customClient: client,
        ).timeout(const Duration(seconds: 20));
      } catch (_) {
        // Dart's handshake exception contains the URL and therefore the token.
        throw const McodePluginException('connection_failed', 'Cannot connect to the local WebUI');
      }
      final id = List.generate(16, (_) => _random.nextInt(256).toRadixString(16).padLeft(2, '0')).join();
      socket.add(
        jsonEncode({
          'protocolVersion': 1,
          'kind': 'request',
          'requestId': id,
          'operation': 'pluginManagement',
          'body': {'action': action, 'input': input},
        }),
      );
      return await _response(socket, id).timeout(timeout);
    } finally {
      unawaited(socket?.close());
      client.close(force: true);
    }
  }

  Future<Object?> _response(WebSocket socket, String id) async {
    await for (final payload in socket) {
      if (payload is! String || payload.length > 2 * 1024 * 1024) {
        throw const FormatException('Invalid WebUI frame');
      }
      final frame = jsonDecode(payload);
      if (frame is! Map<String, dynamic> || frame['protocolVersion'] != 1) {
        throw const FormatException('Unsupported WebUI protocol');
      }
      final anonymousError = frame['kind'] == 'error' && frame['requestId'] == 'anonymous';
      if (frame['requestId'] != id && !anonymousError) continue;
      if (frame['kind'] == 'error') {
        throw McodePluginException(
          frame['code']?.toString() ?? 'wire_error',
          frame['message']?.toString() ?? 'WebUI rejected the request',
        );
      }
      if (frame['kind'] == 'response' && frame.containsKey('body')) return frame['body'];
    }
    throw const McodePluginException('connection_closed', 'WebUI closed before answering');
  }

  Future<Object?> marketplace({String keyword = '', int limit = 40, String? cursor, int? source}) =>
      _call('listMarketplacePlugins', {'keyword': keyword, 'limit': limit, 'cursor': ?cursor, 'source': ?source});
  Future<Object?> installed({String keyword = '', int limit = 100, int offset = 0}) =>
      _call('listInstalledPlugins', {'keyword': keyword, 'limit': limit, 'offset': offset});
  Future<Object?> previewGithub(String url) => _call('previewGithubPlugin', {'url': url});
  Future<Object?> importGithub({required String repositoryUrl, required String commitSha, String? subPath}) =>
      _call('importGithubPlugin', {
        'source': {'repositoryUrl': repositoryUrl, 'commitSha': commitSha, 'subPath': ?subPath},
      });
  Future<Object?> install(String name, {int? source}) => _mutate('installPlugin', name, source);
  Future<Object?> uninstall(String name, {int? source}) => _mutate('uninstallPlugin', name, source);
  Future<Object?> enable(String name, {int? source}) => _mutate('enablePlugin', name, source);
  Future<Object?> disable(String name, {int? source}) => _mutate('disablePlugin', name, source);
  Future<Object?> _mutate(String action, String name, int? source) =>
      _call(action, {'pluginName': name, 'source': ?source});
}
