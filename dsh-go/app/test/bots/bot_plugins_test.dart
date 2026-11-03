// SPDX-License-Identifier: AGPL-3.0-only
import 'package:dsh_app/bot/bot_plugins.dart';
import 'package:dsh_app/bot/bot_settings_hub.dart';
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mbot_settings/mbot_settings.dart';

class _Api extends McodePluginsApi {
  _Api() : super(Uri.parse('ws://127.0.0.1:8789/'), token: 'test-only');
  String? lastCursor;
  int? lastSource;
  int previews = 0;
  @override
  Future<Object?> marketplace({String keyword = '', int limit = 40, String? cursor, int? source}) async {
    lastCursor = cursor;
    lastSource = source;
    return {
      'plugins': [
        {'name': 'plugin_a', 'installExists': false},
      ],
      'hasMore': true,
      'nextCursor': 'market_next',
    };
  }

  @override
  Future<Object?> installed({String keyword = '', int limit = 100, int offset = 0}) async => {
    'plugins': [
      {'name': 'plugin_a', 'source': 2, 'enabled': false},
    ],
    'hasMore': true,
    'nextCursor': 'installed_next',
  };
  @override
  Future<Object?> previewGithub(String url) async {
    previews++;
    return {};
  }
}

void main() {
  test(
    'adapter preserves official market pagination, reports installed wire limitation and closes all writes',
    () async {
      final api = _Api();
      final port = BotPluginsPort(api: api);
      final page = await port.load(market: true, cursor: 'market_previous');
      expect(api.lastCursor, 'market_previous');
      expect(api.lastSource, 1);
      expect(page.nextCursor, 'market_next');
      final installed = await port.load(market: false);
      expect(installed.hasMore, isTrue);
      expect(installed.nextCursor, isNull);
      expect(port.canMutate, isFalse);
      await expectLater(port.load(market: false, cursor: 'installed_next'), throwsUnsupportedError);
      await expectLater(port.install(page.items.single), throwsUnsupportedError);
      await expectLater(port.setEnabled(page.items.single, true), throwsUnsupportedError);
      await expectLater(port.uninstall(page.items.single), throwsUnsupportedError);
      await expectLater(port.previewGithub('https://example.com/a/b'), throwsFormatException);
      expect(api.previews, 0);
    },
  );
  testWidgets('real app hub opens an unavailable plugin page without an engine', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: BotSettingsHub(store: MemoryPrefsStore(), platform: FakeMbotPlatform()),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('mbot.settings.hub.plugins')));
    await tester.pumpAndSettle();
    expect(find.text('尚未连接 MiniMax Code 本机插件服务'), findsOneWidget);
    expect(find.textContaining('安装与执行暂未开放'), findsOneWidget);
  });
}
