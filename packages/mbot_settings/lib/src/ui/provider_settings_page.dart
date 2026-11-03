// SPDX-License-Identifier: AGPL-3.0-only
import 'package:flutter/material.dart';
import 'loading_surface.dart';
import '../providers/provider_contract.dart';

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
  bool _busy = false;
  bool _failed = false;
  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _run(Future<MbotProviderCatalog> Function() operation) async {
    if (_busy) return;
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

  Future<void> _probe(MbotProvider provider, String model) async {
    final test = widget.testProvider;
    if (test == null || _busy) return;
    setState(() => _busy = true);
    var message = '测试未完成，请检查本机模型服务';
    try {
      final result = await test(provider.id, model);
      message = result['ok'] == true
          ? '模型连接测试成功'
          : result['code'] == 'provider_unavailable'
              ? '本机模型服务尚未就绪'
              : '模型连接测试失败';
    } catch (_) {/* never show provider bodies or credentials */}
    if (!mounted) return;
    setState(() => _busy = false);
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> _edit([MbotProvider? provider]) async {
    final name = TextEditingController(text: provider?.name ?? '');
    final base = TextEditingController(
        text: provider?.baseUrl ?? 'https://api.minimax.io/v1');
    final models =
        TextEditingController(text: provider?.models.join('\n') ?? '');
    final key = TextEditingController();
    var format = provider?.apiFormat ?? 'openai-completions';
    var enabled = provider?.enabled ?? true;
    var clearKey = false;
    var invalid = false;
    try {
      final route = DialogRoute<bool>(
          context: context,
          builder: (context) => StatefulBuilder(
              builder: (context, update) => AlertDialog(
                    title: Text(provider == null ? '添加供应商' : '编辑供应商'),
                    content: SizedBox(
                        width: 480,
                        child: SingleChildScrollView(
                            child: Column(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                              if (invalid)
                                const Text('请填写名称、HTTPS 地址和至少一个模型 ID'),
                              TextField(
                                  key: const Key('mbot.provider.name'),
                                  controller: name,
                                  decoration: const InputDecoration(
                                      labelText: '供应商名称')),
                              DropdownButtonFormField<String>(
                                  initialValue: format,
                                  isExpanded: true,
                                  decoration: const InputDecoration(
                                      labelText: 'API 协议'),
                                  items: [
                                    for (final value in mbotProviderApiFormats)
                                      DropdownMenuItem(
                                          value: value, child: Text(value))
                                  ],
                                  onChanged: (value) =>
                                      update(() => format = value!)),
                              TextField(
                                  key: const Key('mbot.provider.baseUrl'),
                                  controller: base,
                                  autocorrect: false,
                                  keyboardType: TextInputType.url,
                                  decoration: const InputDecoration(
                                      labelText: 'Base URL')),
                              TextField(
                                  key: const Key('mbot.provider.key'),
                                  controller: key,
                                  obscureText: true,
                                  enableSuggestions: false,
                                  autocorrect: false,
                                  enabled: !clearKey,
                                  decoration: InputDecoration(
                                      labelText: 'API Key',
                                      helperText: provider?.hasApiKey == true
                                          ? '已保存；留空保留现有 Key'
                                          : '请输入供应商的 Key')),
                              TextField(
                                  key: const Key('mbot.provider.models'),
                                  controller: models,
                                  minLines: 2,
                                  maxLines: 6,
                                  decoration: const InputDecoration(
                                      labelText: '模型 ID，每行一个')),
                              SwitchListTile(
                                  title: const Text('启用供应商'),
                                  value: enabled,
                                  onChanged: (value) =>
                                      update(() => enabled = value)),
                              if (provider?.hasApiKey == true)
                                CheckboxListTile(
                                    title: const Text('删除已保存的 Key'),
                                    value: clearKey,
                                    onChanged: (value) =>
                                        update(() => clearKey = value == true)),
                            ]))),
                    actions: [
                      TextButton(
                          onPressed: () => Navigator.pop(context, false),
                          child: const Text('取消')),
                      FilledButton(
                          key: const Key('mbot.provider.save'),
                          onPressed: () {
                            final uri = Uri.tryParse(base.text.trim());
                            final ids = models.text
                                .split(RegExp(r'[\n,]'))
                                .map((s) => s.trim())
                                .where((s) => s.isNotEmpty)
                                .toList();
                            if (name.text.trim().isEmpty ||
                                ids.isEmpty ||
                                uri == null ||
                                uri.scheme != 'https' ||
                                uri.host.isEmpty ||
                                uri.userInfo.isNotEmpty ||
                                uri.hasQuery ||
                                uri.hasFragment) {
                              update(() => invalid = true);
                              return;
                            }
                            Navigator.pop(context, true);
                          },
                          child: const Text('保存'))
                    ],
                  )));
      final accepted = await Navigator.of(context).push(route);
      await route.completed;
      if (accepted != true || !mounted) return;
      final draft = MbotProviderDraft(
          id: provider?.id,
          name: name.text.trim(),
          apiFormat: format,
          baseUrl: base.text.trim(),
          models: models.text
              .split(RegExp(r'[\n,]'))
              .map((s) => s.trim())
              .where((s) => s.isNotEmpty)
              .toSet()
              .toList(),
          enabled: enabled);
      final newKey =
          clearKey || key.text.trim().isEmpty ? null : key.text.trim();
      key.clear();
      await _run(
          () => widget.port.save(draft, newKey: newKey, clearKey: clearKey));
    } finally {
      name.dispose();
      base.dispose();
      models.dispose();
      key.clear();
      key.dispose();
    }
  }

  Future<void> _delete(MbotProvider provider) async {
    final accepted = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
                title: const Text('删除供应商？'),
                content: Text(provider.name),
                actions: [
                  TextButton(
                      onPressed: () => Navigator.pop(context, false),
                      child: const Text('取消')),
                  TextButton(
                      onPressed: () => Navigator.pop(context, true),
                      child: const Text('删除'))
                ]));
    if (accepted == true && mounted) {
      await _run(() => widget.port.delete(provider.id));
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('账号与模型'), actions: [
          if (widget.usagePage != null)
            IconButton(
                tooltip: '账号用量',
                onPressed: () => Navigator.of(context)
                    .push(MaterialPageRoute<void>(builder: widget.usagePage!)),
                icon: const Icon(Icons.data_usage)),
          IconButton(
              key: const Key('mbot.provider.add'),
              tooltip: '添加供应商',
              onPressed: _busy ? null : _edit,
              icon: const Icon(Icons.add))
        ]),
        body: Align(
            alignment: Alignment.topCenter,
            child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 760),
                child: MbotLoadTransition(
                    loading: _catalog == null && _busy,
                    child: ListView.builder(
                      itemCount: (_catalog?.providers.length ?? 0) + 1,
                      itemBuilder: (context, index) {
                        if (index == 0) {
                          return Column(children: [
                            if (_busy) const LinearProgressIndicator(),
                            if (_failed)
                              ListTile(
                                  title: const Text('供应商设置失败，请检查输入或重试'),
                                  trailing: TextButton(
                                      onPressed: _busy ? null : _refresh,
                                      child: const Text('重试'))),
                            if (_catalog?.providers.isEmpty == true)
                              const Padding(
                                  padding: EdgeInsets.all(24),
                                  child: Text('添加供应商，配置 API 协议、地址、Key 和模型。')),
                          ]);
                        }
                        final provider = _catalog!.providers[index - 1];
                        return ListTile(
                          key: Key('mbot.provider.${provider.id}'),
                          title: Text(provider.name),
                          subtitle: Text(
                              '${provider.apiFormat}\n${provider.baseUrl}\n${provider.hasApiKey ? 'Key 已保存' : '未保存 Key'}${provider.enabled ? '' : ' · 已停用'}${_catalog?.defaultProviderId == provider.id ? '\n默认模型：${_catalog?.defaultModelId}' : ''}'),
                          onTap: _busy ? null : () => _edit(provider),
                          trailing: PopupMenuButton<int>(
                              enabled: !_busy,
                              onSelected: (value) {
                                if (value == -1) {
                                  _delete(provider);
                                } else if (value >= provider.models.length) {
                                  _probe(
                                      provider,
                                      provider.models[
                                          value - provider.models.length]);
                                } else {
                                  _run(() => widget.port.selectDefault(
                                      provider.id, provider.models[value]));
                                }
                              },
                              itemBuilder: (context) => [
                                    for (var i = 0;
                                        i < provider.models.length;
                                        i++)
                                      PopupMenuItem(
                                          value: i,
                                          enabled: provider.enabled,
                                          child: Text(
                                              '设为默认：${provider.models[i]}')),
                                    if (widget.testProvider != null)
                                      for (var i = 0;
                                          i < provider.models.length;
                                          i++)
                                        PopupMenuItem(
                                            value: provider.models.length + i,
                                            enabled: provider.enabled,
                                            child: Text(
                                                '测试：${provider.models[i]}')),
                                    const PopupMenuItem(
                                        value: -1, child: Text('删除供应商')),
                                  ]),
                        );
                      },
                    )))),
      );
}
