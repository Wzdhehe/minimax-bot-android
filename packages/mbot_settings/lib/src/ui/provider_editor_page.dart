// SPDX-License-Identifier: AGPL-3.0-only
import 'package:flutter/material.dart';
import '../providers/provider_contract.dart';
import 'provider_model_page.dart';
import 'provider_strings.dart';

class MbotProviderEditorPage extends StatefulWidget {
  const MbotProviderEditorPage({super.key, required this.port, this.provider});
  final MbotProviderPort port;
  final MbotProvider? provider;
  @override
  State<MbotProviderEditorPage> createState() => _MbotProviderEditorPageState();
}

class _MbotProviderEditorPageState extends State<MbotProviderEditorPage> {
  late final _name = TextEditingController(text: widget.provider?.name ?? '');
  late final _base = TextEditingController(
      text: widget.provider?.baseUrl ?? 'https://api.minimax.io/v1');
  final _key = TextEditingController();
  late final _models =
      widget.provider?.modelSettings.toList() ?? <MbotModelSettings>[];
  late String _format = widget.provider?.apiFormat ?? 'openai-completions';
  late bool _enabled = widget.provider?.enabled ?? true;
  bool _clearKey = false,
      _busy = false,
      _editing = false,
      _invalid = false,
      _failed = false;

  @override
  void dispose() {
    _name.dispose();
    _base.dispose();
    _key.clear();
    _key.dispose();
    super.dispose();
  }

  Future<void> _model([int? index]) async {
    if (_busy || _editing) return;
    setState(() => _editing = true);
    try {
      final value = await Navigator.of(context).push<MbotModelSettings>(
          MaterialPageRoute(
              builder: (_) => MbotModelEditorPage(
                      model: index == null
                          ? MbotModelSettings(modelId: '')
                          : _models[index],
                      otherIds: {
                        for (var i = 0; i < _models.length; i++)
                          if (i != index) _models[i].modelId
                      })));
      if (!mounted || value == null) return;
      setState(() {
        if (index == null) {
          _models.add(value);
        } else {
          _models[index] = value;
        }
      });
    } finally {
      if (mounted) setState(() => _editing = false);
    }
  }

  Future<void> _save() async {
    if (_busy || _editing) return;
    final uri = Uri.tryParse(_base.text.trim());
    if (_name.text.trim().isEmpty ||
        _name.text.trim().length > 128 ||
        _models.isEmpty ||
        _models.length > 1000 ||
        uri == null ||
        uri.scheme != 'https' ||
        uri.host.isEmpty ||
        uri.userInfo.isNotEmpty ||
        uri.hasQuery ||
        uri.hasFragment) {
      setState(() => _invalid = true);
      return;
    }
    setState(() {
      _busy = true;
      _failed = false;
      _invalid = false;
    });
    try {
      final result = await widget.port.save(
          MbotProviderDraft(
            id: widget.provider?.id,
            name: _name.text.trim(),
            apiFormat: _format,
            baseUrl: _base.text.trim(),
            models: _models.map((model) => model.modelId).toList(),
            modelSettings: _models,
            enabled: _enabled,
          ),
          newKey:
              _clearKey || _key.text.trim().isEmpty ? null : _key.text.trim(),
          clearKey: _clearKey);
      _key.clear();
      if (mounted) Navigator.pop(context, result);
    } catch (_) {
      // Credentials and server bodies never become UI error strings.
      if (mounted) setState(() => _failed = true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    String t(String key) => providerText(context, key);
    return PopScope(
        canPop: !_busy,
        child: Scaffold(
          appBar: AppBar(
              title: Text(
                  t(widget.provider == null ? 'addProvider' : 'editProvider')),
              actions: [
                TextButton(
                    key: const Key('mbot.provider.save'),
                    onPressed: _busy || _editing ? null : _save,
                    child: Text(t('save'))),
              ]),
          body: Align(
              alignment: Alignment.topCenter,
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 720),
                child: ListView(padding: const EdgeInsets.all(24), children: [
                  if (_busy) const LinearProgressIndicator(),
                  if (_failed || _invalid)
                    Padding(
                        padding: const EdgeInsets.only(bottom: 20),
                        child: Text(t(_failed ? 'failed' : 'invalidProvider'),
                            style: TextStyle(
                                color: Theme.of(context).colorScheme.error))),
                  Text(t('account'),
                      style: Theme.of(context).textTheme.titleLarge),
                  const SizedBox(height: 8),
                  Text(t('accountHint')),
                  const SizedBox(height: 24),
                  TextField(
                      key: const Key('mbot.provider.name'),
                      controller: _name,
                      enabled: !_busy,
                      decoration: InputDecoration(labelText: t('name'))),
                  const SizedBox(height: 24),
                  DropdownButtonFormField<String>(
                      initialValue: _format,
                      isExpanded: true,
                      decoration: InputDecoration(labelText: t('format')),
                      items: [
                        for (final value in mbotProviderApiFormats)
                          DropdownMenuItem(value: value, child: Text(value))
                      ],
                      onChanged: _busy
                          ? null
                          : (value) => setState(() => _format = value!)),
                  const SizedBox(height: 24),
                  TextField(
                      key: const Key('mbot.provider.baseUrl'),
                      controller: _base,
                      enabled: !_busy,
                      autocorrect: false,
                      keyboardType: TextInputType.url,
                      decoration: const InputDecoration(labelText: 'Base URL')),
                  const SizedBox(height: 24),
                  TextField(
                      key: const Key('mbot.provider.key'),
                      controller: _key,
                      enabled: !_busy && !_clearKey,
                      obscureText: true,
                      enableSuggestions: false,
                      autocorrect: false,
                      decoration: InputDecoration(
                          labelText: 'API Key',
                          helperMaxLines: 2,
                          helperText: t(widget.provider?.hasApiKey == true
                              ? 'keepKey'
                              : 'keyHint'))),
                  if (widget.provider?.hasApiKey == true)
                    CheckboxListTile(
                        contentPadding: EdgeInsets.zero,
                        title: Text(t('clearKey')),
                        value: _clearKey,
                        onChanged: _busy
                            ? null
                            : (value) =>
                                setState(() => _clearKey = value == true)),
                  const SizedBox(height: 16),
                  SwitchListTile.adaptive(
                      contentPadding: EdgeInsets.zero,
                      title: Text(t('enabled')),
                      value: _enabled,
                      onChanged: _busy
                          ? null
                          : (value) => setState(() => _enabled = value)),
                  const SizedBox(height: 24),
                  Row(children: [
                    Expanded(
                        child: Text(t('models'),
                            style: Theme.of(context).textTheme.titleLarge)),
                    TextButton.icon(
                        key: const Key('mbot.provider.addModel'),
                        onPressed: _busy || _editing ? null : _model,
                        icon: const Icon(Icons.add),
                        label: Text(t('addModel')))
                  ]),
                  const SizedBox(height: 12),
                  for (var index = 0; index < _models.length; index++)
                    Card(
                        margin: const EdgeInsets.only(bottom: 12),
                        child: Padding(
                            padding: const EdgeInsets.symmetric(vertical: 8),
                            child: Column(children: [
                              ListTile(
                                  title: Text(_models[index].modelId,
                                      maxLines: 2,
                                      overflow: TextOverflow.ellipsis),
                                  onTap: _busy || _editing
                                      ? null
                                      : () => _model(index),
                                  trailing: Switch.adaptive(
                                      key: ValueKey(
                                          'mbot.editor.model.${_models[index].modelId}'),
                                      value: _models[index].enabled,
                                      onChanged: _busy
                                          ? null
                                          : (value) => setState(() =>
                                              _models[index] = _models[index]
                                                  .withEnabled(value)))),
                              Row(
                                  mainAxisAlignment: MainAxisAlignment.end,
                                  children: [
                                    IconButton(
                                        tooltip: t('deleteModel'),
                                        onPressed: _busy
                                            ? null
                                            : () => setState(
                                                () => _models.removeAt(index)),
                                        icon: const Icon(Icons.delete_outline)),
                                    TextButton(
                                        onPressed: _busy || _editing
                                            ? null
                                            : () => _model(index),
                                        child: Text(t('editModel'))),
                                    const SizedBox(width: 12),
                                  ]),
                            ]))),
                  const SizedBox(height: 24),
                ]),
              )),
        ));
  }
}
