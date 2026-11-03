// SPDX-License-Identifier: AGPL-3.0-only
import 'dart:async';
import 'package:flutter/material.dart';
import '../plugins/plugin_port.dart';
import 'loading_surface.dart';

class MbotPluginSettingsPage extends StatefulWidget {
  const MbotPluginSettingsPage({super.key, required this.port});
  final MbotPluginPort port;
  @override
  State<MbotPluginSettingsPage> createState() => _MbotPluginSettingsPageState();
}

class _MbotPluginSettingsPageState extends State<MbotPluginSettingsPage> {
  final _search = TextEditingController();
  Timer? _debounce;
  MbotPluginPage? _page;
  bool _market = false, _loading = false, _failed = false, _mutating = false;
  bool _uncertain = false;
  int _epoch = 0;
  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _epoch++;
    _debounce?.cancel();
    _search.dispose();
    super.dispose();
  }

  Future<void> _load({String? cursor}) async {
    if (!widget.port.available) return;
    final epoch = ++_epoch;
    final market = _market;
    final keyword = _search.text.trim();
    setState(() {
      _loading = true;
      _failed = false;
    });
    try {
      final next = await widget.port
          .load(market: market, keyword: keyword, cursor: cursor);
      if (!mounted || epoch != _epoch) return;
      // Pages overlap occasionally; identity includes source, never displayName.
      final items = <String, MbotPlugin>{
        if (cursor != null)
          for (final item in _page?.items ?? <MbotPlugin>[])
            '${item.source}:${item.name}': item,
        for (final item in next.items) '${item.source}:${item.name}': item,
      };
      setState(() {
        _page = MbotPluginPage(List.unmodifiable(items.values),
            nextCursor: next.nextCursor == cursor ? null : next.nextCursor,
            hasMore: next.hasMore);
        _uncertain = false;
      });
    } catch (_) {
      if (mounted && epoch == _epoch) setState(() => _failed = true);
    } finally {
      if (mounted && epoch == _epoch) setState(() => _loading = false);
    }
  }

  void _query(String _) {
    _debounce?.cancel();
    _epoch++; // A late response for the previous query must not reappear.
    _debounce = Timer(const Duration(milliseconds: 300), () => _load());
  }

  Future<bool> _confirm(String title) async =>
      await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
                  title: Text(title),
                  content: const Text('插件可能包含工具、网络连接和执行逻辑。此操作以本机运行时的实际结果为准。'),
                  actions: [
                    TextButton(
                        onPressed: () => Navigator.pop(context, false),
                        child: const Text('取消')),
                    FilledButton(
                        onPressed: () => Navigator.pop(context, true),
                        child: const Text('确认'))
                  ])) ??
      false;

  Future<void> _act(Future<void> Function() operation) async {
    if (_mutating || _uncertain || !widget.port.canMutate) return;
    _debounce?.cancel();
    setState(() {
      _mutating = true;
      _uncertain = false;
    });
    try {
      await operation();
      if (mounted) {
        await _load();
        if (mounted && _failed) setState(() => _uncertain = true);
      }
    } catch (_) {
      // Never retry a mutation or turn an ambiguous timeout into local success.
      if (mounted) setState(() => _uncertain = true);
    } finally {
      if (mounted) setState(() => _mutating = false);
    }
  }

  Future<void> _github() async {
    _debounce?.cancel();
    final input = TextEditingController();
    final route = DialogRoute<bool>(
        context: context,
        builder: (context) => AlertDialog(
                title: const Text('从 GitHub 预览'),
                content: TextField(
                    controller: input,
                    autofocus: true,
                    decoration:
                        const InputDecoration(labelText: 'GitHub 仓库或插件链接')),
                actions: [
                  TextButton(
                      onPressed: () => Navigator.pop(context, false),
                      child: const Text('取消')),
                  FilledButton(
                      onPressed: () => Navigator.pop(context, true),
                      child: const Text('预览'))
                ]));
    final accepted = await Navigator.of(context).push(route);
    final url = input.text.trim();
    await route.completed;
    input.dispose();
    if (!mounted || accepted != true) return;
    setState(() => _loading = true);
    try {
      final preview = await widget.port.previewGithub(url);
      if (!mounted) return;
      setState(() => _loading = false);
      final install = await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
                  title: Text(preview.plugin.displayName),
                  content: SingleChildScrollView(
                      child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          mainAxisSize: MainAxisSize.min,
                          children: [
                        Text(preview.plugin.description),
                        const SizedBox(height: 16),
                        SelectableText(preview.repositoryUrl),
                        SelectableText(preview.commitSha),
                        if (preview.subPath != null) Text(preview.subPath!),
                        Text(
                            '技能 ${preview.plugin.skills} · MCP ${preview.plugin.mcpServers}'),
                        if (preview.hasStdioMcp)
                          const Text('包含需要启动本机进程的 MCP 工具。'),
                        const Text('预览固定版本不代表已验证 Android 兼容性。'),
                        if (!widget.port.canMutate)
                          const Text('Android 安装与执行尚未开放。'),
                      ])),
                  actions: [
                    TextButton(
                        onPressed: () => Navigator.pop(context, false),
                        child: const Text('关闭')),
                    FilledButton(
                        onPressed: widget.port.canMutate &&
                                !_uncertain &&
                                preview.canImport
                            ? () => Navigator.pop(context, true)
                            : null,
                        child: const Text('导入此版本'))
                  ]));
      if (mounted &&
          install == true &&
          await _confirm('导入 ${preview.plugin.displayName}？') &&
          mounted) {
        await _act(() => widget.port.importGithub(preview));
      }
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('无法预览，请检查 GitHub 链接和本机服务')));
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final enabled = widget.port.available && !_loading && !_mutating;
    return Scaffold(
      appBar: AppBar(title: const Text('插件'), actions: [
        IconButton(
            tooltip: '从 GitHub 预览',
            onPressed: enabled ? _github : null,
            icon: const Icon(Icons.add_link)),
        IconButton(
            tooltip: '刷新',
            onPressed: enabled ? () => _load() : null,
            icon: const Icon(Icons.refresh))
      ]),
      body: Align(
          alignment: Alignment.topCenter,
          child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 760),
              child: Column(children: [
                Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(children: [
                      SegmentedButton<bool>(
                          segments: const [
                            ButtonSegment(value: false, label: Text('已安装')),
                            ButtonSegment(value: true, label: Text('插件市场'))
                          ],
                          selected: {
                            _market
                          },
                          onSelectionChanged: _mutating
                              ? null
                              : (value) {
                                  _debounce?.cancel();
                                  setState(() {
                                    _market = value.single;
                                    _page = null;
                                  });
                                  _load();
                                }),
                      const SizedBox(height: 16),
                      TextField(
                          controller: _search,
                          onChanged: _query,
                          enabled: !_mutating,
                          decoration: const InputDecoration(
                              hintText: '搜索插件',
                              prefixIcon: Icon(Icons.search))),
                    ])),
                if (!widget.port.canMutate)
                  const Padding(
                      padding: EdgeInsets.symmetric(horizontal: 20),
                      child:
                          Text('上游插件尚无 Android 包目标，安装与执行暂未开放。可在连接运行时后浏览和预览。')),
                if (_mutating || (_loading && _page != null))
                  const LinearProgressIndicator(),
                if (_uncertain)
                  const Padding(
                      padding: EdgeInsets.all(16),
                      child: Text('操作结果未确认，请刷新核对后再决定是否重试。')),
                if (_failed)
                  const Padding(
                      padding: EdgeInsets.all(16),
                      child: Text('无法读取插件，请检查本机运行时和账号状态后刷新。')),
                Expanded(
                    child: !widget.port.available
                        ? const Center(child: Text('尚未连接 MiniMax Code 本机插件服务'))
                        : MbotLoadTransition(
                            loading: _loading && _page == null,
                            child: ListView.builder(
                                padding: const EdgeInsets.all(16),
                                itemCount: (_page?.items.length ?? 0) + 1,
                                itemBuilder: (context, index) {
                                  final items = _page?.items ?? <MbotPlugin>[];
                                  if (index == items.length) {
                                    if (_page?.hasMore == true) {
                                      return TextButton(
                                          onPressed: enabled &&
                                                  _page?.nextCursor != null
                                              ? () => _load(
                                                  cursor: _page!.nextCursor)
                                              : null,
                                          child: Text(_page?.nextCursor == null
                                              ? '还有更多；当前运行时未提供可用的翻页接口'
                                              : '加载更多'));
                                    }
                                    return items.isEmpty &&
                                            !_failed &&
                                            !_loading
                                        ? const Center(child: Text('暂无插件'))
                                        : const SizedBox.shrink();
                                  }
                                  final item = items[index];
                                  return Card(
                                      elevation: 0,
                                      child: ListTile(
                                        key: ValueKey(
                                            'mbot.plugin.${item.source}.${item.name}'),
                                        leading: const Icon(
                                            Icons.extension_outlined),
                                        title: Text(item.displayName),
                                        subtitle: Text(
                                            '${item.description}\n技能 ${item.skills} · MCP ${item.mcpServers} · Hooks ${item.hooks}'
                                            '${item.apps > 0 ? '\n含 Mini App，当前不适配其可视化界面' : ''}'
                                            '${item.managed ? '\n运行时管理的内置插件' : ''}'),
                                        trailing: item.installed
                                            ? PopupMenuButton<String>(
                                                enabled: enabled &&
                                                    widget.port.canMutate &&
                                                    !_uncertain &&
                                                    !item.managed,
                                                onSelected: (action) async {
                                                  if (await _confirm(
                                                          '${action == 'remove' ? '卸载' : item.enabled ? '停用' : '启用'} ${item.displayName}？') &&
                                                      mounted) {
                                                    await _act(() => action ==
                                                            'remove'
                                                        ? widget.port
                                                            .uninstall(item)
                                                        : widget.port
                                                            .setEnabled(item,
                                                                !item.enabled));
                                                  }
                                                },
                                                itemBuilder: (_) => [
                                                      PopupMenuItem(
                                                          value: 'toggle',
                                                          child: Text(
                                                              item.enabled
                                                                  ? '停用'
                                                                  : '启用')),
                                                      const PopupMenuItem(
                                                          value: 'remove',
                                                          child: Text('卸载'))
                                                    ])
                                            : TextButton(
                                                onPressed: enabled &&
                                                        !_uncertain &&
                                                        widget.port.canMutate
                                                    ? () async {
                                                        if (await _confirm(
                                                                '安装 ${item.displayName}？') &&
                                                            mounted) {
                                                          await _act(() =>
                                                              widget.port
                                                                  .install(
                                                                      item));
                                                        }
                                                      }
                                                    : null,
                                                child: const Text('安装')),
                                      ));
                                }))),
              ]))),
    );
  }
}
