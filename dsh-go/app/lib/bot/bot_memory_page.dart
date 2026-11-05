// SPDX-License-Identifier: AGPL-3.0-only
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/material.dart';
import 'package:mbot_settings/mbot_settings.dart';

import '../l10n/l10n.dart';

/// The memory of one Bot, read only.
///
/// The page owns nothing of the service: the caller keeps the API and this
/// page never closes it. One read per Bot at a time, and a newer Bot replaces
/// the older one — an answer that belongs to an older read may not write over
/// the newer result.
///
/// The service answers `GET /api/memory/{scope}` with the scope, the entries
/// and the budget. Only the entries' text is shown: no ids, no revisions, no
/// raw payload, and no exception text.
class BotMemoryPage extends StatefulWidget {
  const BotMemoryPage({super.key, required this.api, required this.botId});

  /// The service the parent owns. This page never closes it.
  final MiniMaxBotApi api;

  /// The Bot whose memory is read. The scope of the read is this id.
  final String botId;

  @override
  State<BotMemoryPage> createState() => _BotMemoryPageState();
}

class _BotMemoryPageState extends State<BotMemoryPage> {
  /// The texts the service sent, in its order.
  List<String> _entries = const [];
  bool _loading = true;
  bool _failed = false;

  /// The scope the state below belongs to.
  String? _scope;
  /// The service the state below belongs to.
  MiniMaxBotApi? _api;
  /// The newest read. An older one may not write a result.
  int _read = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void didUpdateWidget(covariant BotMemoryPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.api != widget.api || oldWidget.botId != widget.botId) {
      _load();
    }
  }

  /// Reads the current Bot's memory. A read that is already running for this
  /// Bot and service is not repeated; a retry waits for nothing.
  Future<void> _load() async {
    final api = widget.api;
    final scope = widget.botId;
    if (_loading && _scope == scope && _api == api) return;
    final read = ++_read;
    setState(() {
      _scope = scope;
      _api = api;
      _loading = true;
    });
    try {
      final payload = await api.memory(scope);
      if (!mounted || read != _read) return;
      final entries = _textsOf(payload, scope);
      if (entries == null) {
        // A payload that is not this Bot's memory is a failed read, not an
        // empty memory: its entries are never shown.
        setState(() {
          _failed = true;
          _loading = false;
        });
        return;
      }
      setState(() {
        _entries = entries;
        _failed = false;
        _loading = false;
      });
    } catch (_) {
      if (!mounted || read != _read) return;
      setState(() {
        _failed = true;
        _loading = false;
      });
    }
  }

  /// The texts of a memory payload, or null when the payload does not belong
  /// to [scope] or does not have the shape the service promises.
  static List<String>? _textsOf(Map<String, Object?> payload, String scope) {
    if (payload['scope'] is! String || payload['scope'] != scope) return null;
    final entries = payload['entries'];
    if (entries is! List) return null;
    final texts = <String>[];
    for (final entry in entries) {
      if (entry is! Map) return null;
      final text = entry['text'];
      if (text is! String) return null;
      texts.add(text);
    }
    return texts;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(context.l10n.botsMemory)),
      body: Align(
        alignment: Alignment.topCenter,
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 680),
          child: MbotLoadTransition(
            // A retry keeps the failure on screen; only a first read is a
            // loading screen.
            loading: _loading && !_failed,
            child: _body(),
          ),
        ),
      ),
    );
  }

  Widget _body() {
    if (_failed) {
      return ListView(
        padding: const EdgeInsets.all(24),
        children: [
          Text(context.l10n.botsMemoryReadFailed),
          const SizedBox(height: 8),
          TextButton(
            onPressed: _loading ? null : _load,
            child: Text(context.l10n.botsTapRetry),
          ),
        ],
      );
    }
    if (_entries.isEmpty) {
      return ListView(
        padding: const EdgeInsets.all(24),
        children: [Text(context.l10n.botsNoMemory)],
      );
    }
    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        for (final entry in _entries) ...[
          SelectableText(entry),
          const SizedBox(height: 12),
        ],
      ],
    );
  }
}
