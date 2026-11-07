// SPDX-License-Identifier: AGPL-3.0-only
import 'package:flutter/material.dart';
import '../providers/provider_contract.dart';
import 'provider_strings.dart';

/// A single mcode model, edited independently of provider credentials.
class MbotModelEditorPage extends StatefulWidget {
  const MbotModelEditorPage(
      {super.key, required this.model, required this.otherIds});
  final MbotModelSettings model;
  final Set<String> otherIds;
  @override
  State<MbotModelEditorPage> createState() => _MbotModelEditorPageState();
}

class _MbotModelEditorPageState extends State<MbotModelEditorPage> {
  late final _id = TextEditingController(text: widget.model.modelId);
  late final _context =
      TextEditingController(text: widget.model.contextLimit?.toString() ?? '');
  late final _output =
      TextEditingController(text: widget.model.outputLimit?.toString() ?? '');
  final _effort = TextEditingController();
  late final _levels = widget.model.effortOptions.toSet();
  late final _inputs = widget.model.inputModalities.toSet();
  late bool _enabled = widget.model.enabled;
  bool _invalid = false;

  @override
  void dispose() {
    for (final controller in [_id, _context, _output, _effort]) {
      controller.dispose();
    }
    super.dispose();
  }

  void _addEffort(String text) {
    final levels = text
        .split(',')
        .map((level) => level.trim())
        .where((level) => level.isNotEmpty)
        .toList();
    if (levels.any((level) => level.length > 64) ||
        {..._levels, ...levels}.length > 32) {
      setState(() => _invalid = true);
      return;
    }
    setState(() => _levels.addAll(levels));
    _effort.clear();
  }

  int? _limit(String value) =>
      value.trim().isEmpty ? null : int.tryParse(value.trim());
  bool _validLimit(String value) =>
      value.trim().isEmpty ||
      (RegExp(r'^\d+$').hasMatch(value.trim()) &&
          (_limit(value) ?? 0) > 0 &&
          _limit(value)! <= 9007199254740991);

  void _save() {
    final id = _id.text.trim();
    if (!RegExp(r'^[A-Za-z0-9._:/-]{1,256}$').hasMatch(id) ||
        widget.otherIds.contains(id) ||
        !_validLimit(_context.text) ||
        !_validLimit(_output.text)) {
      setState(() => _invalid = true);
      return;
    }
    if (_effort.text.trim().isNotEmpty) {
      _addEffort(_effort.text);
      if (_effort.text.isNotEmpty) return;
    }
    Navigator.pop(
        context,
        MbotModelSettings(
          modelId: id,
          enabled: _enabled,
          contextLimit: _limit(_context.text),
          outputLimit: _limit(_output.text),
          effortOptions: _levels.toList(),
          inputModalities: ['text', ..._inputs.where((mode) => mode != 'text')],
          reasoning: _levels.isEmpty ? widget.model.reasoning : true,
        ));
  }

  @override
  Widget build(BuildContext context) {
    String t(String key) => providerText(context, key);
    return Scaffold(
      appBar: AppBar(title: Text(t('editModel')), actions: [
        TextButton(
            key: const Key('mbot.model.save'),
            onPressed: _save,
            child: Text(t('save'))),
      ]),
      body: Align(
          alignment: Alignment.topCenter,
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 680),
            child: ListView(padding: const EdgeInsets.all(24), children: [
              if (_invalid)
                Padding(
                    padding: const EdgeInsets.only(bottom: 20),
                    child: Text(t('invalidModel'),
                        style: TextStyle(
                            color: Theme.of(context).colorScheme.error))),
              TextField(
                  key: const Key('mbot.model.id'),
                  controller: _id,
                  autocorrect: false,
                  decoration: InputDecoration(labelText: t('modelId'))),
              const SizedBox(height: 24),
              TextField(
                  key: const Key('mbot.model.context'),
                  controller: _context,
                  keyboardType: TextInputType.number,
                  decoration: InputDecoration(
                      labelText: t('context'), helperText: t('defaultLimit'))),
              const SizedBox(height: 24),
              TextField(
                  key: const Key('mbot.model.output'),
                  controller: _output,
                  keyboardType: TextInputType.number,
                  decoration: InputDecoration(
                      labelText: t('output'), helperText: t('defaultLimit'))),
              const SizedBox(height: 32),
              Text(t('efforts'),
                  style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 8),
              Text(t('effortHint')),
              const SizedBox(height: 12),
              Wrap(spacing: 8, runSpacing: 8, children: [
                for (final level in {
                  ...['none', 'minimal', 'low', 'medium', 'high', 'xhigh'],
                  ..._levels
                })
                  FilterChip(
                      label: Text(level),
                      selected: _levels.contains(level),
                      onSelected: (value) => setState(() {
                            if (value) {
                              _levels.add(level);
                            } else {
                              _levels.remove(level);
                            }
                          })),
              ]),
              const SizedBox(height: 16),
              TextField(
                  key: const Key('mbot.model.effort'),
                  controller: _effort,
                  onSubmitted: _addEffort,
                  decoration: InputDecoration(
                      labelText: t('effortInput'),
                      suffixIcon: IconButton(
                          tooltip: t('addModel'),
                          onPressed: () => _addEffort(_effort.text),
                          icon: const Icon(Icons.add)))),
              const SizedBox(height: 32),
              Text(t('attachments'),
                  style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 8),
              Text(t('attachmentHint')),
              const SizedBox(height: 12),
              Wrap(spacing: 8, runSpacing: 8, children: [
                for (final mode in ['image', 'pdf', 'video', 'audio'])
                  FilterChip(
                      key: ValueKey('mbot.model.input.$mode'),
                      label: Text(t(mode)),
                      selected: _inputs.contains(mode),
                      onSelected: (value) => setState(() {
                            if (value) {
                              _inputs.add(mode);
                            } else {
                              _inputs.remove(mode);
                            }
                          })),
              ]),
              const SizedBox(height: 24),
              SwitchListTile.adaptive(
                  key: const Key('mbot.model.enabled'),
                  contentPadding: EdgeInsets.zero,
                  title: Text(t('modelEnabled')),
                  value: _enabled,
                  onChanged: (value) => setState(() => _enabled = value)),
            ]),
          )),
    );
  }
}
