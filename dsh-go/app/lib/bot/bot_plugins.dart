// SPDX-License-Identifier: AGPL-3.0-only
import 'package:dsh_client/dsh_client.dart';
import 'package:mbot_settings/mbot_settings.dart';

/// Reads the pinned WebUI protocol with a trusted per-launch endpoint/token.
/// Production composition has no descriptor yet: absent API means unavailable,
/// never an empty fake catalogue. Android installation/execution stays closed
/// until the runtime implements an independently enforced compatibility policy.
class BotPluginsPort extends MbotPluginPort {
  const BotPluginsPort({this.api});
  final McodePluginsApi? api;
  @override
  bool get available => api != null;
  @override
  bool get canMutate => false;

  @override
  Future<MbotPluginPage> load({required bool market, String keyword = '', String? cursor}) async {
    final client = api;
    if (client == null) throw StateError('plugin_runtime_unavailable');
    // At 97818b2 protocol/local.ts exposes an installed cursor, but the WebUI
    // operation schema rejects it and offset is ignored by plugin-system.
    // Do not request the first page again and pretend pagination worked.
    if (!market && cursor != null) throw UnsupportedError('installed_cursor_unavailable');
    final result = market
        ? await client.marketplace(keyword: keyword, cursor: cursor, source: 1)
        : await client.installed(keyword: keyword);
    if (result is! Map) throw const FormatException('Invalid plugin response');
    final page = MbotPluginPage.fromMap(result, market: market);
    return market ? page : MbotPluginPage(page.items, hasMore: page.hasMore);
  }

  @override
  Future<MbotGithubPluginPreview> previewGithub(String url) async {
    final client = api;
    if (client == null) throw StateError('plugin_runtime_unavailable');
    final uri = Uri.tryParse(url);
    if (uri == null ||
        uri.scheme != 'https' ||
        uri.host != 'github.com' ||
        uri.userInfo.isNotEmpty ||
        uri.hasQuery ||
        uri.hasFragment ||
        uri.hasPort ||
        uri.pathSegments.where((part) => part.isNotEmpty).length < 2) {
      throw const FormatException('Expected a GitHub repository URL');
    }
    final result = await client.previewGithub(url);
    if (result is! Map) throw const FormatException('Invalid plugin preview');
    return MbotGithubPluginPreview.fromMap(result);
  }

  Never _closed() => throw UnsupportedError('android_plugin_execution_not_ready');
  @override
  Future<void> install(MbotPlugin item) async => _closed();
  @override
  Future<void> setEnabled(MbotPlugin item, bool enabled) async => _closed();
  @override
  Future<void> uninstall(MbotPlugin item) async => _closed();
  @override
  Future<void> importGithub(MbotGithubPluginPreview preview) async => _closed();
}
