import '../ia/settings_groups.dart';

/// Spec for one Android notification channel (native registration deferred).
class NotificationChannelSpec {
  const NotificationChannelSpec({
    required this.id,
    required this.name,
    required this.description,
    this.importance = 3, // IMPORTANCE_DEFAULT-ish; host maps as needed
  });

  final String id;
  final String name;
  final String description;
  final int importance;

  static List<NotificationChannelSpec> mbotDefaults() => [
        for (final category in MbotNotifyCategory.values)
          NotificationChannelSpec(
            id: MbotNotificationChannels.forCategory(category),
            name: category.title,
            description: category.subtitle,
            importance: category == MbotNotifyCategory.approval ||
                    category == MbotNotifyCategory.question ||
                    category == MbotNotifyCategory.scheduleDue
                ? 4
                : 3,
          ),
      ];
}

/// Optional OEM hint for the background-running guide (display only).
class VendorBackgroundInfo {
  const VendorBackgroundInfo({
    required this.vendorKey,
    required this.displayName,
    required this.steps,
  });

  /// Stable key such as `xiaomi`, `vivo`, `huawei`, `honor`, `oppo`, `samsung`.
  final String vendorKey;
  final String displayName;

  /// Short step lines shown under the vendor heading. Original M Bot copy.
  final List<String> steps;
}

/// Read-only system snapshot for the background-running page.
class BackgroundSystemStatus {
  const BackgroundSystemStatus({
    required this.notificationsEnabled,
    this.batteryOptimizationIgnored,
    this.vendor,
  });

  final bool notificationsEnabled;

  /// `null` when the host cannot answer (desktop / tests without a stub).
  final bool? batteryOptimizationIgnored;
  final VendorBackgroundInfo? vendor;
}

/// Platform capabilities the settings UI needs.
///
/// Implementations must be **self-authored**. Do not call into DSH Go channels.
/// Production Android glue can live later under this package's `android/` (or a
/// sibling package). Until then, inject [FakeMbotPlatform] in tests and a
/// no-op [UnsupportedMbotPlatform] in pure-Flutter shells.
abstract class MbotPlatform {
  const MbotPlatform();

  /// App-level notification permission / areNotificationsEnabled.
  Future<bool> areNotificationsEnabled();

  /// Opens the system page for this app's notifications. User-initiated only.
  Future<bool> openAppNotificationSettings();

  /// Opens the system page for one channel id. User-initiated only.
  /// May fall back to [openAppNotificationSettings] if the channel is unknown.
  Future<bool> openChannelSettings(String channelId);

  /// Ensures M Bot channels exist. May be a no-op until native code lands.
  Future<void> ensureNotificationChannels(
      List<NotificationChannelSpec> channels);

  /// Whether the app is on the battery-optimization ignore list; null if unknown.
  Future<bool?> isBatteryOptimizationIgnored();

  /// Opens the system battery-optimization / exemption UI. User-initiated only.
  /// Must not auto-fire on page open.
  Future<bool> openBatteryOptimizationSettings();

  /// Opens the app's system details page (fallback for OEM paths).
  Future<bool> openAppDetailsSettings();

  /// Optional OEM identity for guide copy.
  Future<VendorBackgroundInfo?> vendorBackgroundInfo();

  Future<BackgroundSystemStatus> readBackgroundStatus() async {
    final notifications = await areNotificationsEnabled();
    final battery = await isBatteryOptimizationIgnored();
    final vendor = await vendorBackgroundInfo();
    return BackgroundSystemStatus(
      notificationsEnabled: notifications,
      batteryOptimizationIgnored: battery,
      vendor: vendor,
    );
  }
}

/// Safe default when no native plugin is wired: reports unknown / fails opens.
class UnsupportedMbotPlatform implements MbotPlatform {
  const UnsupportedMbotPlatform();

  @override
  Future<bool> areNotificationsEnabled() async => false;

  @override
  Future<bool> openAppNotificationSettings() async => false;

  @override
  Future<bool> openChannelSettings(String channelId) async => false;

  @override
  Future<void> ensureNotificationChannels(
      List<NotificationChannelSpec> channels) async {}

  @override
  Future<bool?> isBatteryOptimizationIgnored() async => null;

  @override
  Future<bool> openBatteryOptimizationSettings() async => false;

  @override
  Future<bool> openAppDetailsSettings() async => false;

  @override
  Future<VendorBackgroundInfo?> vendorBackgroundInfo() async => null;

  @override
  Future<BackgroundSystemStatus> readBackgroundStatus() async =>
      const BackgroundSystemStatus(notificationsEnabled: false);
}
