import 'dart:async';

import 'package:flutter/material.dart';
import 'package:mbot_settings/mbot_settings.dart';

import '../l10n/l10n.dart';
import 'bot_controller.dart';
import 'bot_settings_hub.dart';
import 'bot_schedule_notifications.dart';
import 'bot_profile.dart';
import 'bot_avatar.dart';
import 'bot_create_page.dart';
import 'bot_attachments.dart';

import 'package:dsh_client/dsh_client.dart' show MiniMaxBotApi;

class BotHome extends StatefulWidget {
  const BotHome({super.key, required this.controller, this.pickAttachments = pickBotAttachments, this.onReconnect});
  final BotController controller;
  final Future<List<BotPickedAttachment>> Function() pickAttachments;
  final Future<void> Function()? onReconnect;
  @override
  State<BotHome> createState() => _BotHomeState();
}

class _BotHomeState extends State<BotHome> {
  final _search = TextEditingController();
  final _composer = TextEditingController();
  BotController get bot => widget.controller;
  StreamSubscription<Map<String, Object?>>? _notices;

  /// One creation flow at a time: the dialog, its close transition, the
  /// create request and the refresh that follows it. The entry is disabled
  /// while it runs, and the method refuses a second flow on its own.
  bool _creating = false;
  int _reconnecting = 0;
  bool _uploading = false;
  final _attachments = <String, List<Map<String, Object?>>>{};

  Future<void> _reconnect() async {
    setState(() => _reconnecting++);
    try {
      await (widget.onReconnect?.call() ?? bot.start());
    } finally {
      if (mounted) setState(() => _reconnecting--);
    }
  }

  Future<void> _settings() async {
    await Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => BotSettingsHub(botApi: bot.api)));
    if (mounted) await _reconnect();
  }

  @override
  void initState() {
    super.initState();
    final notices = BotScheduleNotifications();
    _notices = bot.events.listen((event) => unawaited(notices.handle(event)));
    unawaited(bot.start());
  }

  @override
  void dispose() {
    unawaited(_notices?.cancel());
    _search.dispose();
    _composer.dispose();
    super.dispose();
  }

  Future<void> _create({bool group = false}) async {
    // The entry is disabled while a flow runs; the guard here is what keeps
    // a second flow from starting even if the entry is reached anyway.
    if (_creating || _reconnecting > 0) return;
    setState(() => _creating = true);
    final name = TextEditingController();
    final role = TextEditingController();
    final members = <String>{};
    try {
      if (!group) {
        final route = MaterialPageRoute<Map<String, Object?>>(builder: (_) => BotCreatePage(api: bot.api));
        final draft = await Navigator.of(context).push(route);
        await route.completed;
        if (draft == null || !mounted) return;
        await bot.createBot(draft['name'] as String, draft['role'] as String, additional: draft);
        if (mounted) _composer.clear();
        return;
      }
      final route = DialogRoute<bool>(
        context: context,
        builder: (context) => StatefulBuilder(
          builder: (context, update) {
            // A Bot needs a name; a group needs a name and at least one
            // member. The button follows the fields instead of ignoring them.
            final ready = name.text.trim().isNotEmpty && (!group || members.isNotEmpty);
            return AlertDialog(
              title: Text(group ? context.l10n.mobileBotGroup : context.l10n.mobileBotNew),
              content: SizedBox(
                width: 380,
                child: SingleChildScrollView(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      TextField(
                        controller: name,
                        onChanged: (_) => update(() {}),
                        decoration: InputDecoration(labelText: context.l10n.mobileBotName),
                      ),
                      if (!group)
                        TextField(
                          controller: role,
                          maxLines: 3,
                          decoration: InputDecoration(labelText: context.l10n.mobileBotRole),
                        ),
                      if (group) ...[
                        Text(context.l10n.mobileBotMembers),
                        for (final member in bot.bots)
                          CheckboxListTile(
                            title: Text(member['name']?.toString() ?? ''),
                            value: members.contains(member['id']),
                            onChanged: (value) => update(() {
                              if (value == true) {
                                members.add(member['id'] as String);
                              } else {
                                members.remove(member['id']);
                              }
                            }),
                          ),
                      ],
                    ],
                  ),
                ),
              ),
              actions: [
                TextButton(onPressed: () => Navigator.pop(context, false), child: Text(context.l10n.mobileBotCancel)),
                TextButton(
                  onPressed: ready ? () => Navigator.pop(context, true) : null,
                  child: Text(context.l10n.mobileBotCreate),
                ),
              ],
            );
          },
        ),
      );
      final accepted = await Navigator.of(context).push(route);
      await route.completed;
      // Cancelling asks for nothing. A page that is already gone does not
      // start an operation either: the user confirmed a dialog, not a page
      // that has left while the dialog was still closing.
      if (accepted != true || !mounted) return;
      if (group) {
        await bot.createGroup(name.text, members.toList());
      } else {
        await bot.createBot(name.text, role.text);
      }
      if (!mounted) return;
      _composer.clear();
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(context.l10n.mobileBotLoadFailed)));
      }
    } finally {
      name.dispose();
      role.dispose();
      if (mounted) setState(() => _creating = false);
    }
  }

  List<Map<String, Object?>> _members(Map<String, Object?> group) {
    final ids = <Object?>{group['adminId'], if (group['memberIds'] is List) ...group['memberIds'] as List};
    return bot.bots.where((row) => ids.contains(row['id'])).toList();
  }

  Widget _avatar(Map<String, Object?> row, {bool group = false, double size = 52}) =>
      group ? MbotGroupAvatar(members: _members(row), size: size) : MbotAvatar(bot: row, size: size);

  Object? _summary(String chat) => bot.snapshot['chats'] is Map ? (bot.snapshot['chats'] as Map)[chat] : null;

  int _lastTime(Map<String, Object?> row) {
    final summary = _summary(row['chat'] as String);
    final value = summary is Map ? summary['lastTs'] ?? row['createdAt'] : row['createdAt'];
    return value is num ? value.toInt() : DateTime.tryParse(value?.toString() ?? '')?.millisecondsSinceEpoch ?? 0;
  }

  String _timeLabel(Map<String, Object?> row) {
    final value = _lastTime(row);
    if (value <= 0) return '';
    final date = DateTime.fromMillisecondsSinceEpoch(value), now = DateTime.now();
    final local = MaterialLocalizations.of(context);
    return date.year == now.year && date.month == now.month && date.day == now.day
        ? local.formatTimeOfDay(
            TimeOfDay.fromDateTime(date),
            alwaysUse24HourFormat: MediaQuery.alwaysUse24HourFormatOf(context),
          )
        : local.formatShortDate(date);
  }

  Widget _mainBots(List<Map<String, Object?>> mains) => Padding(
    padding: const EdgeInsets.fromLTRB(16, 22, 16, 28),
    child: Wrap(
      alignment: WrapAlignment.center,
      spacing: 24,
      runSpacing: 20,
      children: [
        for (final row in mains)
          SizedBox(
            width: mains.length == 1 ? 176 : 120,
            child: InkWell(
              key: ValueKey('mbot.main.${row['id']}'),
              borderRadius: BorderRadius.circular(28),
              onTap: () {
                _composer.clear();
                unawaited(bot.select('bot:${row['id']}'));
              },
              onLongPress: () => _profile(row['id'] as String),
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 10),
                child: Column(
                  children: [
                    _avatar(row, size: mains.length == 1 ? 112 : 84),
                    const SizedBox(height: 14),
                    Text(
                      row['name']?.toString() ?? '',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600),
                    ),
                    const SizedBox(height: 5),
                    Text(
                      context.l10n.mobileBotMain,
                      style: Theme.of(context).textTheme.labelSmall?.copyWith(color: const Color(0xffb78018)),
                    ),
                  ],
                ),
              ),
            ),
          ),
      ],
    ),
  );

  Widget _list() {
    final query = _search.text.trim().toLowerCase();
    final mains = query.isEmpty ? bot.bots.where((row) => row['isMain'] == true).toList() : <Map<String, Object?>>[];
    final rows =
        [
              for (final b in bot.bots) {...b, 'chat': 'bot:${b['id']}'},
              for (final g in bot.groups) {...g, 'chat': 'group:${g['id']}'},
            ]
            .where(
              (row) =>
                  (row['name']?.toString() ?? '').toLowerCase().contains(query) &&
                  !(query.isEmpty && row['isMain'] == true && (row['chat'] as String).startsWith('bot:')),
            )
            .toList()
          ..sort((a, b) => _lastTime(b).compareTo(_lastTime(a)));
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 12, 24, 8),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              IconButton.outlined(
                tooltip: context.l10n.mobileBotSettings,
                onPressed: _settings,
                icon: const Icon(Icons.settings_outlined),
              ),
              Text(
                context.l10n.mobileBotTitle,
                style: Theme.of(context).textTheme.titleLarge
                    ?.copyWith(fontWeight: FontWeight.w700, letterSpacing: -.6),
              ),
              DecoratedBox(
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  border: Border.all(color: Theme.of(context).colorScheme.outlineVariant),
                ),
                child: PopupMenuButton<bool>(
                  icon: const Icon(Icons.add),
                  enabled: !_creating && _reconnecting == 0,
                  onSelected: (group) => _create(group: group),
                  itemBuilder: (context) => [
                    PopupMenuItem(value: false, child: Text(context.l10n.mobileBotNew)),
                    PopupMenuItem(value: true, child: Text(context.l10n.mobileBotGroup)),
                  ],
                ),
              ),
            ],
          ),
        ),
        Expanded(
          child: MbotLoadTransition(
            loading: bot.loading && rows.isEmpty && mains.isEmpty,
            child: CustomScrollView(
              slivers: [
                if (mains.isNotEmpty) SliverToBoxAdapter(child: _mainBots(mains)),
                if (rows.isEmpty && mains.isEmpty && !bot.loading && bot.error == null)
                  SliverToBoxAdapter(
                    child: Padding(padding: const EdgeInsets.all(24), child: Text(context.l10n.mobileBotEmpty)),
                  ),
                SliverPadding(
                  padding: const EdgeInsets.fromLTRB(12, 8, 12, 20),
                  sliver: SliverList.builder(
                    itemCount: rows.length,
                    itemBuilder: (context, index) {
                      final row = rows[index];
                      final chat = row['chat'] as String;
                      final name = row['name']?.toString() ?? '';
                      final summary = _summary(chat);
                      return ListTile(
                        key: ValueKey('mbot.row.$chat'),
                        contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                        minLeadingWidth: 58,
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(22)),
                        selected: bot.selectedChat == chat,
                        leading: _avatar(row, group: chat.startsWith('group:')),
                        title: Text(
                          name,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w600),
                        ),
                        trailing: Text(
                          _timeLabel(row),
                          style: Theme.of(context).textTheme.labelSmall
                              ?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant),
                        ),
                        subtitle: Text(
                          summary is Map
                              ? (summary['preview'] ?? row['role'] ?? '').toString()
                              : (row['role'] ?? '').toString(),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 14,
                            height: 1.5,
                            color: Theme.of(context).colorScheme.onSurfaceVariant,
                          ),
                        ),
                        onTap: () {
                          _composer.clear();
                          unawaited(bot.select(chat));
                        },
                      );
                    },
                  ),
                ),
              ],
            ),
          ),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 8, 24, 18),
          child: TextField(
            controller: _search,
            onChanged: (_) => setState(() {}),
            decoration: InputDecoration(
              hintText: context.l10n.mobileBotSearch,
              prefixIcon: const Icon(Icons.search),
              contentPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 15),
              filled: true,
              border: OutlineInputBorder(borderRadius: BorderRadius.circular(32), borderSide: BorderSide.none),
            ),
          ),
        ),
      ],
    );
  }

  Future<void> _send() async {
    if (_reconnecting > 0) return;
    final text = _composer.text;
    final chat = bot.selectedChat;
    final attachments = List<Map<String, Object?>>.of(_attachments[chat] ?? const []);
    final sent = await bot.send(text, attachments: attachments.map((row) => row['id'] as String).toList());
    if (!mounted) return;
    if (sent) {
      setState(() => _attachments[chat!]?.removeWhere((row) => attachments.any((sent) => sent['id'] == row['id'])));
    }
    if (sent && _composer.text == text && bot.selectedChat == chat) _composer.clear();
    if (!sent) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(context.l10n.mobileBotSendFailed)));
  }

  Future<void> _attach() async {
    final chat = bot.selectedChat;
    if (chat == null || _uploading || bot.sending || _reconnecting > 0) return;
    setState(() => _uploading = true);
    try {
      final selected = await widget.pickAttachments();
      if (!mounted || bot.selectedChat != chat || selected.isEmpty) return;
      final previous = _attachments[chat] ?? const <Map<String, Object?>>[];
      final total =
          selected.fold<int>(0, (sum, item) => sum + item.length) +
          previous.fold<int>(0, (sum, item) => sum + (item['size'] as int));
      if (previous.length + selected.length > 10 ||
          total > MiniMaxBotApi.maxUploadBytes ||
          selected.any((item) => item.length < 1)) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(context.l10n.mobileBotUploadLimit)));
        return;
      }
      for (final file in selected) {
        if (!mounted) return;
        final receipt = await bot.api.upload(file.name, file.data, length: file.length);
        if (!mounted) return;
        setState(() {
          final draft = _attachments.putIfAbsent(chat, () => []);
          if (!draft.any((row) => row['id'] == receipt['id'])) draft.add(receipt);
        });
      }
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(context.l10n.mobileBotUploadFailed)));
      }
    } finally {
      if (mounted) setState(() => _uploading = false);
    }
  }

  Future<void> _profile(String id) async {
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => BotProfilePage(api: bot.api, botId: id),
      ),
    );
    if (mounted) await bot.refresh();
  }

  Widget _chat(bool expanded) {
    if (bot.selectedChat == null) return Center(child: Text(context.l10n.mobileBotChoose));
    final isGroup = bot.selectedChat!.startsWith('group:');
    final entries = isGroup ? bot.groups : bot.bots;
    final selected = entries.where((row) => bot.selectedChat!.endsWith(':${row['id']}')).firstOrNull;
    final name = selected?['name']?.toString() ?? '';
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.all(12),
          child: Row(
            children: [
              if (!expanded) IconButton(onPressed: () => bot.select(null), icon: const Icon(Icons.arrow_back)),
              _avatar(selected ?? const {}, group: isGroup, size: 40),
              const SizedBox(width: 12),
              Expanded(
                child: Text(name, style: Theme.of(context).textTheme.titleLarge, overflow: TextOverflow.ellipsis),
              ),
              IconButton(
                tooltip: context.l10n.mobileBotStop,
                onPressed: () async {
                  try {
                    await bot.cancel();
                  } catch (_) {
                    if (mounted) {
                      ScaffoldMessenger.of(context)
                          .showSnackBar(SnackBar(content: Text(context.l10n.mobileBotLoadFailed)));
                    }
                  }
                },
                icon: const Icon(Icons.stop_circle_outlined),
              ),
              if (!isGroup && selected != null)
                IconButton(
                  tooltip: context.l10n.mobileBotProfile,
                  onPressed: () => _profile(selected['id'] as String),
                  icon: const Icon(Icons.info_outline),
                ),
            ],
          ),
        ),
        Expanded(
          child: MbotLoadTransition(
            loading: bot.messagesLoading && bot.messages.isEmpty,
            child: ListView.builder(
              reverse: true,
              padding: const EdgeInsets.all(18),
              itemCount: bot.messages.length,
              itemBuilder: (context, index) {
                final message = bot.messages[bot.messages.length - index - 1];
                final mine = message['role'] == 'user';
                return Align(
                  alignment: mine ? Alignment.centerRight : Alignment.centerLeft,
                  child: Container(
                    constraints: const BoxConstraints(maxWidth: 680),
                    margin: const EdgeInsets.only(bottom: 12),
                    padding: const EdgeInsets.all(14),
                    decoration: BoxDecoration(
                      color: mine
                          ? Theme.of(context).colorScheme.surfaceContainerHighest
                          : Theme.of(context).colorScheme.surfaceContainerLow,
                      borderRadius: BorderRadius.circular(22),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        if (isGroup && !mine)
                          Text(
                            bot.bots.where((b) => b['id'] == message['botId']).firstOrNull?['name']?.toString() ?? '',
                            style: const TextStyle(color: Color(0xff81c7b7)),
                          ),
                        SelectableText(message['text']?.toString() ?? ''),
                        if (message['attachments'] is List)
                          for (final attachment in (message['attachments'] as List).whereType<Map>())
                            Padding(
                              padding: const EdgeInsets.only(top: 8),
                              child: Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  const Icon(Icons.attach_file, size: 16),
                                  const SizedBox(width: 6),
                                  Flexible(
                                    child: Text(
                                      attachment['name']?.toString() ?? context.l10n.mobileBotAttachment,
                                      overflow: TextOverflow.ellipsis,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                        if (message['status'] == 'streaming')
                          const Padding(padding: EdgeInsets.only(top: 8), child: LinearProgressIndicator()),
                      ],
                    ),
                  ),
                );
              },
            ),
          ),
        ),
        if (_uploading) const LinearProgressIndicator(),
        if ((_attachments[bot.selectedChat] ?? const []).isNotEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Wrap(
                spacing: 8,
                children: [
                  for (final attachment in _attachments[bot.selectedChat]!)
                    InputChip(
                      avatar: const Icon(Icons.attach_file, size: 16),
                      label: ConstrainedBox(
                        constraints: const BoxConstraints(maxWidth: 180),
                        child: Text(attachment['name'] as String, maxLines: 1, overflow: TextOverflow.ellipsis),
                      ),
                      onDeleted: _uploading || bot.sending
                          ? null
                          : () => setState(() => _attachments[bot.selectedChat]?.remove(attachment)),
                    ),
                ],
              ),
            ),
          ),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              IconButton(
                key: const Key('mbot.chat.attach'),
                tooltip: context.l10n.mobileBotAttachment,
                onPressed: _uploading || bot.sending || _reconnecting > 0 ? null : _attach,
                icon: const Icon(Icons.add_circle_outline),
              ),
              Expanded(
                child: TextField(
                  controller: _composer,
                  minLines: 1,
                  maxLines: 6,
                  decoration: InputDecoration(
                    hintText: context.l10n.mobileBotCompose,
                    filled: true,
                    border: OutlineInputBorder(borderRadius: BorderRadius.circular(28), borderSide: BorderSide.none),
                  ),
                ),
              ),
              IconButton(
                tooltip: context.l10n.mobileBotSend,
                onPressed: bot.sending || _uploading || _reconnecting > 0 ? null : _send,
                icon: bot.sending
                    ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.arrow_upward),
              ),
            ],
          ),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: bot,
    builder: (context, _) => Scaffold(
      backgroundColor: Theme.of(context).colorScheme.surface,
      body: SafeArea(
        child: Column(
          children: [
            if (_reconnecting > 0) const LinearProgressIndicator(),
            if (bot.error != null)
              MaterialBanner(
                content: Text(context.l10n.mobileBotLoadFailed),
                actions: [TextButton(onPressed: _reconnect, child: Text(context.l10n.mobileBotRetry))],
              ),
            if (!bot.connected && !bot.loading && bot.error == null)
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Flexible(child: Text(context.l10n.mobileBotOffline)),
                  TextButton(onPressed: _reconnect, child: Text(context.l10n.mobileBotRetry)),
                ],
              ),
            Expanded(
              child: LayoutBuilder(
                builder: (context, constraints) {
                  final expanded = constraints.maxWidth >= 840;
                  if (expanded) {
                    return Row(
                      children: [
                        SizedBox(width: 320, child: _list()),
                        const VerticalDivider(width: 1),
                        Expanded(
                          child: MbotPaneTransition(identity: bot.selectedChat ?? 'empty', child: _chat(true)),
                        ),
                      ],
                    );
                  }
                  return PopScope(
                    canPop: bot.selectedChat == null,
                    onPopInvokedWithResult: (didPop, result) {
                      if (!didPop) unawaited(bot.select(null));
                    },
                    child: MbotPaneTransition(
                      identity: bot.selectedChat ?? 'list',
                      child: bot.selectedChat == null ? _list() : _chat(false),
                    ),
                  );
                },
              ),
            ),
          ],
        ),
      ),
    ),
  );
}
