// SPDX-License-Identifier: AGPL-3.0-only
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/material.dart';
import 'package:mbot_settings/mbot_settings.dart';

import '../l10n/l10n.dart';

/// One memory entry as the service described it.
///
/// The service gives an entry an id and a revision. An entry that has no
/// usable id cannot be edited or deleted, and one that has no revision
/// cannot be edited: the page then shows the text and offers nothing.
class _MemoryEntry {
  const _MemoryEntry({required this.text, this.id, this.revision});

  final String text;

  /// The service's id, when it has one this client can use.
  final String? id;

  /// The service's revision. It is a string the service compares, not a
  /// counter, and it is only meaningful together with [id].
  final String? revision;

  bool get editable => id != null && revision != null && revision!.isNotEmpty;
  bool get deletable => id != null;
}

/// What went wrong with the last change.
enum _ChangeFailure {
  /// The service says the entry changed under us.
  conflict,

  /// The service refused the change.
  rejected,

  /// The change may or may not have been written.
  unknown,
}

/// The service's own codes that mean it refused the change before it
/// wrote anything. Every other code stays unknown: a service that fails
/// after it accepted a change may say so with a code this client has
/// never heard of, and that is not a refusal.
const _refusedCodes = <String>{
  'memory_invalid',
  'memory_full',
  'memory_too_long',
};

/// The memory of one Bot.
///
/// The page owns nothing of the service: the caller keeps the API and this
/// page never closes it. One read per Bot at a time, and a newer Bot replaces
/// the older one — an answer that belongs to an older read may not write over
/// the newer result.
///
/// Changes are never repeated on their own. A change that failed keeps its
/// draft on the screen and closes the write entries until a read succeeds,
/// because a refused change, a conflicting change and a change whose outcome
/// is unknown need the user to look at the service again.
class BotMemoryPage extends StatefulWidget {
  const BotMemoryPage({super.key, required this.api, required this.botId});

  /// The service the parent owns. This page never closes it.
  final MiniMaxBotApi api;

  /// The Bot whose memory is read. The scope of every call is this id.
  final String botId;

  @override
  State<BotMemoryPage> createState() => _BotMemoryPageState();
}

class _BotMemoryPageState extends State<BotMemoryPage> {
  /// The entries the service sent, in its order.
  List<_MemoryEntry> _entries = const [];
  bool _loading = true;
  bool _failed = false;

  /// The scope the state below belongs to.
  String? _scope;
  /// The service the state below belongs to.
  MiniMaxBotApi? _api;
  /// The newest read. An older one may not write a result.
  int _read = 0;
  /// A read of the current context succeeded, so its entries may be written
  /// to. A context that has not been read yet has nothing to write.
  bool _ready = false;

  /// The context a flow belongs to. Another Bot or another service starts a
  /// new one, and every flow of the old one is stale: it may not write, read
  /// or clear the state of the new one.
  int _generation = 0;

  /// One change at a time: the dialog, its close transition, the request and
  /// the read that follows it.
  bool _changing = false;
  /// The newest change. An older one may not write a result.
  int _change = 0;
  /// The write entries are closed until a read succeeds again.
  bool _blocked = false;
  /// What went wrong with the last change.
  _ChangeFailure? _failure;
  /// The draft of the last failed change, kept for the user to read or copy.
  String? _draft;
  /// The last change was written but the list could not be read again.
  bool _refreshNeeded = false;

  static final _idPattern = RegExp(r'^[a-f0-9]{8}$');

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void didUpdateWidget(covariant BotMemoryPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.api != widget.api || oldWidget.botId != widget.botId) {
      // Another Bot or another service: its write entries are their own.
      // Every flow of the old context is stale from here on, so it may not
      // write, may not read again and may not clear the state of this one.
      setState(() {
        _generation++;
        _changing = false;
        _blocked = false;
        _failure = null;
        _draft = null;
        _refreshNeeded = false;
        _ready = false;
      });
      _load();
    }
  }

  /// Reads the current Bot's memory. A read that is already running for this
  /// Bot and service is not repeated, and a read never competes with a
  /// change that is on its way.
  Future<void> _load() async {
    // A change reads after itself; a read on its own would race it.
    if (_changing) return;
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
      final entries = _entriesOf(payload, scope);
      if (entries == null) {
        setState(() {
          _failed = true;
          _loading = false;
          _ready = false;
        });
        return;
      }
      setState(() {
        _entries = entries;
        _failed = false;
        _loading = false;
        _ready = true;
        // A read that worked closes the write entries again.
        _blocked = false;
        _refreshNeeded = false;
        // The prompt of an old failure is stale now; its draft stays so the
        // user can still copy it.
        _failure = null;
      });
    } catch (_) {
      if (!mounted || read != _read) return;
      setState(() {
        _failed = true;
        _loading = false;
        // Nothing was read, so nothing may be written.
        _ready = false;
      });
    }
  }

  /// The entries of a memory payload, or null when the payload does not
  /// belong to [scope] or does not have the shape the service promises.
  static List<_MemoryEntry>? _entriesOf(Map<String, Object?> payload, String scope) {
    if (payload['scope'] is! String || payload['scope'] != scope) return null;
    final entries = payload['entries'];
    if (entries is! List) return null;
    final rows = <_MemoryEntry>[];
    for (final entry in entries) {
      if (entry is! Map) return null;
      final text = entry['text'];
      if (text is! String) return null;
      final id = entry['id'];
      final revision = entry['revision'];
      rows.add(_MemoryEntry(
        text: text,
        id: id is String && _idPattern.hasMatch(id) ? id : null,
        revision: revision is String && revision.isNotEmpty ? revision : null,
      ));
    }
    return rows;
  }

  /// Whether the current context may be written to at all: it was read
  /// successfully, nothing is being read or changed right now, and the
  /// service did not leave the result of an earlier change unverified.
  bool get _contextWritable =>
      !_blocked &&
      !_refreshNeeded &&
      !_loading &&
      !_failed &&
      _ready &&
      _scope == widget.botId &&
      _api == widget.api;

  /// Whether a write may be started for the current Bot.
  bool get _writesAllowed => _contextWritable && !_changing;

  /// Opens the form for a new or an existing entry. The same form does both:
  /// the text is prefilled for an existing entry.
  Future<void> _openForm({_MemoryEntry? entry}) async {
    if (!_writesAllowed) return;
    final api = widget.api;
    final scope = widget.botId;
    final generation = _generation;
    final page = ModalRoute.of(context);
    setState(() => _changing = true);
    final text = TextEditingController(text: entry?.text ?? '');
    try {
      final route = DialogRoute<bool>(
        context: context,
        builder: (context) => StatefulBuilder(
          builder: (context, update) {
            final ready = text.text.trim().isNotEmpty;
            return AlertDialog(
              title: Text(entry == null ? context.l10n.botsAddMemory : context.l10n.botsEditMemory),
              content: TextField(
                controller: text,
                autofocus: true,
                minLines: 2,
                maxLines: 6,
                onChanged: (_) => update(() {}),
                decoration: InputDecoration(labelText: context.l10n.botsMemory),
              ),
              actions: [
                TextButton(onPressed: () => Navigator.pop(context, false), child: Text(context.l10n.commonCancel)),
                TextButton(
                  onPressed: ready ? () => Navigator.pop(context, true) : null,
                  child: Text(context.l10n.commonSave),
                ),
              ],
            );
          },
        ),
      );
      final accepted = await Navigator.of(context).push(route);
      await route.completed;
      if (accepted != true) return;
      // The page may be gone, or the Bot or the service may have changed
      // while the dialog was closing. A confirmed dialog is not a licence to
      // write for a page that is no longer there. The route tells a page that
      // left apart from one that is still there: an element can outlive the
      // navigator for a frame.
      if (!mounted) return;
      if (page == null || !page.isActive) return;
      if (widget.api != api || widget.botId != scope) return;
      // The context moved on while the form was open: the form belongs to a
      // generation that no longer exists, even if the Bot is the same again.
      if (generation != _generation) return;
      // The context may not be written to any more, for instance because a
      // read failed while the form was open.
      if (!_contextWritable) return;
      final body = text.text.trim();
      await _runChange(api, scope, generation, () {
        if (entry == null) {
          return api.remember(scope, {'text': body});
        }
        return api.remember(scope, {
          'text': body,
          'replaceId': entry.id,
          'expectedRevision': entry.revision,
        });
      }, draft: body);
    } finally {
      text.dispose();
      // Only the flow that owns the busy flag may clear it. A flow of an
      // older context may not unlock a change of the current one.
      if (mounted && generation == _generation) setState(() => _changing = false);
    }
  }

  /// Asks before removing an entry. The service's forget call takes no
  /// revision, so the confirmation says what it does and no more.
  Future<void> _confirmDelete(_MemoryEntry entry) async {
    if (!_writesAllowed) return;
    final api = widget.api;
    final scope = widget.botId;
    final generation = _generation;
    final id = entry.id;
    final page = ModalRoute.of(context);
    if (id == null) return;
    setState(() => _changing = true);
    try {
      final route = DialogRoute<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: Text(context.l10n.mbotMemoryDeleteEntry),
          content: Text(context.l10n.mbotMemoryDeleteConfirm),
          actions: [
            TextButton(onPressed: () => Navigator.pop(context, false), child: Text(context.l10n.commonCancel)),
            TextButton(onPressed: () => Navigator.pop(context, true), child: Text(context.l10n.commonDelete)),
          ],
        ),
      );
      final accepted = await Navigator.of(context).push(route);
      await route.completed;
      if (accepted != true) return;
      if (!mounted) return;
      if (page == null || !page.isActive) return;
      if (widget.api != api || widget.botId != scope) return;
      if (generation != _generation) return;
      if (!_contextWritable) return;
      await _runChange(api, scope, generation, () => api.forget(scope, id));
    } finally {
      if (mounted && generation == _generation) setState(() => _changing = false);
    }
  }

  /// Runs one change and then reads what the service actually holds.
  ///
  /// A change is never repeated on its own, and a failure never pretends that
  /// nothing was written: the outcome is unknown until a read says so.
  Future<void> _runChange(
    MiniMaxBotApi api,
    String scope,
    int generation,
    Future<Map<String, Object?>> Function() write, {
    String? draft,
  }) async {
    final change = ++_change;
    try {
      await write();
      if (!_isCurrent(change, generation)) return;
      await _readAfter(api, scope, change, generation);
    } on MiniMaxBotException catch (error) {
      if (!_isCurrent(change, generation)) return;
      _onFailure(_kindOf(error), draft);
    } catch (_) {
      if (!_isCurrent(change, generation)) return;
      // A timeout, a dropped connection or a broken answer may already have
      // written. The outcome is unknown, never "it did not write".
      _onFailure(_ChangeFailure.unknown, draft);
    }
  }

  /// Whether the result of a change still belongs to this page.
  bool _isCurrent(int change, int generation) =>
      mounted && change == _change && generation == _generation;

  /// Reads the entries again after a change the service accepted.
  Future<void> _readAfter(
    MiniMaxBotApi api,
    String scope,
    int change,
    int generation,
  ) async {
    try {
      final payload = await api.memory(scope);
      if (!_isCurrent(change, generation)) return;
      final entries = _entriesOf(payload, scope);
      if (entries == null) {
        setState(() {
          _refreshNeeded = true;
          _blocked = true;
        });
        return;
      }
      setState(() {
        _entries = entries;
        _failed = false;
        _loading = false;
        _ready = true;
        _blocked = false;
        _refreshNeeded = false;
        _failure = null;
        _draft = null;
      });
    } catch (_) {
      if (!_isCurrent(change, generation)) return;
      // The change may be stored; the list cannot show what is stored.
      setState(() {
        _refreshNeeded = true;
        _blocked = true;
      });
    }
  }

  void _onFailure(_ChangeFailure kind, String? draft) {
    setState(() {
      _failure = kind;
      _draft = draft;
      // The write entries stay closed until a read succeeds.
      _blocked = true;
    });
  }

  /// How a failure is read. The service's code decides; its message never
  /// does, because a message is written for a person.
  ///
  /// Only the codes that mean a refusal before anything was written read as
  /// a refusal. `memory_pending` is a durable transaction that failed after
  /// the service took it, and any code this client does not know may come
  /// from a service that accepted the change: both are unknown.
  static _ChangeFailure _kindOf(MiniMaxBotException error) {
    final code = error.code;
    if (code == 'memory_conflict') return _ChangeFailure.conflict;
    if (code.isNotEmpty) {
      return _refusedCodes.contains(code) ? _ChangeFailure.rejected : _ChangeFailure.unknown;
    }
    // No code to go by: anything but a plain success is unknown, and a
    // success that raised is not something this page can explain.
    return _ChangeFailure.unknown;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(context.l10n.botsMemory),
        actions: [
          IconButton(
            onPressed: _writesAllowed ? () => _openForm() : null,
            icon: const Icon(Icons.add),
            tooltip: context.l10n.botsAddMemory,
          ),
        ],
      ),
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
            onPressed: _loading || _changing ? null : _load,
            child: Text(context.l10n.botsTapRetry),
          ),
        ],
      );
    }
    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        if (_refreshNeeded || _failure != null) ...[
          if (_refreshNeeded) Text(context.l10n.mbotMemoryRefreshFailed),
          if (_failure != null) Text(_failureText(context)),
          const SizedBox(height: 8),
          TextButton(
            onPressed: _loading || _changing ? null : _load,
            child: Text(context.l10n.botsTapRetry),
          ),
          const SizedBox(height: 8),
        ],
        if (_draft != null) ...[
          SelectableText(_draft!),
          const SizedBox(height: 8),
          Text(context.l10n.mbotMemoryDraftKept),
          const SizedBox(height: 8),
        ],
        if (_entries.isEmpty)
          // A read that failed after a change does not fall back to an empty
          // memory: the list may hold entries the page cannot show yet.
          if (_refreshNeeded) const SizedBox(height: 0) else Text(context.l10n.botsNoMemory)
        else
          for (final entry in _entries) _entry(context, entry),
      ],
    );
  }

  Widget _entry(BuildContext context, _MemoryEntry entry) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SelectableText(entry.text),
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextButton(
                onPressed: _writesAllowed && entry.editable ? () => _openForm(entry: entry) : null,
                child: Text(context.l10n.commonEdit),
              ),
              TextButton(
                onPressed: _writesAllowed && entry.deletable ? () => _confirmDelete(entry) : null,
                child: Text(context.l10n.commonDelete),
              ),
            ],
          ),
        ],
      ),
    );
  }

  String _failureText(BuildContext context) => switch (_failure) {
        _ChangeFailure.conflict => context.l10n.mbotMemoryConflict,
        _ChangeFailure.rejected => context.l10n.mbotMemoryRejected,
        _ChangeFailure.unknown => context.l10n.mbotMemoryUnknown,
        null => '',
      };
}
