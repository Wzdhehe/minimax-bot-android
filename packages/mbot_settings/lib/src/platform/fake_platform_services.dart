import 'platform_services.dart';

/// Mutable platform stub for widget / unit tests.
class FakeMbotPlatform implements MbotPlatform {
  FakeMbotPlatform({
    this.notificationsEnabled = true,
    this.batteryOptimizationIgnored = false,
    this.vendor,
    this.openAppNotificationSettingsResult = true,
    this.openChannelSettingsResult = true,
    this.openBatteryOptimizationSettingsResult = true,
    this.openAppDetailsSettingsResult = true,
  });

  bool notificationsEnabled;
  bool? batteryOptimizationIgnored;
  VendorBackgroundInfo? vendor;

  bool openAppNotificationSettingsResult;
  bool openChannelSettingsResult;
  bool openBatteryOptimizationSettingsResult;
  bool openAppDetailsSettingsResult;

  final List<String> openedChannels = <String>[];
  final List<String> calls = <String>[];
  List<NotificationChannelSpec> ensuredChannels = const [];

  @override
  Future<bool> areNotificationsEnabled() async {
    calls.add('areNotificationsEnabled');
    return notificationsEnabled;
  }

  @override
  Future<bool> openAppNotificationSettings() async {
    calls.add('openAppNotificationSettings');
    return openAppNotificationSettingsResult;
  }

  @override
  Future<bool> openChannelSettings(String channelId) async {
    calls.add('openChannelSettings:$channelId');
    openedChannels.add(channelId);
    return openChannelSettingsResult;
  }

  @override
  Future<void> ensureNotificationChannels(List<NotificationChannelSpec> channels) async {
    calls.add('ensureNotificationChannels');
    ensuredChannels = List<NotificationChannelSpec>.from(channels);
  }

  @override
  Future<bool?> isBatteryOptimizationIgnored() async {
    calls.add('isBatteryOptimizationIgnored');
    return batteryOptimizationIgnored;
  }

  @override
  Future<bool> openBatteryOptimizationSettings() async {
    calls.add('openBatteryOptimizationSettings');
    // Simulate user returning from settings with exemption granted when result is true.
    if (openBatteryOptimizationSettingsResult) {
      batteryOptimizationIgnored = true;
    }
    return openBatteryOptimizationSettingsResult;
  }

  @override
  Future<bool> openAppDetailsSettings() async {
    calls.add('openAppDetailsSettings');
    return openAppDetailsSettingsResult;
  }

  @override
  Future<VendorBackgroundInfo?> vendorBackgroundInfo() async {
    calls.add('vendorBackgroundInfo');
    return vendor;
  }

  @override
  Future<BackgroundSystemStatus> readBackgroundStatus() async {
    calls.add('readBackgroundStatus');
    return BackgroundSystemStatus(
      notificationsEnabled: notificationsEnabled,
      batteryOptimizationIgnored: batteryOptimizationIgnored,
      vendor: vendor,
    );
  }
}
