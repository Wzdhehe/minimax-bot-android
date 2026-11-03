// SPDX-License-Identifier: AGPL-3.0-only
class MbotQuotaWindow {
  const MbotQuotaWindow(
      {this.usedPercent, this.resetAtMs, this.unlimited = false});
  final double? usedPercent;
  final int? resetAtMs;
  final bool unlimited;
  factory MbotQuotaWindow.fromMap(Map value) {
    final used = value['usedPercent'];
    final reset = value['resetAtMs'];
    return MbotQuotaWindow(
      usedPercent: used is num && used.isFinite && used >= 0 && used <= 100
          ? used.toDouble()
          : null,
      resetAtMs: reset is num &&
              reset.isFinite &&
              reset >= 0 &&
              reset <= 8640000000000000
          ? reset.toInt()
          : null,
      unlimited: value['unlimited'] == true,
    );
  }
}

class MbotUsageQuota {
  const MbotUsageQuota(
      {required this.signedIn,
      this.supported = true,
      this.planName,
      this.creditBalance,
      this.fiveHour,
      this.weekly});
  final bool signedIn;
  final bool supported;
  final String? planName;
  final String? creditBalance;
  final MbotQuotaWindow? fiveHour;
  final MbotQuotaWindow? weekly;
  factory MbotUsageQuota.fromMap(Map<String, Object?> value) {
    if (['apiKey', 'accessToken', 'refreshToken', 'token']
        .any(value.containsKey)) {
      throw const FormatException(
          'Quota projection contains credential fields');
    }
    if (value['signedIn'] != true) return const MbotUsageQuota(signedIn: false);
    final quota = value['quota'];
    final balance = value['creditBalance'];
    return MbotUsageQuota(
      signedIn: true,
      planName: value['planName'] is String
          ? value['planName'] as String
          : value['subscriptionType'] as String?,
      creditBalance:
          balance is String && RegExp(r'^\d+(?:\.\d+)?$').hasMatch(balance)
              ? balance
              : null,
      fiveHour: quota is Map && quota['fiveHour'] is Map
          ? MbotQuotaWindow.fromMap(quota['fiveHour'] as Map)
          : null,
      weekly: quota is Map && quota['weekly'] is Map
          ? MbotQuotaWindow.fromMap(quota['weekly'] as Map)
          : null,
    );
  }
}

abstract class MbotUsageQuotaPort {
  Future<MbotUsageQuota> load({bool forceRefresh = false});
}
