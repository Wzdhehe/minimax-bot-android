// SPDX-License-Identifier: AGPL-3.0-only
import 'package:dsh_client/dsh_client.dart';
import 'package:mbot_settings/mbot_settings.dart';

import 'bot_providers.dart';

class BotUsageQuotaPort implements MbotUsageQuotaPort {
  const BotUsageQuotaPort({this.api, this.providers = const NativeBotProviders()});
  final MiniMaxBotApi? api;
  final MbotProviderPort providers;
  @override
  Future<MbotUsageQuota> load({bool forceRefresh = false}) async {
    final catalog = await providers.load();
    // Native API providers have no MiniMax OAuth account lease, including a
    // MiniMax API key. Never send a third-party key to the quota service.
    if (catalog.defaultProviderId != null && catalog.providers.any((p) => p.id == catalog.defaultProviderId)) {
      return const MbotUsageQuota(signedIn: false, supported: false);
    }
    final host = api;
    if (host == null) throw StateError('Account service is not connected');
    return MbotUsageQuota.fromMap(await host.usageQuota(forceRefresh: forceRefresh));
  }
}
