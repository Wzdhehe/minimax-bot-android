// SPDX-License-Identifier: AGPL-3.0-only
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/material.dart';

import '../l10n/l10n.dart';
import 'bot_avatar.dart';

/// The official Bot creation fields, with an actual catalog rather than
/// hard-coded model names or a UI-only model selection.
class BotCreatePage extends StatefulWidget {
  const BotCreatePage({super.key, required this.api});
  final MiniMaxBotApi api;
  @override
  State<BotCreatePage> createState() => _BotCreatePageState();
}

class _BotCreatePageState extends State<BotCreatePage> {
  final _name = TextEditingController(), _role = TextEditingController(), _instructions = TextEditingController();
  String _skin = 'bubble', _selection = '', _effort = '';
  bool _main = false, _helpers = true, _loading = true, _failed = false;
  List<Map<String, Object?>> _models = [];
  Map<String, Object?>? _default;
  @override
  void initState() {
    super.initState();
    _loadModels();
  }

  @override
  void dispose() {
    _name.dispose();
    _role.dispose();
    _instructions.dispose();
    super.dispose();
  }

  Future<void> _loadModels() async {
    if (!_loading) {
      setState(() {
        _loading = true;
        _failed = false;
      });
    }
    try {
      final result = await widget.api.models();
      final rows = result['models'];
      if (rows is! List) throw const FormatException('Model catalog missing');
      final models = rows
          .whereType<Map>()
          .map((row) => Map<String, Object?>.from(row))
          .where((row) => row['providerId'] is String && row['modelId'] is String && row['enabled'] != false)
          .toList();
      final def = result['default'];
      if (!mounted) return;
      setState(() {
        final seen = <String>{};
        _models = models.where((row) => seen.add(_key(row))).toList();
        _default = def is Map
            ? _models
                  .where((row) => row['providerId'] == def['providerId'] && row['modelId'] == def['modelId'])
                  .firstOrNull
            : null;
      });
    } catch (_) {
      if (mounted) setState(() => _failed = true);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  String _key(Map<String, Object?> row) => '${row['providerId']}/${row['modelId']}';
  Map<String, Object?>? get _entry =>
      _selection.isEmpty ? _default : _models.where((row) => _key(row) == _selection).firstOrNull;
  List<String> get _efforts {
    final thinking = _entry?['thinking'];
    final variants = _entry?['variants'];
    return <String>{
      if (thinking is Map && thinking['efforts'] is List) ...(thinking['efforts'] as List).whereType<String>(),
      if (variants is List && variants.contains('')) 'off',
    }.toList();
  }

  void _create() {
    if (!_validName) return;
    final entry = _entry;
    final selection = _selection.isEmpty || entry == null
        ? null
        : <String, Object?>{
            'providerId': entry['providerId'],
            'modelId': entry['modelId'],
            if (_effort == 'off') 'variant': '',
            if (_effort.isNotEmpty && _effort != 'off') ...{
              if (entry['variants'] is List && (entry['variants'] as List).contains('thinking')) 'variant': 'thinking',
              'thinking': {'effort': _effort},
            },
          };
    Navigator.pop(context, <String, Object?>{
      'name': _name.text.trim(),
      'role': _role.text.trim(),
      'instructions': _instructions.text.trim(),
      'skin': _skin,
      'isMain': _main,
      'autoHelper': _helpers,
      'model': ?selection,
      if (selection == null && _effort.isNotEmpty) 'effort': _effort,
    });
  }

  bool get _validName {
    final name = _name.text.trim();
    return name.isNotEmpty && name.length <= 24 && !name.contains(RegExp(r'[@\n\r]'));
  }

  @override
  Widget build(BuildContext context) {
    final t = context.l10n;
    final name = _name.text.trim();
    return Scaffold(
      appBar: AppBar(
        leadingWidth: 80,
        leading: TextButton(onPressed: () => Navigator.pop(context), child: Text(t.mobileBotCancel)),
        title: Text(t.mobileBotNew),
        actions: [TextButton(onPressed: _validName ? _create : null, child: Text(t.mobileBotCreate))],
      ),
      body: Align(
        alignment: Alignment.topCenter,
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 680),
          child: ListView(
            padding: const EdgeInsets.fromLTRB(24, 12, 24, 32),
            children: [
              Container(
                padding: const EdgeInsets.all(24),
                decoration: BoxDecoration(
                  color: Theme.of(context).colorScheme.surfaceContainerLow,
                  borderRadius: BorderRadius.circular(28),
                ),
                child: Column(
                  children: [
                    MbotAvatar(bot: {'skin': _skin, 'isMain': _main}, size: 100),
                    const SizedBox(height: 16),
                    Text(
                      name.isEmpty ? t.mobileBotNew : name,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.titleLarge,
                    ),
                    const SizedBox(height: 6),
                    Text(_main ? t.mobileBotMain : t.mobileBotCreateHint),
                  ],
                ),
              ),
              const SizedBox(height: 28),
              TextField(
                key: const Key('mbot.create.name'),
                controller: _name,
                maxLength: 24,
                onChanged: (_) => setState(() {}),
                decoration: InputDecoration(labelText: t.mobileBotName),
              ),
              const SizedBox(height: 20),
              TextField(
                controller: _role,
                maxLength: 60,
                decoration: InputDecoration(labelText: t.mobileBotRole),
              ),
              const SizedBox(height: 20),
              TextField(
                key: const Key('mbot.create.instructions'),
                controller: _instructions,
                minLines: 4,
                maxLines: 10,
                maxLength: 4000,
                decoration: InputDecoration(
                  labelText: t.mobileBotInstructions,
                  hintText: t.mobileBotInstructionsHint,
                  alignLabelWithHint: true,
                ),
              ),
              const SizedBox(height: 24),
              if (_loading) const LinearProgressIndicator(),
              if (_failed)
                TextButton(onPressed: _loading ? null : _loadModels, child: Text(t.mobileBotModelLoadFailed)),
              DropdownButtonFormField<String>(
                key: const Key('mbot.create.model'),
                initialValue: _selection,
                isExpanded: true,
                decoration: InputDecoration(labelText: t.mobileBotModel),
                items: [
                  DropdownMenuItem(value: '', child: Text(t.mobileBotModelDefault)),
                  for (final row in _models)
                    DropdownMenuItem(
                      value: _key(row),
                      child: Text(
                        (row['name'] ?? row['modelId']).toString(),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                ],
                onChanged: _loading
                    ? null
                    : (value) => setState(() {
                        _selection = value!;
                        _effort = '';
                      }),
              ),
              if (_efforts.isNotEmpty) ...[
                const SizedBox(height: 24),
                DropdownButtonFormField<String>(
                  key: ValueKey('mbot.create.effort.$_selection'),
                  initialValue: _effort,
                  isExpanded: true,
                  decoration: InputDecoration(labelText: t.mobileBotEffort),
                  items: [
                    DropdownMenuItem(value: '', child: Text(t.mobileBotEffortDefault)),
                    for (final value in _efforts) DropdownMenuItem(value: value, child: Text(value)),
                  ],
                  onChanged: (value) => setState(() => _effort = value!),
                ),
              ],
              const SizedBox(height: 24),
              SwitchListTile.adaptive(
                key: const Key('mbot.create.helpers'),
                contentPadding: EdgeInsets.zero,
                title: Text(t.mobileBotHelpers),
                subtitle: Text(t.mobileBotHelpersHint),
                value: _helpers,
                onChanged: (value) => setState(() => _helpers = value),
              ),
              const SizedBox(height: 12),
              SwitchListTile.adaptive(
                key: const Key('mbot.create.main'),
                contentPadding: EdgeInsets.zero,
                title: Text(t.mobileBotMain),
                subtitle: Text(t.mobileBotMainHint),
                value: _main,
                onChanged: (value) => setState(() => _main = value),
              ),
              const SizedBox(height: 28),
              Text(t.mobileBotAppearance, style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 16),
              Wrap(
                spacing: 10,
                runSpacing: 10,
                children: [
                  for (final skin in MbotAvatar.skinIds)
                    Semantics(
                      selected: _skin == skin,
                      button: true,
                      child: InkWell(
                        key: ValueKey('mbot.create.skin.$skin'),
                        borderRadius: BorderRadius.circular(18),
                        onTap: () => setState(() => _skin = skin),
                        child: Container(
                          width: 84,
                          padding: const EdgeInsets.all(8),
                          decoration: BoxDecoration(
                            borderRadius: BorderRadius.circular(18),
                            border: Border.all(
                              color: _skin == skin
                                  ? Theme.of(context).colorScheme.primary
                                  : Theme.of(context).colorScheme.outlineVariant,
                            ),
                          ),
                          child: Column(
                            children: [
                              MbotAvatar(bot: {'skin': skin}, size: 56, showMain: false),
                              const SizedBox(height: 8),
                              Text(
                                MbotAvatar.skinLabel(context, skin),
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: Theme.of(context).textTheme.labelSmall,
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
