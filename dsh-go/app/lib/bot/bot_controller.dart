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
  bool sending = false;
  bool connected = false;
  bool _disposed = false;
  int _selection = 0;
  int _messageSequence = 0;
  StreamSubscription<Map<String, Object?>>? _events;
  Timer? _refreshTimer;
  Future<void>? _refreshing;

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
        connected = true;
        _changed();
        if (_refreshTimer?.isActive != true) {
          _refreshTimer = Timer(const Duration(milliseconds: 100), refresh);
        }
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

  Future<void> refresh() => _refreshing ??= _refresh().whenComplete(() => _refreshing = null);

  Future<void> _refresh() async {
    try {
      final state = await api.state();
      if (_disposed) return;
      snapshot = state;
      error = null;
      if (selectedChat != null) await _loadMessages(selectedChat!, _selection);
    } catch (failure) {
      if (!_disposed) error = failure;
    } finally {
      loading = false;
      _changed();
    }
  }

  Future<void> select(String? chat) async {
    selectedChat = chat;
    messages = [];
    error = null;
    final generation = ++_selection;
    _changed();
    if (chat == null) return;
    try {
      await _loadMessages(chat, generation);
    } catch (failure) {
      if (generation == _selection) error = failure;
    }
    _changed();
  }

  Future<void> _loadMessages(String chat, int generation) async {
    final page = await api.messages(chat);
    if (_disposed || generation != _selection || selectedChat != chat) return;
    messages = _rows(page['messages']);
  }

  Future<bool> send(String text) async {
    final chat = selectedChat;
    if (chat == null || sending || text.trim().isEmpty) return false;
    sending = true;
    error = null;
    _changed();
    try {
      await api.sendMessage(
        chat,
        text.trim(),
        clientMessageId: 'android-${DateTime.now().microsecondsSinceEpoch}-${_messageSequence++}',
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

  Future<void> createBot(String name, String role) async {
    final response = await api.createBot({'name': name.trim(), 'role': role.trim()});
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
    unawaited(_events?.cancel());
    super.dispose();
  }
}
