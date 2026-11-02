import 'dart:async';

import 'package:flutter/material.dart';

import '../ia/settings_groups.dart';
import '../platform/platform_services.dart';
import '../prefs/notification_prefs.dart';

/// 通知：总开关、分类开关、每类跳转系统渠道；总关时分类置灰。
class MbotNotificationSettingsPage extends StatefulWidget {
  const MbotNotificationSettingsPage({
    super.key,
    required this.platform,
    required this.prefs,
  });

  final MbotPlatform platform;
  final MbotNotificationPrefsController prefs;

  @override
  State<MbotNotificationSettingsPage> createState() => _MbotNotificationSettingsPageState();
}

class _MbotNotificationSettingsPageState extends State<MbotNotificationSettingsPage> {
  late MbotNotificationPrefs _value;
  bool? _systemEnabled;

  @override
  void initState() {
    super.initState();
    _value = widget.prefs.value;
    widget.prefs.addListener(_onPrefs);
    unawaited(_refreshSystem());
    unawaited(widget.platform.ensureNotificationChannels(NotificationChannelSpec.mbotDefaults()));
  }

  @override
  void dispose() {
    widget.prefs.removeListener(_onPrefs);
    super.dispose();
  }

  void _onPrefs(MbotNotificationPrefs next) {
    if (!mounted) return;
    setState(() => _value = next);
  }

  Future<void> _refreshSystem() async {
    final enabled = await widget.platform.areNotificationsEnabled();
    if (!mounted) return;
    setState(() => _systemEnabled = enabled);
  }

  Future<void> _openChannel(MbotNotifyCategory category) async {
    final id = MbotNotificationChannels.forCategory(category);
    final opened = await widget.platform.openChannelSettings(id);
    if (!opened && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('无法打开系统通知渠道设置')),
      );
    }
  }

  Future<void> _openAppSettings() async {
    final opened = await widget.platform.openAppNotificationSettings();
    if (!opened && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('无法打开系统通知设置')),
      );
    }
    await _refreshSystem();
  }

  @override
  Widget build(BuildContext context) {
    final masterOn = _value.enabled;
    return Scaffold(
      appBar: AppBar(title: const Text('通知')),
      body: SingleChildScrollView(
        key: const Key('mbot.settings.notify.list'),
        child: Column(
          children: [
          if (_systemEnabled == false)
            ListTile(
              key: const Key('mbot.settings.notify.systemBlocked'),
              leading: const Icon(Icons.warning_amber_outlined),
              title: const Text('系统通知权限未开启'),
              subtitle: const Text(
                '关掉通知后，定时任务到点也不会弹出提醒；进程被系统杀掉后定时任务不会执行。',
              ),
              trailing: TextButton(
                key: const Key('mbot.settings.notify.openSystem'),
                onPressed: () => unawaited(_openAppSettings()),
                child: const Text('去系统设置'),
              ),
              isThreeLine: true,
            ),
          SwitchListTile(
            key: const Key('mbot.settings.notify.master'),
            title: const Text('允许通知'),
            subtitle: const Text('关闭后所有分类通知都不会弹出'),
            value: masterOn,
            onChanged: (v) => unawaited(widget.prefs.setEnabled(v)),
          ),
          const Divider(),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
            child: Text('分类', style: Theme.of(context).textTheme.titleSmall),
          ),
          for (final category in MbotNotifyCategory.values) ...[
            SwitchListTile(
              key: Key('mbot.settings.notify.${category.name}'),
              title: Text(category.title),
              subtitle: Text(category.subtitle),
              value: _value.categoryEnabled(category),
              onChanged: masterOn
                  ? (v) => unawaited(widget.prefs.setCategory(category, v))
                  : null,
            ),
            ListTile(
              key: Key('mbot.settings.notify.channel.${category.name}'),
              enabled: masterOn,
              dense: true,
              title: Text('系统渠道：${MbotNotificationChannels.forCategory(category)}'),
              trailing: const Icon(Icons.open_in_new, size: 18),
              onTap: masterOn ? () => unawaited(_openChannel(category)) : null,
            ),
          ],
          const Divider(),
          const ListTile(
            key: Key('mbot.settings.notify.disclaimer'),
            leading: Icon(Icons.info_outline),
            title: Text('关于定时任务与通知'),
            subtitle: Text(
              '定时任务只在本机 Bot/runtime 进程仍存活时才会到点执行并通知。'
              '关掉通知权限、划掉任务或被系统杀掉进程后，定时任务不会执行，本页也不会假装已执行。',
            ),
            isThreeLine: true,
          ),
          ],
        ),
      ),
    );
  }
}
