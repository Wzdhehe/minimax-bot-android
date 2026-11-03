// SPDX-License-Identifier: AGPL-3.0-only
import 'package:flutter/material.dart';
import 'loading_surface.dart';
import '../usage/usage_quota.dart';

class MbotUsageQuotaPage extends StatefulWidget {
  const MbotUsageQuotaPage({super.key, required this.port});
  final MbotUsageQuotaPort port;
  @override
  State<MbotUsageQuotaPage> createState() => _MbotUsageQuotaPageState();
}

class _MbotUsageQuotaPageState extends State<MbotUsageQuotaPage> {
  MbotUsageQuota? _value;
  bool _loading = false;
  bool _failed = false;
  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load({bool force = false}) async {
    if (_loading) return;
    setState(() {
      _loading = true;
      _failed = false;
    });
    try {
      final value = await widget.port.load(forceRefresh: force);
      if (mounted) setState(() => _value = value);
    } catch (_) {
      if (mounted) setState(() => _failed = true);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Widget _window(String label, MbotQuotaWindow? window) => Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(label, style: Theme.of(context).textTheme.titleMedium),
        Text(window?.unlimited == true
            ? '不限额'
            : window?.usedPercent == null
                ? '暂无数据'
                : '已用 ${window!.usedPercent!.toStringAsFixed(0)}%'),
        if (window?.usedPercent != null && window?.unlimited != true)
          LinearProgressIndicator(value: window!.usedPercent! / 100),
        if (window?.resetAtMs != null)
          Text(
              '重置时间：${DateTime.fromMillisecondsSinceEpoch(window!.resetAtMs!).toLocal()}'),
      ]));
  @override
  Widget build(BuildContext context) {
    final value = _value;
    return Scaffold(
      appBar: AppBar(title: const Text('账号用量'), actions: [
        IconButton(
            tooltip: '刷新',
            onPressed: _loading ? null : () => _load(force: true),
            icon: const Icon(Icons.refresh))
      ]),
      body: Align(
          alignment: Alignment.topCenter,
          child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 680),
              child: MbotLoadTransition(
                  loading: _value == null && _loading,
                  child: ListView(padding: const EdgeInsets.all(24), children: [
                    if (_loading) const LinearProgressIndicator(),
                    if (_failed) const Text('无法读取账号用量，请检查本机服务后刷新'),
                    if (!_failed && value?.supported == false)
                      const Text('当前供应商不支持此用量查询'),
                    if (!_failed &&
                        value?.supported == true &&
                        value?.signedIn == false)
                      const Text('未登录 MiniMax 账号；API Key 不作为账号登录态'),
                    if (!_failed &&
                        value?.supported == true &&
                        value?.signedIn == true) ...[
                      if (value?.planName != null)
                        Text(value!.planName!,
                            style: Theme.of(context).textTheme.headlineSmall),
                      if (value?.creditBalance != null)
                        Text('积分余额：${value!.creditBalance}'),
                      _window('5 小时限额', value?.fiveHour),
                      _window('周限额', value?.weekly),
                    ],
                  ])))),
    );
  }
}
