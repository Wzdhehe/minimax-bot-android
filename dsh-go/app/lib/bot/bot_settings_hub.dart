// SPDX-License-Identifier: AGPL-3.0-only
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:mbot_settings/mbot_settings.dart';
import 'package:dsh_client/dsh_client.dart';

import '../l10n/l10n.dart';
import 'bot_providers.dart';
import 'bot_usage.dart';

/// Product settings use their own platform channel; no DSH engine is required.
class BotSettingsHub extends StatefulWidget {
  const BotSettingsHub({super.key, this.platform = const BotSettingsPlatform(), this.store, this.botApi});
  final MbotPlatform platform;
  final PrefsStore? store;
  final MiniMaxBotApi? botApi;
  @override
  State<BotSettingsHub> createState() => _BotSettingsHubState();
}

class _BotSettingsHubState extends State<BotSettingsHub> {
  MbotNotificationPrefsController? _prefs;
  bool _failed = false;
  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _failed = false);
    try {
      final prefs = MbotNotificationPrefsController(widget.store ?? await SharedPreferencesPrefsStore.create());
      await prefs.load();
      if (mounted) setState(() => _prefs = prefs);
    } catch (_) {
      if (mounted) setState(() => _failed = true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final prefs = _prefs;
    if (prefs != null) {
      return MbotSettingsHubPage(
        platform: widget.platform,
        notificationPrefs: prefs,
        pages: {
          MbotSettingsGroupId.accountAndModel: (_) => MbotProviderSettingsPage(
            port: const NativeBotProviders(),
            testProvider: widget.botApi?.testProvider,
            usagePage: (_) => MbotUsageQuotaPage(port: BotUsageQuotaPort(api: widget.botApi)),
          ),
        },
      );
    }
    return Scaffold(
      appBar: AppBar(title: Text(context.l10n.mobileBotSettings)),
      body: Center(
        child: _failed
            ? TextButton(onPressed: _load, child: Text(context.l10n.mobileBotRetry))
            : const SingleChildScrollView(child: MbotSkeleton()),
      ),
    );
  }
}

class BotSettingsPlatform extends MbotPlatform {
  const BotSettingsPlatform();
  static const _channel = MethodChannel('mbot/system-settings');
  Future<T?> _call<T>(String method, [Object? arguments]) async {
    try {
      return await _channel.invokeMethod<T>(method, arguments);
    } on PlatformException {
      return null;
    } on MissingPluginException {
      return null;
    }
  }

  @override
  Future<bool> areNotificationsEnabled() async => await _call<bool>('notificationsEnabled') ?? false;
  @override
  Future<bool?> isBatteryOptimizationIgnored() => _call<bool>('batteryIgnored');
  @override
  Future<bool> openAppNotificationSettings() async => await _call<bool>('openNotifications') ?? false;
  @override
  Future<bool> openChannelSettings(String channelId) async =>
      await _call<bool>('openChannel', {'id': channelId}) ?? false;
  @override
  Future<bool> openBatteryOptimizationSettings() async => await _call<bool>('openBattery') ?? false;
  @override
  Future<bool> openAppDetailsSettings() async => await _call<bool>('openDetails') ?? false;
  @override
  Future<VendorBackgroundInfo?> vendorBackgroundInfo() async => null;
  @override
  Future<void> ensureNotificationChannels(List<NotificationChannelSpec> channels) async {
    await _call<void>('ensureChannels', [
      for (final c in channels) {'id': c.id, 'name': c.name, 'description': c.description, 'importance': c.importance},
    ]);
  }
}
