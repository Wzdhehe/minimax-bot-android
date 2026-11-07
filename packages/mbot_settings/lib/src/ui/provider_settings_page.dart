// SPDX-License-Identifier: AGPL-3.0-only
import 'package:flutter/material.dart';
import 'loading_surface.dart';
import '../providers/provider_contract.dart';
import 'provider_editor_page.dart';
import 'provider_model_page.dart';
import 'provider_strings.dart';

class MbotProviderSettingsPage extends StatefulWidget {
  const MbotProviderSettingsPage(
      {super.key, required this.port, this.testProvider, this.usagePage});
  final MbotProviderPort port;
  final WidgetBuilder? usagePage;
  final Future<Map<String, Object?>> Function(String id, String model)?
      testProvider;
  @override
  State<MbotProviderSettingsPage> createState() =>
      _MbotProviderSettingsPageState();
}

class _MbotProviderSettingsPageState extends State<MbotProviderSettingsPage> {
  MbotProviderCatalog? _catalog;
  bool _busy = false, _editing = false, _failed = false;
  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _run(Future<MbotProviderCatalog> Function() operation) async {
    if (_busy || _editing) return;
    setState(() {
      _busy = true;
      _failed = false;
    });
    try {
      final result = await operation();
      if (mounted) setState(() => _catalog = result);
    } catch (_) {
      if (mounted) setState(() => _failed = true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _refresh() => _run(widget.port.load);

  Future<void> _edit([MbotProvider? provider]) async {
    if (_busy || _editing) return;
    setState(() => _editing = true);
    try {
      final result = await Navigator.of(context).push<MbotProviderCatalog>(
          MaterialPageRoute(
              builder: (_) => MbotProviderEditorPage(
                  port: widget.port, provider: provider)));
      if (mounted && result != null) {
        setState(() {
          _catalog = result;
          _failed = false;
        });
      }
    } finally {
      if (mounted) setState(() => _editing = false);
    }
  }

  Future<void> _toggle(
          MbotProvider provider, MbotModelSettings model, bool value) =>
      _run(() => widget.port.save(MbotProviderDraft(
              id: provider.id,
              name: provider.name,
              apiFormat: provider.apiFormat,
              baseUrl: provider.baseUrl,
              models: provider.models,
              enabled: provider.enabled,
              modelSettings: [
                for (final row in provider.modelSettings)
                  row.modelId == model.modelId ? row.withEnabled(value) : row,
              ])));

  Future<void> _editModel(
      MbotProvider provider, MbotModelSettings model) async {
    if (_busy || _editing) return;
    setState(() => _editing = true);
    try {
      final route = MaterialPageRoute<MbotModelSettings>(
          builder: (_) => MbotModelEditorPage(
              model: model,
              otherIds:
                  provider.models.where((id) => id != model.modelId).toSet()));
      final result = await Navigator.of(context).push(route);
      await route.completed;
      if (!mounted || result == null) return;
      setState(() => _editing = false);
      await _run(() => widget.port.save(MbotProviderDraft(
            id: provider.id,
            name: provider.name,
            apiFormat: provider.apiFormat,
            baseUrl: provider.baseUrl,
            enabled: provider.enabled,
            models: provider.models
                .map((id) => id == model.modelId ? result.modelId : id)
                .toList(),
            modelSettings: provider.modelSettings
                .map((row) => row.modelId == model.modelId ? result : row)
                .toList(),
          )));
    } finally {
      if (mounted) setState(() => _editing = false);
    }
  }

  Future<void> _probe(MbotProvider provider, String model) async {
    final test = widget.testProvider;
    if (test == null || _busy || _editing) return;
    setState(() => _busy = true);
    var key = 'pending';
    try {
      final result = await test(provider.id, model);
      key = result['ok'] == true
          ? 'testOk'
          : result['code'] == 'provider_unavailable'
              ? 'notReady'
              : 'testFailed';
    } catch (_) {/* Never display provider bodies or credentials. */}
    if (!mounted) return;
    setState(() => _busy = false);
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(providerText(context, key))));
  }

  Future<void> _delete(MbotProvider provider) async {
    if (_busy || _editing) return;
    final accepted = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
                title: Text(providerText(context, 'deleteProvider')),
                content: Text(provider.name),
                actions: [
                  TextButton(
                      onPressed: () => Navigator.pop(context, false),
                      child: Text(providerText(context, 'cancel'))),
                  TextButton(
                      onPressed: () => Navigator.pop(context, true),
                      child: Text(providerText(context, 'delete'))),
                ]));
    if (accepted == true && mounted) {
      await _run(() => widget.port.delete(provider.id));
    }
  }

  @override
  Widget build(BuildContext context) {
    String t(String key) => providerText(context, key);
    return Scaffold(
      appBar: AppBar(title: Text(t('title')), actions: [
        if (widget.usagePage != null)
          IconButton(
              tooltip: t('usage'),
              onPressed: () => Navigator.of(context)
                  .push(MaterialPageRoute<void>(builder: widget.usagePage!)),
              icon: const Icon(Icons.data_usage)),
        IconButton(
            key: const Key('mbot.provider.add'),
            tooltip: t('addProvider'),
            onPressed: _busy || _editing ? null : _edit,
            icon: const Icon(Icons.add)),
      ]),
      body: Align(
          alignment: Alignment.topCenter,
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 760),
            child: MbotLoadTransition(
                loading: _catalog == null && _busy,
                child: ListView(
                  padding: const EdgeInsets.all(20),
                  children: [
                    if (_busy) const LinearProgressIndicator(),
                    if (_failed)
                      ListTile(
                          title: Text(t('failed')),
                          trailing: TextButton(
                              onPressed: _busy ? null : _refresh,
                              child: Text(t('retry')))),
                    if (_catalog?.providers.isEmpty == true)
                      Padding(
                          padding: const EdgeInsets.all(12),
                          child: Text(t('empty'))),
                    for (final provider
                        in _catalog?.providers ?? <MbotProvider>[])
                      Card(
                        key: Key('mbot.provider.${provider.id}'),
                        margin: const EdgeInsets.only(bottom: 16),
                        child: Padding(
                            padding: const EdgeInsets.symmetric(vertical: 8),
                            child: Column(children: [
                              ListTile(
                                  title: Text(provider.name,
                                      style: const TextStyle(
                                          fontWeight: FontWeight.w600)),
                                  subtitle: Text(
                                      '${provider.apiFormat}\n${t(provider.hasApiKey ? 'keySaved' : 'keyMissing')}${provider.enabled ? '' : ' · ${t('disabled')}'}'),
                                  onTap: _busy || _editing
                                      ? null
                                      : () => _edit(provider),
                                  trailing: PopupMenuButton<int>(
                                      enabled: !_busy && !_editing,
                                      onSelected: (value) {
                                        if (value == -1) {
                                          _delete(provider);
                                        } else {
                                          _edit(provider);
                                        }
                                      },
                                      itemBuilder: (_) => [
                                            PopupMenuItem(
                                                value: 0,
                                                child: Text(t('editProvider'))),
                                            PopupMenuItem(
                                                value: -1,
                                                child: Text(t('delete'))),
                                          ])),
                              const Divider(indent: 16, endIndent: 16),
                              for (final model in provider.modelSettings)
                                ListTile(
                                  key: ValueKey(
                                      'mbot.model.row.${provider.id}.${model.modelId}'),
                                  onTap: _busy || _editing
                                      ? null
                                      : () => _editModel(provider, model),
                                  title: Text(model.modelId,
                                      maxLines: 2,
                                      overflow: TextOverflow.ellipsis),
                                  subtitle: _catalog?.defaultProviderId ==
                                              provider.id &&
                                          _catalog?.defaultModelId ==
                                              model.modelId
                                      ? Text(t('currentDefault'))
                                      : null,
                                  trailing: Row(
                                      mainAxisSize: MainAxisSize.min,
                                      children: [
                                        PopupMenuButton<String>(
                                            enabled: !_busy &&
                                                !_editing &&
                                                provider.enabled &&
                                                model.enabled,
                                            onSelected: (value) {
                                              if (value == 'default') {
                                                _run(() => widget.port
                                                    .selectDefault(provider.id,
                                                        model.modelId));
                                              } else {
                                                _probe(provider, model.modelId);
                                              }
                                            },
                                            itemBuilder: (_) => [
                                                  PopupMenuItem(
                                                      value: 'default',
                                                      child:
                                                          Text(t('default'))),
                                                  if (widget.testProvider !=
                                                      null)
                                                    PopupMenuItem(
                                                        value: 'test',
                                                        child: Text(t('test'))),
                                                ]),
                                        Switch.adaptive(
                                            key: ValueKey(
                                                'mbot.model.toggle.${provider.id}.${model.modelId}'),
                                            value: model.enabled,
                                            onChanged: _busy || _editing
                                                ? null
                                                : (value) => _toggle(
                                                    provider, model, value)),
                                      ]),
                                ),
                            ])),
                      ),
                  ],
                )),
          )),
    );
  }
}
