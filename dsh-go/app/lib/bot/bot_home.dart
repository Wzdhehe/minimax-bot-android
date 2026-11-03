import 'dart:async';

import 'package:flutter/material.dart';

import '../l10n/l10n.dart';
import 'bot_controller.dart';
import 'bot_settings_hub.dart';
import 'bot_schedule_notifications.dart';
import 'bot_profile.dart';

class BotHome extends StatefulWidget {
  const BotHome({super.key, required this.controller});
  final BotController controller;
  @override
  State<BotHome> createState() => _BotHomeState();
}

class _BotHomeState extends State<BotHome> {
  final _search = TextEditingController();
  final _composer = TextEditingController();
  BotController get bot => widget.controller;
  StreamSubscription<Map<String, Object?>>? _notices;

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
    final name = TextEditingController();
    final role = TextEditingController();
    final members = <String>{};
    try {
      final route = DialogRoute<bool>(
        context: context,
        builder: (context) => StatefulBuilder(
          builder: (context, update) => AlertDialog(
            title: Text(group ? context.l10n.mobileBotGroup : context.l10n.mobileBotNew),
            content: SizedBox(
              width: 380,
              child: SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    TextField(
                      controller: name,
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
                onPressed: () {
                  if (name.text.trim().isNotEmpty && (!group || members.isNotEmpty)) Navigator.pop(context, true);
                },
                child: Text(context.l10n.mobileBotCreate),
              ),
            ],
          ),
        ),
      );
      final accepted = await Navigator.of(context).push(route);
      await route.completed;
      if (accepted != true || !mounted) return;
      if (group) {
        await bot.createGroup(name.text, members.toList());
      } else {
        await bot.createBot(name.text, role.text);
      }
      _composer.clear();
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(context.l10n.mobileBotLoadFailed)));
      }
    } finally {
      name.dispose();
      role.dispose();
    }
  }

  Widget _avatar(String name, {bool group = false}) => CircleAvatar(
    backgroundColor: group ? const Color(0xff6b4b8a) : const Color(0xff356c61),
    child: group
        ? const Icon(Icons.groups_outlined, color: Colors.white)
        : Text(name.isEmpty ? 'B' : name.characters.first, style: const TextStyle(color: Colors.white)),
  );

  Widget _list() {
    final query = _search.text.toLowerCase();
    final rows = [
      for (final b in bot.bots) {...b, 'chat': 'bot:${b['id']}'},
      for (final g in bot.groups) {...g, 'chat': 'group:${g['id']}'},
    ].where((row) => (row['name']?.toString() ?? '').toLowerCase().contains(query)).toList();
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              Text(context.l10n.mobileBotTitle, style: Theme.of(context).textTheme.headlineSmall),
              const Spacer(),
              IconButton(
                tooltip: context.l10n.mobileBotSettings,
                onPressed: () =>
                    Navigator.of(context)
                        .push(MaterialPageRoute<void>(builder: (_) => BotSettingsHub(botApi: bot.api))),
                icon: const Icon(Icons.settings_outlined),
              ),
              PopupMenuButton<bool>(
                icon: const Icon(Icons.add),
                onSelected: (group) => _create(group: group),
                itemBuilder: (context) => [
                  PopupMenuItem(value: false, child: Text(context.l10n.mobileBotNew)),
                  PopupMenuItem(value: true, child: Text(context.l10n.mobileBotGroup)),
                ],
              ),
            ],
          ),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: TextField(
            controller: _search,
            onChanged: (_) => setState(() {}),
            decoration: InputDecoration(
              hintText: context.l10n.mobileBotSearch,
              prefixIcon: const Icon(Icons.search),
              filled: true,
              border: OutlineInputBorder(borderRadius: BorderRadius.circular(28), borderSide: BorderSide.none),
            ),
          ),
        ),
        const SizedBox(height: 12),
        if (bot.loading) const LinearProgressIndicator(),
        if (rows.isEmpty && !bot.loading && bot.error == null)
          Padding(padding: const EdgeInsets.all(24), child: Text(context.l10n.mobileBotEmpty)),
        Expanded(
          child: ListView.builder(
            itemCount: rows.length,
            itemBuilder: (context, index) {
              final row = rows[index];
              final chat = row['chat'] as String;
              final name = row['name']?.toString() ?? '';
              final chats = bot.snapshot['chats'];
              final summary = chats is Map ? chats[chat] : null;
              return ListTile(
                selected: bot.selectedChat == chat,
                leading: _avatar(name, group: chat.startsWith('group:')),
                title: Text(name, maxLines: 1, overflow: TextOverflow.ellipsis),
                subtitle: Text(
                  summary is Map
                      ? (summary['preview'] ?? row['role'] ?? '').toString()
                      : (row['role'] ?? '').toString(),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
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
    );
  }

  Future<void> _send() async {
    final text = _composer.text;
    final chat = bot.selectedChat;
    final sent = await bot.send(text);
    if (!mounted) return;
    if (sent && _composer.text == text && bot.selectedChat == chat) _composer.clear();
    if (!sent) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(context.l10n.mobileBotSendFailed)));
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
              _avatar(name, group: isGroup),
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
                    color: mine ? const Color(0xff3b3b3e) : const Color(0xff232325),
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
                      if (message['status'] == 'streaming')
                        const Padding(padding: EdgeInsets.only(top: 8), child: LinearProgressIndicator()),
                    ],
                  ),
                ),
              );
            },
          ),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
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
                onPressed: bot.sending ? null : _send,
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
      backgroundColor: const Color(0xff131314),
      body: SafeArea(
        child: Column(
          children: [
            if (bot.error != null)
              MaterialBanner(
                content: Text(context.l10n.mobileBotLoadFailed),
                actions: [TextButton(onPressed: bot.start, child: Text(context.l10n.mobileBotRetry))],
              ),
            if (!bot.connected && !bot.loading && bot.error == null)
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Flexible(child: Text(context.l10n.mobileBotOffline)),
                  TextButton(onPressed: bot.start, child: Text(context.l10n.mobileBotRetry)),
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
                        Expanded(child: _chat(true)),
                      ],
                    );
                  }
                  return PopScope(
                    canPop: bot.selectedChat == null,
                    onPopInvokedWithResult: (didPop, result) {
                      if (!didPop) unawaited(bot.select(null));
                    },
                    child: bot.selectedChat == null ? _list() : _chat(false),
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
