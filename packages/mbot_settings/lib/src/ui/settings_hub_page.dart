import 'package:flutter/material.dart';

import '../ia/settings_groups.dart';
import '../platform/platform_services.dart';
import '../prefs/notification_prefs.dart';
import 'background_running_page.dart';
import 'notification_settings_page.dart';

/// Settings home: every E.2 group as an entry; only notifications + background open for real.
class MbotSettingsHubPage extends StatelessWidget {
  const MbotSettingsHubPage({
    super.key,
    required this.platform,
    required this.notificationPrefs,
    this.groups = MbotSettingsIa.groups,
    this.pages = const {},
  });

  final MbotPlatform platform;
  final MbotNotificationPrefsController notificationPrefs;
  final List<MbotSettingsGroup> groups;

  /// Product-owned pages, such as the encrypted provider settings screen.
  final Map<MbotSettingsGroupId, WidgetBuilder> pages;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('设置')),
      body: SingleChildScrollView(
        key: const Key('mbot.settings.hub.list'),
        child: Column(
          children: [
            for (final group in groups)
              ListTile(
                key: Key('mbot.settings.hub.${group.id.name}'),
                title: Text(group.title),
                subtitle: Text(group.subtitle),
                trailing: pages.containsKey(group.id) ||
                        group.kind == MbotSettingsEntryKind.implemented
                    ? const Icon(Icons.chevron_right)
                    : Text(
                        '即将实现',
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                              color: Theme.of(context).colorScheme.outline,
                            ),
                      ),
                onTap: () => _open(context, group),
              ),
          ],
        ),
      ),
    );
  }

  void _open(BuildContext context, MbotSettingsGroup group) {
    final page = pages[group.id];
    if (page != null) {
      Navigator.of(context).push(MaterialPageRoute<void>(builder: page));
      return;
    }
    switch (group.id) {
      case MbotSettingsGroupId.notifications:
        Navigator.of(context).push(
          MaterialPageRoute<void>(
            builder: (_) => MbotNotificationSettingsPage(
              platform: platform,
              prefs: notificationPrefs,
            ),
          ),
        );
      case MbotSettingsGroupId.backgroundRunning:
        Navigator.of(context).push(
          MaterialPageRoute<void>(
            builder: (_) => MbotBackgroundRunningPage(platform: platform),
          ),
        );
      default:
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('「${group.title}」尚未实现')),
        );
    }
  }
}
