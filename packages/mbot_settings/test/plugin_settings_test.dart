// SPDX-License-Identifier: AGPL-3.0-only
import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mbot_settings/mbot_settings.dart';

MbotPlugin item(String name, {bool installed = true, bool enabled = false}) =>
    MbotPlugin.fromMap({
      'name': name,
      'source': 1,
      'enabled': enabled,
      'installExists': installed,
      'capabilities': {'skillCount': 2, 'appCount': 1}
    }, market: !installed);

class _Port extends MbotPluginPort {
  _Port({this.available = true, this.canMutate = true});
  @override
  final bool available;
  @override
  final bool canMutate;
  int reads = 0, mutations = 0;
  bool enabled = false;
  Completer<MbotPluginPage>? pendingInstalled;
  @override
  Future<MbotPluginPage> load(
      {required bool market, String keyword = '', String? cursor}) async {
    reads++;
    if (!market && pendingInstalled != null) return pendingInstalled!.future;
    return MbotPluginPage([
      item(market ? 'Market plugin' : 'My plugin',
          installed: !market, enabled: enabled)
    ]);
  }

  @override
  Future<void> setEnabled(MbotPlugin item, bool value) async {
    mutations++;
    enabled = value; // Host executed it, but its response was lost.
    throw TimeoutException('test response lost');
  }

  @override
  Future<void> install(MbotPlugin item) async {
    mutations++;
  }

  @override
  Future<void> uninstall(MbotPlugin item) async {
    mutations++;
  }

  @override
  Future<void> importGithub(MbotGithubPluginPreview preview) async {
    mutations++;
  }

  @override
  Future<MbotGithubPluginPreview> previewGithub(String url) async =>
      MbotGithubPluginPreview(
          plugin: item('Preview'),
          repositoryUrl: 'https://github.com/example/repo',
          commitSha: 'a' * 40,
          hasStdioMcp: true,
          canImport: true);
}

void main() {
  test(
      'display projection discards config and rejects changed plugin identities or unpinned previews',
      () {
    final plugin = MbotPlugin.fromMap({
      'name': 'safe',
      'source': 2,
      'configJson': '{"env":{"TOKEN":"private"}}',
      'capabilities': {'mcpServerCount': 3, 'hookCount': -1}
    }, market: false);
    expect(plugin.mcpServers, 3);
    expect(plugin.hooks, 0);
    expect(plugin.description, isEmpty);
    expect(() => MbotPlugin.fromMap({'name': 'a' * 201}, market: true),
        throwsFormatException);
    expect(
        () => MbotGithubPluginPreview.fromMap({
              'source': {
                'repositoryUrl': 'https://github.com/example/repo',
                'commitSha': 'a' * 41
              },
              'plugin': {
                'summary': {'name': 'test'}
              }
            }),
        throwsFormatException);
  });
  testWidgets(
      'unavailable runtime has an honest empty state and disabled actions',
      (tester) async {
    final port = _Port(available: false, canMutate: false);
    await tester
        .pumpWidget(MaterialApp(home: MbotPluginSettingsPage(port: port)));
    await tester.pumpAndSettle();
    expect(find.text('尚未连接 MiniMax Code 本机插件服务'), findsOneWidget);
    expect(find.text('暂无插件'), findsNothing);
    expect(port.reads, 0);
    expect(
        tester
            .widget<IconButton>(find.byWidgetPredicate(
                (w) => w is IconButton && w.tooltip == '从 GitHub 预览'))
            .onPressed,
        isNull);
  });
  testWidgets('late installed response cannot replace the selected marketplace',
      (tester) async {
    final port = _Port()..pendingInstalled = Completer();
    await tester
        .pumpWidget(MaterialApp(home: MbotPluginSettingsPage(port: port)));
    await tester.tap(find.text('插件市场'));
    await tester.pumpAndSettle();
    expect(find.text('Market plugin'), findsOneWidget);
    port.pendingInstalled!.complete(MbotPluginPage([item('Stale plugin')]));
    await tester.pumpAndSettle();
    expect(find.text('Stale plugin'), findsNothing);
    expect(find.text('Market plugin'), findsOneWidget);
  });
  testWidgets(
      'uncertain mutation is sent once and reconciled only after a user refresh',
      (tester) async {
    final port = _Port();
    await tester
        .pumpWidget(MaterialApp(home: MbotPluginSettingsPage(port: port)));
    await tester.pumpAndSettle();
    await tester.tap(find.byType(PopupMenuButton<String>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('启用'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('确认'));
    await tester.pumpAndSettle();
    await tester.pump(const Duration(seconds: 1));
    expect(port.mutations, 1);
    expect(port.reads, 1);
    expect(find.textContaining('操作结果未确认'), findsOneWidget);
    await tester.tap(find.byTooltip('刷新'));
    await tester.pumpAndSettle();
    expect(port.reads, 2);
    await tester.tap(find.byType(PopupMenuButton<String>));
    await tester.pumpAndSettle();
    expect(find.text('停用'), findsOneWidget);
  });
  testWidgets(
      'GitHub preview shows the pinned commit and cannot import on a readonly host',
      (tester) async {
    final port = _Port(canMutate: false);
    await tester
        .pumpWidget(MaterialApp(home: MbotPluginSettingsPage(port: port)));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('从 GitHub 预览'));
    await tester.pumpAndSettle();
    await tester.enterText(
        find.byType(TextField).last, 'https://github.com/example/repo');
    await tester.tap(find.text('预览'));
    await tester.pumpAndSettle();
    expect(find.text('a' * 40), findsOneWidget);
    expect(find.textContaining('需要启动本机进程'), findsOneWidget);
    expect(
        tester
            .widget<FilledButton>(find.widgetWithText(FilledButton, '导入此版本'))
            .onPressed,
        isNull);
    expect(port.mutations, 0);
    expect(tester.takeException(), isNull);
  });
}
