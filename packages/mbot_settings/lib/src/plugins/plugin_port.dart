// SPDX-License-Identifier: AGPL-3.0-only
library;

String _text(Object? value, [int max = 2000]) => value is String
    ? value.substring(0, value.length > max ? max : value.length)
    : '';
int _count(Object? value) =>
    value is int && value >= 0 && value <= 100000 ? value : 0;

/// A display-only projection: never carry MCP configJson, environment, tokens,
/// arbitrary commands or executable plugin payloads into the settings UI.
class MbotPlugin {
  const MbotPlugin(
      {required this.name,
      required this.displayName,
      required this.description,
      required this.enabled,
      required this.installed,
      required this.source,
      required this.managed,
      required this.skills,
      required this.mcpServers,
      required this.hooks,
      required this.apps});
  final String name, displayName, description;
  final bool enabled, installed, managed;
  final int source, skills, mcpServers, hooks, apps;
  factory MbotPlugin.fromMap(Map value, {required bool market}) {
    final name = value['name'];
    if (name is! String || name.isEmpty || name.length > 200) {
      throw const FormatException('Invalid plugin identity');
    }
    final source = value['source'] ?? (market ? 1 : null);
    if (source != 1 && source != 2) {
      throw const FormatException('Unknown plugin source');
    }
    final caps =
        value['capabilities'] is Map ? value['capabilities'] as Map : const {};
    return MbotPlugin(
        name: name,
        displayName: _text(value['displayName'], 200).isEmpty
            ? name
            : _text(value['displayName'], 200),
        description: _text(value['description']),
        enabled: value['enabled'] == true,
        installed: !market || value['installExists'] == true,
        source: source as int,
        managed: value['installationPolicy'] == 2,
        skills: _count(caps['skillCount']),
        mcpServers: _count(caps['mcpServerCount']),
        hooks: _count(caps['hookCount']),
        apps: _count(caps['appCount']));
  }
}

class MbotPluginPage {
  const MbotPluginPage(this.items, {this.nextCursor, this.hasMore = false});
  final List<MbotPlugin> items;
  final String? nextCursor;
  final bool hasMore;
  factory MbotPluginPage.fromMap(Map value, {required bool market}) {
    final rows = value['plugins'];
    if (rows is! List || rows.length > 1000) {
      throw const FormatException('Invalid plugin page');
    }
    final rawCursor = value['nextCursor'];
    if (rawCursor != null &&
        (rawCursor is! String || rawCursor.length > 4096)) {
      throw const FormatException('Invalid plugin cursor');
    }
    final cursor = rawCursor as String? ?? '';
    return MbotPluginPage(List.unmodifiable(rows.map((row) {
      if (row is! Map) throw const FormatException('Invalid plugin row');
      return MbotPlugin.fromMap(row, market: market);
    })),
        nextCursor: cursor.isEmpty ? null : cursor,
        hasMore: value['hasMore'] == true);
  }
}

class MbotGithubPluginPreview {
  const MbotGithubPluginPreview(
      {required this.plugin,
      required this.repositoryUrl,
      required this.commitSha,
      required this.hasStdioMcp,
      required this.canImport,
      this.subPath});
  final MbotPlugin plugin;
  final String repositoryUrl, commitSha;
  final String? subPath;
  final bool hasStdioMcp, canImport;
  factory MbotGithubPluginPreview.fromMap(Map value) {
    final source = value['source'];
    final plugin = value['plugin'];
    if (source is! Map || plugin is! Map || plugin['summary'] is! Map) {
      throw const FormatException('Invalid GitHub plugin preview');
    }
    final url = source['repositoryUrl'];
    if (url is! String || url.length > 2048) {
      throw const FormatException('Invalid GitHub source');
    }
    final uri = Uri.tryParse(url);
    final sha = source['commitSha'];
    if (uri == null ||
        uri.scheme != 'https' ||
        uri.host != 'github.com' ||
        uri.userInfo.isNotEmpty ||
        uri.hasPort ||
        uri.hasQuery ||
        uri.hasFragment ||
        sha is! String ||
        !RegExp(r'^[0-9a-fA-F]{40}$').hasMatch(sha)) {
      throw const FormatException('Preview must have a pinned GitHub source');
    }
    final subPath = source['subPath'];
    if (subPath != null &&
        (subPath is! String ||
            subPath.length > 512 ||
            subPath.contains('\\') ||
            subPath.contains('\u0000') ||
            (subPath.isNotEmpty &&
                subPath.split('/').any(
                    (part) => part.isEmpty || part == '.' || part == '..')))) {
      throw const FormatException('Invalid GitHub plugin subpath');
    }
    return MbotGithubPluginPreview(
        plugin: MbotPlugin.fromMap(plugin['summary'] as Map, market: true),
        repositoryUrl: url,
        commitSha: sha,
        subPath: subPath as String?,
        hasStdioMcp: plugin['hasStdioMcp'] == true,
        canImport: value['canImport'] == true);
  }
}

abstract class MbotPluginPort {
  const MbotPluginPort();
  bool get available;

  /// UI affordance only. The runtime must independently enforce execution and
  /// installation policy; this flag is not a security boundary.
  bool get canMutate;
  Future<MbotPluginPage> load(
      {required bool market, String keyword = '', String? cursor});
  Future<MbotGithubPluginPreview> previewGithub(String url);
  Future<void> install(MbotPlugin item);
  Future<void> setEnabled(MbotPlugin item, bool enabled);
  Future<void> uninstall(MbotPlugin item);
  Future<void> importGithub(MbotGithubPluginPreview preview);
}
