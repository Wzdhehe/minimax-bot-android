/// Local client for the built-in official MiniMax Bot service HTTP API.
///
/// This client does not start the MiniMax host and must be given its local,
/// authenticated proxy URL. It never retries mutations: after a timeout the
/// caller must reconcile state before attempting the operation again.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

class MiniMaxBotException implements Exception {
  const MiniMaxBotException(this.status, this.code, this.message);
  final int status;
  final String code;
  final String message;
  @override
  String toString() => 'MiniMaxBotException($status, $code): $message';
}

class MiniMaxBotApi {
  MiniMaxBotApi(Uri base, {HttpClient? httpClient, Map<String, String> headers = const {}})
    : _base = _localBase(base),
      _client = httpClient ?? HttpClient(),
      _ownsClient = httpClient == null,
      _headers = Map.unmodifiable(headers);

  final Uri _base;
  final HttpClient _client;
  final bool _ownsClient;
  final Map<String, String> _headers;
  static const _maxResponse = 2 * 1024 * 1024;
  static final _botId = RegExp(r'^bot_[a-zA-Z0-9]+$');
  static final _groupId = RegExp(r'^grp_[a-zA-Z0-9]+$');
  static final _chatId = RegExp(r'^(?:bot|group):[a-zA-Z0-9_]+$');
  static final _messageId = RegExp(r'^msg_[a-zA-Z0-9]+$');
  static final _scheduleId = RegExp(r'^sch_[a-zA-Z0-9]+$');
  static final _taskId = RegExp(r'^task_[a-zA-Z0-9]+$');
  static final _helperId = RegExp(r'^hlp_[a-zA-Z0-9]+$');
  static final _memoryId = RegExp(r'^[a-f0-9]{8}$');

  static Uri _localBase(Uri uri) {
    final ip = InternetAddress.tryParse(uri.host);
    if ((uri.scheme != 'http' && uri.scheme != 'https') ||
        ip == null ||
        !ip.isLoopback ||
        uri.hasQuery ||
        uri.hasFragment ||
        uri.userInfo.isNotEmpty) {
      throw ArgumentError('MiniMax Bot host must be a loopback IP URL without user info, query or fragment');
    }
    return uri;
  }

  String _id(String value, RegExp pattern) {
    if (!pattern.hasMatch(value)) throw ArgumentError('Invalid Bot identifier');
    return value;
  }

  Uri _uri(String path, [Map<String, String>? query]) {
    final prefix = _base.path.endsWith('/') ? _base.path.substring(0, _base.path.length - 1) : _base.path;
    return _base.replace(path: '$prefix$path', queryParameters: query);
  }

  Future<Map<String, Object?>> _request(
    String method,
    String path, {
    Map<String, Object?>? body,
    Map<String, String>? query,
  }) async {
    final request = await _client.openUrl(method, _uri(path, query)).timeout(const Duration(seconds: 20));
    request.followRedirects = false;
    _headers.forEach(request.headers.set);
    if (body != null) {
      request.headers.contentType = ContentType.json;
      request.write(jsonEncode(body));
    }
    final response = await request.close().timeout(const Duration(seconds: 60));
    final bytes = BytesBuilder(copy: false);
    await for (final chunk in response.timeout(const Duration(seconds: 60))) {
      if (bytes.length + chunk.length > _maxResponse) {
        throw const FormatException('MiniMax Bot response is too large');
      }
      bytes.add(chunk);
    }
    final decoded = jsonDecode(utf8.decode(bytes.takeBytes()));
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('MiniMax Bot response is not an object');
    }
    final result = Map<String, Object?>.from(decoded);
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw MiniMaxBotException(
        response.statusCode,
        result['error']?.toString() ?? 'http_error',
        result['message']?.toString() ?? 'Request failed',
      );
    }
    return result;
  }

  Future<Map<String, Object?>> state() => _request('GET', '/api/state');
  Future<Map<String, Object?>> models() => _request('GET', '/api/models');
  Future<Map<String, Object?>> updateSettings(Map<String, Object?> settings) =>
      _request('PUT', '/api/settings', body: settings);
  Future<Map<String, Object?>> createBot(Map<String, Object?> properties) =>
      _request('POST', '/api/bots', body: properties);
  Future<Map<String, Object?>> updateBot(String id, Map<String, Object?> properties) =>
      _request('PATCH', '/api/bots/${_id(id, _botId)}', body: properties);
  Future<Map<String, Object?>> deleteBot(String id) => _request('DELETE', '/api/bots/${_id(id, _botId)}');
  Future<Map<String, Object?>> createGroup(Map<String, Object?> properties) =>
      _request('POST', '/api/groups', body: properties);
  Future<Map<String, Object?>> updateGroup(String id, Map<String, Object?> properties) =>
      _request('PATCH', '/api/groups/${_id(id, _groupId)}', body: properties);
  Future<Map<String, Object?>> deleteGroup(String id) => _request('DELETE', '/api/groups/${_id(id, _groupId)}');
  Future<Map<String, Object?>> messages(String chat, {int limit = 200, String? before}) {
    if (limit < 1 || limit > 500) throw ArgumentError.value(limit, 'limit');
    if (before != null) _id(before, _messageId);
    return _request('GET', '/api/chats/${_id(chat, _chatId)}/messages', query: {'limit': '$limit', 'before': ?before});
  }

  Future<Map<String, Object?>> sendMessage(
    String chat,
    String text, {
    required String clientMessageId,
    List<Map<String, Object?>> attachments = const [],
  }) => _request(
    'POST',
    '/api/chats/${_id(chat, _chatId)}/messages',
    body: {'text': text, 'clientMessageId': clientMessageId, 'attachments': attachments},
  );
  Future<Map<String, Object?>> cancelChat(String chat) => _request('POST', '/api/chats/${_id(chat, _chatId)}/cancel');
  Future<Map<String, Object?>> resetChat(String chat) => _request('POST', '/api/chats/${_id(chat, _chatId)}/reset');
  Future<Map<String, Object?>> resumePending(String chat) =>
      _request('POST', '/api/chats/${_id(chat, _chatId)}/resume-pending');
  Future<Map<String, Object?>> retryMessage(String chat, String message) =>
      _request('POST', '/api/chats/${_id(chat, _chatId)}/messages/${_id(message, _messageId)}/retry');
  Future<Map<String, Object?>> react(String chat, String message, {required String emoji, required bool selected}) =>
      _request(
        'PUT',
        '/api/chats/${_id(chat, _chatId)}/messages/${_id(message, _messageId)}/reactions',
        body: {'emoji': emoji, 'selected': selected},
      );
  Future<Map<String, Object?>> answerQuestion(String chat, String message, List<Object?> answers) => _request(
    'POST',
    '/api/chats/${_id(chat, _chatId)}/messages/${_id(message, _messageId)}/answer',
    body: {'answers': answers},
  );
  Future<Map<String, Object?>> memory(String scope) =>
      _request('GET', '/api/memory/${scope == 'team' ? scope : _id(scope, _botId)}');
  Future<Map<String, Object?>> remember(String scope, Map<String, Object?> entry) =>
      _request('POST', '/api/memory/${scope == 'team' ? scope : _id(scope, _botId)}', body: entry);
  Future<Map<String, Object?>> forget(String scope, String entry) =>
      _request('DELETE', '/api/memory/${scope == 'team' ? scope : _id(scope, _botId)}/${_id(entry, _memoryId)}');
  Future<Map<String, Object?>> createSchedule(Map<String, Object?> schedule) =>
      _request('POST', '/api/schedules', body: schedule);
  Future<Map<String, Object?>> deleteSchedule(String id) =>
      _request('DELETE', '/api/schedules/${_id(id, _scheduleId)}');
  Future<Map<String, Object?>> runSchedule(String id) => _request('POST', '/api/schedules/${_id(id, _scheduleId)}/run');
  Future<Map<String, Object?>> stopTask(String id) => _request('POST', '/api/tasks/${_id(id, _taskId)}/stop');
  Future<Map<String, Object?>> stopHelper(String id) => _request('POST', '/api/helpers/${_id(id, _helperId)}/stop');
  Future<Map<String, Object?>> usage({int days = 7}) {
    if (days < 1 || days > 90) throw ArgumentError.value(days, 'days');
    return _request('GET', '/api/usage', query: {'days': '$days'});
  }

  /// A new stream per subscription. Unknown event types are retained verbatim.
  Stream<Map<String, Object?>> events() async* {
    final request = await _client.getUrl(_uri('/api/events')).timeout(const Duration(seconds: 20));
    request.followRedirects = false;
    _headers.forEach(request.headers.set);
    final response = await request.close().timeout(const Duration(seconds: 20));
    if (response.statusCode != 200) {
      throw MiniMaxBotException(response.statusCode, 'event_stream', 'Event stream unavailable');
    }
    final data = <String>[];
    var size = 0;
    await for (final line
        in response.timeout(const Duration(seconds: 50)).transform(utf8.decoder).transform(const LineSplitter())) {
      if (line.isEmpty) {
        if (data.isEmpty) continue;
        final decoded = jsonDecode(data.join('\n'));
        if (decoded is! Map<String, dynamic>) {
          throw const FormatException('MiniMax Bot event is not an object');
        }
        yield Map<String, Object?>.from(decoded);
        data.clear();
        size = 0;
      } else if (line.startsWith('data:')) {
        final value = line.substring(5).replaceFirst(RegExp(r'^ '), '');
        size += value.length;
        if (size > _maxResponse) {
          throw const FormatException('MiniMax Bot event is too large');
        }
        data.add(value);
      }
    }
  }

  void close() {
    if (_ownsClient) _client.close(force: true);
  }
}
