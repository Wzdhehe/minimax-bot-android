import 'dart:async';

import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/foundation.dart';

/// State for the built-in Bot service, independent of the old DSH chat UI.
class BotController extends ChangeNotifier {
  BotController(this.api);
  final MiniMaxBotApi api;
  Map<String, Object?> snapshot = {};
  List<Map<String, Object?>> messages = [];
  String? selectedChat;
  Object? error;
  bool loading = true;
  bool messagesLoading = false;
  bool sending = false;
  bool connected = false;
  bool _disposed = false;
  int _selection = 0;
  int _messageSequence = 0;
  StreamSubscription<Map<String, Object?>>? _events;
  final _eventBus = StreamController<Map<String, Object?>>.broadcast();
  Stream<Map<String, Object?>> get events => _eventBus.stream;
  Timer? _refreshTimer;
  Timer? _paintTimer;
  int _historyRequest = 0;
  bool _readingHistory = false;
  bool _readingState = false;
  final _liveHistory = <String, Map<String, Object?>>{};
  final _liveSummaries = <String, Object?>{};
  Future<void>? _refreshing;
  bool _refreshAgain = false;

  List<Map<String, Object?>> get bots => _rows(snapshot['bots']);
  List<Map<String, Object?>> get groups => _rows(snapshot['groups']);
  static List<Map<String, Object?>> _rows(Object? value) =>
      value is List ? value.whereType<Map>().map((row) => Map<String, Object?>.from(row)).toList() : [];

  void _changed() {
    if (!_disposed) notifyListeners();
  }

  Future<void> start() async {
    await _events?.cancel();
    if (_disposed) return;
    _events = api.events().listen(
      (event) {
        if (_disposed) return;
        _eventBus.add(event);
        if (!connected) {
          connected = true;
          _changed();
        }
        _applyEvent(event);
      },
      onError: (Object failure) {
        connected = false;
        error = failure;
        _changed();
      },
      onDone: () {
        connected = false;
        _changed();
      },
    );
    await refresh();
  }

  void _paintSoon() {
    if (_paintTimer?.isActive == true) return;
    _paintTimer = Timer(const Duration(milliseconds: 32), _changed);
  }

  void _applyEvent(Map<String, Object?> event) {
    final chat = event['chatId'];
    final message = event['message'];
    if (event['type'] == 'message.upsert' &&
        chat is String &&
        message is Map &&
        message['id'] is String &&
        (message['id'] as String).isNotEmpty) {
      if (chat == selectedChat) {
        final row = Map<String, Object?>.from(message);
        final id = row['id'] as String;
        if (_readingHistory) {
          _liveHistory[id] = row;
          if (_liveHistory.length > 500) _liveHistory.remove(_liveHistory.keys.first);
        }
        final index = messages.indexWhere((item) => item['id'] == id);
        if (index < 0) {
          messages = [...messages, row];
          if (messages.length > 500) messages = messages.sublist(messages.length - 500);
        } else {
          messages[index] = row;
        }
        _paintSoon();
      }
      return;
    }
    if (event['type'] == 'chat.summary' && chat is String && event['summary'] is Map) {
      final chats = snapshot['chats'];
      snapshot = {
        ...snapshot,
        'chats': {if (chats is Map) ...Map<String, Object?>.from(chats), chat: event['summary']},
      };
      if (_readingState) _liveSummaries[chat] = event['summary'];
      _paintSoon();
      return;
    }
    if (event['type'] == 'chat.reset' && chat == selectedChat) {
      // Invalidate a history read started before the reset.
      _historyRequest++;
      _liveHistory.clear();
      messages = [];
      _paintSoon();
    }
    // Metadata/reconnect events still reconcile with the authoritative service.
    // Streaming token events above cause no HTTP reads.
    if (_refreshTimer?.isActive != true) {
      _refreshTimer = Timer(const Duration(milliseconds: 200), refresh);
    }
  }

  Future<void> refresh() {
    _refreshAgain = true;
    return _refreshing ??= _drainRefresh().whenComplete(() => _refreshing = null);
  }

  Future<void> _drainRefresh() async {
    while (_refreshAgain && !_disposed) {
      _refreshAgain = false;
      await _refresh();
    }
  }

  Future<void> _refresh() async {
    _readingState = true;
    _liveSummaries.clear();
    try {
      final state = await api.state();
      if (_disposed) return;
      final chats = state['chats'];
      snapshot = {
        ...state,
        'chats': {if (chats is Map) ...Map<String, Object?>.from(chats), ..._liveSummaries},
      };
      _readingState = false;
      _liveSummaries.clear();
      error = null;
      if (selectedChat != null) await _loadMessages(selectedChat!, _selection);
    } catch (failure) {
      if (!_disposed) error = failure;
    } finally {
      _readingState = false;
      _liveSummaries.clear();
      loading = false;
      _changed();
    }
  }

  Future<void> select(String? chat) async {
    selectedChat = chat;
    _historyRequest++;
    _readingHistory = false;
    messages = [];
    _liveHistory.clear();
    messagesLoading = chat != null;
    error = null;
    final generation = ++_selection;
    _changed();
    if (chat == null) return;
    try {
      await _loadMessages(chat, generation);
    } catch (failure) {
      if (generation == _selection) error = failure;
    }
    if (generation == _selection) messagesLoading = false;
    _changed();
  }

  Future<void> _loadMessages(String chat, int generation) async {
    final request = ++_historyRequest;
    _readingHistory = true;
    _liveHistory.clear();
    try {
      final page = await api.messages(chat);
      if (_disposed || request != _historyRequest || generation != _selection || selectedChat != chat) return;
      final rows = _rows(page['messages']);
      final ids = rows.map((row) => row['id']).toSet();
      messages = [
        for (final row in rows) _liveHistory[row['id']] ?? row,
        for (final row in _liveHistory.values)
          if (!ids.contains(row['id'])) row,
      ];
      if (messages.length > 500) messages = messages.sublist(messages.length - 500);
    } finally {
      if (request == _historyRequest) {
        _readingHistory = false;
        _liveHistory.clear();
      }
    }
  }

  Future<bool> send(String text, {List<String> attachments = const []}) async {
    final chat = selectedChat;
    if (chat == null || sending || (text.trim().isEmpty && attachments.isEmpty)) return false;
    sending = true;
    error = null;
    _changed();
    try {
      await api.sendMessage(
        chat,
        text.trim(),
        clientMessageId: 'android-${DateTime.now().microsecondsSinceEpoch}-${_messageSequence++}',
        attachments: attachments,
      );
      await refresh();
      return true;
    } catch (failure) {
      if (!_disposed) error = failure;
      return false;
    } finally {
      sending = false;
      _changed();
    }
  }

  Future<void> createBot(String name, String role, {Map<String, Object?> additional = const {}}) async {
    final response = await api.createBot({...additional, 'name': name.trim(), 'role': role.trim()});
    await refresh();
    final bot = response['bot'] as Map;
    await select('bot:${bot['id']}');
  }

  Future<void> createGroup(String name, List<String> members) async {
    final response = await api.createGroup({'name': name.trim(), 'members': members});
    await refresh();
    final group = response['group'] as Map;
    await select('group:${group['id']}');
  }

  Future<void> cancel() async {
    if (selectedChat != null) await api.cancelChat(selectedChat!);
    await refresh();
  }

  @override
  void dispose() {
    _disposed = true;
    _refreshTimer?.cancel();
    _paintTimer?.cancel();
    unawaited(_events?.cancel());
    unawaited(_eventBus.close());
    super.dispose();
  }
}
