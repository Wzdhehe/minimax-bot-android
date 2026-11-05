// SPDX-License-Identifier: AGPL-3.0-only
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/material.dart';
import 'package:mbot_settings/mbot_settings.dart';

import '../l10n/l10n.dart';
import 'bot_memory_page.dart';
import 'bot_schedules.dart';
import 'bot_notification_prefs.dart';

class BotProfilePage extends StatefulWidget {
  const BotProfilePage({super.key, required this.api, required this.botId, this.store});
  final MiniMaxBotApi api;
  final String botId;
  final PrefsStore? store;
  @override
  State<BotProfilePage> createState() => _BotProfilePageState();
}

class _BotProfilePageState extends State<BotProfilePage> {
  Map<String, Object?>? _bot;
  PrefsStore? _store;
  int _enabledSchedules = 0;
  bool _notifications = true;
  bool _busy = false;
  bool _failed = false;

  /// One edit flow at a time: the dialog, its close transition, the update
  /// request and the reload that follows it. It is its own flag because the
  /// reload resets [busy], and the flow has to stay closed across that reset.
  bool _editing = false;
  @override
  void initState() {
    super.initState();
    _reload();
  }

  Future<void> _reload() async {
    setState(() {
      _busy = true;
      _failed = false;
    });
    try {
      final state = await widget.api.state();
      final bots = state['bots'] as List;
      final bot = bots.cast<Map>().firstWhere((b) => b['id'] == widget.botId);
      final schedules = state['schedules'] is List ? state['schedules'] as List : [];
      final store = _store ?? widget.store ?? await SharedPreferencesPrefsStore.create();
      final enabled = await store.getBool(botNotificationPrefKey(widget.botId)) ?? true;
      if (!mounted) return;
      setState(() {
        _bot = Map<String, Object?>.from(bot);
        _store = store;
        _notifications = enabled;
        _enabledSchedules = schedules
            .whereType<Map>()
            .where((s) => s['botId'] == widget.botId && s['enabled'] != false)
            .length;
      });
    } catch (_) {
      if (mounted) setState(() => _failed = true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _edit() async {
    // The entry is closed while a flow runs; the guard here is what keeps a
    // second flow from starting even if the entry is reached anyway.
    if (_editing) return;
    setState(() => _editing = true);
    final name = TextEditingController(text: _bot?['name']?.toString() ?? '');
    final role = TextEditingController(text: _bot?['role']?.toString() ?? '');
    try {
      final route = DialogRoute<bool>(
        context: context,
        builder: (context) => StatefulBuilder(
          builder: (context, update) {
            // A name that says something is the only requirement; the role
            // may stay empty.
            final ready = name.text.trim().isNotEmpty;
            return AlertDialog(
              title: Text(context.l10n.mobileBotEdit),
              content: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  TextField(
                    controller: name,
                    onChanged: (_) => update(() {}),
                    decoration: InputDecoration(labelText: context.l10n.mobileBotName),
                  ),
                  TextField(
                    controller: role,
                    minLines: 2,
                    maxLines: 5,
                    decoration: InputDecoration(labelText: context.l10n.mobileBotRole),
                  ),
                ],
              ),
              actions: [
                TextButton(onPressed: () => Navigator.pop(context, false), child: Text(context.l10n.mobileBotCancel)),
                TextButton(
                  onPressed: ready ? () => Navigator.pop(context, true) : null,
                  child: Text(context.l10n.mobileBotSave),
                ),
              ],
            );
          },
        ),
      );
      final accepted = await Navigator.of(context).push(route);
      await route.completed;
      if (accepted != true || !mounted) return;
      setState(() => _busy = true);
      await widget.api.updateBot(widget.botId, {'name': name.text.trim(), 'role': role.text.trim()});
      if (mounted) await _reload();
    } catch (_) {
      if (mounted) setState(() => _failed = true);
    } finally {
      name.dispose();
      role.dispose();
      if (mounted) {
        setState(() {
          _busy = false;
          _editing = false;
        });
      }
    }
  }

  Future<void> _notify(bool enabled) async {
    final store = _store;
    if (store == null) return;
    setState(() => _busy = true);
    try {
      await store.setBool(botNotificationPrefKey(widget.botId), enabled);
      if (mounted) setState(() => _notifications = enabled);
    } catch (_) {
      if (mounted) setState(() => _failed = true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final name = _bot?['name']?.toString() ?? '';
    return Scaffold(
      appBar: AppBar(title: Text(context.l10n.mobileBotProfile)),
      body: Align(
        alignment: Alignment.topCenter,
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 680),
          child: MbotLoadTransition(
            loading: _bot == null && _busy,
            child: ListView(
              padding: const EdgeInsets.all(24),
              children: [
                if (_busy) const LinearProgressIndicator(),
                if (_failed) TextButton(onPressed: _reload, child: Text(context.l10n.mobileBotRetry)),
                Center(
                  child: CircleAvatar(
                    radius: 52,
                    child: Text(
                      name.isEmpty ? 'B' : name.characters.first,
                      style: Theme.of(context).textTheme.headlineLarge,
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                Center(child: Text(name, style: Theme.of(context).textTheme.headlineMedium)),
                Center(
                  child: TextButton(
                    onPressed: _busy || _editing || _bot == null ? null : _edit,
                    child: Text(context.l10n.mobileBotEdit),
                  ),
                ),
                const SizedBox(height: 24),
                Card(
                  child: ListTile(
                    key: const Key('mbot.bot.automations'),
                    leading: const Icon(Icons.schedule),
                    title: Text(context.l10n.mobileBotScheduledTasks),
                    subtitle: Text(context.l10n.mobileBotScheduleSelfHint),
                    trailing: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(context.l10n.mobileBotScheduleEnabledCount(_enabledSchedules)),
                        const Icon(Icons.chevron_right),
                      ],
                    ),
                    onTap: _bot == null
                        ? null
                        : () async {
                            await Navigator.of(context).push(
                              MaterialPageRoute<void>(
                                builder: (_) =>
                                    MbotScheduleSettingsPage(port: BotSchedulesPort(widget.api, widget.botId)),
                              ),
                            );
                            if (mounted) await _reload();
                          },
                  ),
                ),
                const SizedBox(height: 24),
                Card(
                  child: ListTile(
                    key: const Key('mbot.bot.memory'),
                    leading: const Icon(Icons.psychology_outlined),
                    title: Text(context.l10n.botsMemory),
                    trailing: const Icon(Icons.chevron_right),
                    onTap: _bot == null
                        ? null
                        : () async {
                            // The page borrows the service; the parent keeps it.
                            await Navigator.of(context).push(
                              MaterialPageRoute<void>(
                                builder: (_) => BotMemoryPage(api: widget.api, botId: widget.botId),
                              ),
                            );
                            if (mounted) await _reload();
                          },
                  ),
                ),
                const SizedBox(height: 24),
                Card(
                  child: SwitchListTile(
                    key: const Key('mbot.bot.notifications'),
                    title: Text(context.l10n.mobileBotNotifications),
                    subtitle: Text(context.l10n.mobileBotNotifyHint),
                    value: _notifications,
                    onChanged: _busy || _store == null ? null : _notify,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
