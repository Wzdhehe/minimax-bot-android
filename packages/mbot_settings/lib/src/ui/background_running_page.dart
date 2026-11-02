import 'dart:async';

import 'package:flutter/material.dart';

import '../platform/platform_services.dart';

/// 后台运行：只读检测 + 用户点击后跳转系统设置；不做保活黑科技。
class MbotBackgroundRunningPage extends StatefulWidget {
  const MbotBackgroundRunningPage({
    super.key,
    required this.platform,
  });

  final MbotPlatform platform;

  @override
  State<MbotBackgroundRunningPage> createState() => _MbotBackgroundRunningPageState();
}

class _MbotBackgroundRunningPageState extends State<MbotBackgroundRunningPage> {
  BackgroundSystemStatus? _status;
  bool _loading = true;
  AppLifecycleListener? _lifecycle;

  @override
  void initState() {
    super.initState();
    _lifecycle = AppLifecycleListener(onResume: () => unawaited(_reload()));
    unawaited(_reload());
  }

  @override
  void dispose() {
    _lifecycle?.dispose();
    super.dispose();
  }

  Future<void> _reload() async {
    final status = await widget.platform.readBackgroundStatus();
    if (!mounted) return;
    setState(() {
      _status = status;
      _loading = false;
    });
  }

  Future<void> _openNotifications() async {
    await widget.platform.openAppNotificationSettings();
    await _reload();
  }

  Future<void> _openBattery() async {
    // User-initiated only — never auto-prompt on open.
    await widget.platform.openBatteryOptimizationSettings();
    await _reload();
  }

  @override
  Widget build(BuildContext context) {
    final status = _status;
    return Scaffold(
      appBar: AppBar(title: const Text('后台运行')),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : SingleChildScrollView(
              key: const Key('mbot.settings.background.list'),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Padding(
                    padding: EdgeInsets.fromLTRB(16, 16, 16, 8),
                    child: Text(
                      key: Key('mbot.settings.background.disclaimer'),
                      '保活由你在系统设置里完成。本页只检测状态并提供跳转，不会偷偷拉活、不会多进程守护、不会自动反复弹出系统权限框。\n\n'
                      '关掉通知权限，或进程被系统杀掉之后，定时任务不会执行。'
                      '本应用不会承诺「被杀后仍能到点执行」。',
                    ),
                  ),
                  const Divider(),
                  ListTile(
                    key: const Key('mbot.settings.background.notifications'),
                    title: const Text('通知权限'),
                    subtitle: Text(
                      status == null
                          ? '未知'
                          : (status.notificationsEnabled
                              ? '已授予'
                              : '未授予 — 到点通知无法弹出，定时任务也不会在后台提醒你'),
                    ),
                    trailing: TextButton(
                      key: const Key('mbot.settings.background.notifications.open'),
                      onPressed: () => unawaited(_openNotifications()),
                      child: const Text('去系统设置'),
                    ),
                  ),
                  ListTile(
                    key: const Key('mbot.settings.background.battery'),
                    title: const Text('忽略电池优化'),
                    subtitle: Text(_batteryLabel(status?.batteryOptimizationIgnored)),
                    trailing: TextButton(
                      key: const Key('mbot.settings.background.battery.open'),
                      onPressed: () => unawaited(_openBattery()),
                      child: const Text('去系统设置'),
                    ),
                  ),
                  const Divider(),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
                    child: Text('建议步骤', style: Theme.of(context).textTheme.titleSmall),
                  ),
                  const ListTile(
                    leading: CircleAvatar(radius: 12, child: Text('1')),
                    title: Text('保持通知权限开启'),
                    subtitle: Text('否则到点无法提醒；关闭后定时任务在你划掉应用后也不会跑。'),
                  ),
                  const ListTile(
                    leading: CircleAvatar(radius: 12, child: Text('2')),
                    title: Text('在系统中忽略电池优化'),
                    subtitle: Text('由你手动确认系统对话框或设置页，应用不会自动反复申请。'),
                  ),
                  const ListTile(
                    leading: CircleAvatar(radius: 12, child: Text('3')),
                    title: Text('在最近任务中锁定本应用（可选）'),
                    subtitle: Text('减少被系统顺手清掉的概率；仍不保证进程永存。'),
                  ),
                  if (status?.vendor != null) ..._vendorSection(context, status!.vendor!),
                  if (status?.vendor == null) ..._genericVendorSection(context),
                ],
              ),
            ),
    );
  }

  List<Widget> _vendorSection(BuildContext context, VendorBackgroundInfo vendor) {
    return [
      const Divider(),
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
        child: Text(
          '${vendor.displayName} 参考路径',
          style: Theme.of(context).textTheme.titleSmall,
        ),
      ),
      for (var i = 0; i < vendor.steps.length; i++)
        ListTile(
          key: Key('mbot.settings.background.vendor.step.$i'),
          leading: Text('${i + 1}.'),
          title: Text(vendor.steps[i]),
        ),
      ListTile(
        key: const Key('mbot.settings.background.vendor.open'),
        title: const Text('打开应用系统详情页'),
        subtitle: const Text('厂商菜单位置因 ROM 而异；打不开专用页时退回应用详情。'),
        trailing: const Icon(Icons.open_in_new),
        onTap: () => unawaited(widget.platform.openAppDetailsSettings()),
      ),
    ];
  }

  List<Widget> _genericVendorSection(BuildContext context) {
    return [
      const Divider(),
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
        child: Text(
          key: const Key('mbot.settings.background.vendor.generic'),
          '常见厂商（小米 / vivo / 华为 / 荣耀 / OPPO / 三星）请到系统设置中搜索本应用，'
          '打开「自启动 / 后台耗电 / 电池」相关开关。路径因系统版本而异，本页只作提示，不会替你改系统设置。',
          style: Theme.of(context).textTheme.bodyMedium,
        ),
      ),
    ];
  }

  String _batteryLabel(bool? ignored) {
    if (ignored == null) return '无法检测（当前无原生实现）';
    if (ignored) return '已忽略';
    return '未忽略 — 系统可能在后台限制或结束进程，导致定时任务不执行';
  }
}
