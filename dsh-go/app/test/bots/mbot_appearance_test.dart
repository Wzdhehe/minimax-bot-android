// SPDX-License-Identifier: AGPL-3.0-only
import 'dart:async';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:dsh_app/bot/bot_avatar.dart';
import 'package:dsh_app/bot/bot_controller.dart';
import 'package:dsh_app/bot/bot_home.dart';
import 'package:dsh_app/bot/bot_profile.dart';
import 'package:dsh_app/bot/bot_theme.dart';
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mbot_settings/mbot_settings.dart';

class _Api extends MiniMaxBotApi {
  _Api() : super(Uri.parse('http://127.0.0.1:8788')) {
    changes.onListen = () => changes.add({'type': 'ready'});
  }
  final changes = StreamController<Map<String, Object?>>.broadcast();
  final bots = <Map<String, Object?>>[
    {'id': 'bot_chief', 'name': 'Chief', 'skin': 'moon', 'isMain': true},
    {'id': 'bot_design', 'name': '设计搭子', 'skin': 'glass', 'isMain': true},
    {'id': 'bot_research', 'name': '研究助手', 'skin': 'jade', 'isMain': false},
    {'id': 'bot_notes', 'name': '灵感笔记', 'skin': 'gold', 'isMain': false},
  ];
  final updates = <Map<String, Object?>>[];
  Map<String, Object?> serverOverride = {};
  @override
  Stream<Map<String, Object?>> events() => changes.stream;
  @override
  Future<Map<String, Object?>> state() async => {
    'bots': bots.map((row) => Map<String, Object?>.from(row)).toList(),
    'groups': [
      {
        'id': 'grp_team',
        'name': '一起做点好东西',
        'adminId': 'bot_chief',
        'memberIds': ['bot_design', 'bot_research', 'bot_notes'],
      },
    ],
    'schedules': [],
    'chats': {
      'bot:bot_research': {'preview': '资料找到了，关键结论整理在这里。'},
      'bot:bot_notes': {'preview': '记住这个想法，明天我们接着聊。'},
      'group:grp_team': {'preview': '设计搭子：新的方向，我画了几个草图。'},
    },
  };
  @override
  Future<Map<String, Object?>> updateBot(String id, Map<String, Object?> properties) async {
    updates.add({'id': id, ...properties});
    final row = bots.firstWhere((row) => row['id'] == id);
    row.addAll({...properties, ...serverOverride});
    return {'bot': Map<String, Object?>.from(row)};
  }

  @override
  void close() {
    unawaited(changes.close());
    super.close();
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  Widget app(Widget child, {bool dark = false}) => MaterialApp(
    theme: mbotTheme(dark ? ThemeData.dark() : ThemeData.light()),
    home: RepaintBoundary(key: const Key('mbot.preview'), child: child),
  );
  Future<void> capture(WidgetTester tester, String name) async {
    final directory = Platform.environment['MBOT_CAPTURE_DIR'];
    if (directory == null) return;
    // Asset decoding runs outside the widget test's fake clock. Await the
    // actual providers (including their bounded decode size) before capture.
    await tester.runAsync(() async {
      final context = tester.element(find.byKey(const Key('mbot.preview')));
      await Future.wait([
        for (final element in find.byType(Image).evaluate()) precacheImage((element.widget as Image).image, context),
      ]);
    });
    await tester.pumpAndSettle();
    await tester.runAsync(() async {
      final boundary = tester.renderObject<RenderRepaintBoundary>(find.byKey(const Key('mbot.preview')));
      final image = await boundary.toImage(pixelRatio: 2);
      final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
      await Directory(directory).create(recursive: true);
      await File('$directory/$name.png').writeAsBytes(bytes!.buffer.asUint8List());
      image.dispose();
    });
  }

  test('all original artwork and its MIT notice are present in the application bundle', () async {
    for (final skin in MbotAvatar.skinIds) {
      final bytes = await rootBundle.load('assets/mbot/skins/$skin.png');
      final codec = await ui.instantiateImageCodec(bytes.buffer.asUint8List(bytes.offsetInBytes, bytes.lengthInBytes));
      final frame = await codec.getNextFrame();
      expect(frame.image.width, greaterThan(0));
      expect(frame.image.height, greaterThan(0));
      frame.image.dispose();
      codec.dispose();
    }
    expect(await rootBundle.loadString('assets/mbot/LICENSE.minimax-bot'), contains('MIT License'));
  });

  for (final dark in [false, true]) {
    testWidgets('original artwork and every real Main Bot appear in ${dark ? 'dark' : 'light'} home', (tester) async {
      tester.view.physicalSize = const Size(390, 844);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final api = _Api();
      final state = BotController(api);
      await tester.pumpWidget(app(BotHome(controller: state), dark: dark));
      await tester.pumpAndSettle();
      expect(find.text('mbot'), findsOneWidget);
      expect(find.byKey(const ValueKey('mbot.main.bot_chief')), findsOneWidget);
      expect(find.byKey(const ValueKey('mbot.main.bot_design')), findsOneWidget);
      expect(find.byKey(const Key('mbot.avatar.main')), findsNWidgets(2));
      expect(find.byType(MbotGroupAvatar), findsOneWidget);
      expect(find.byType(CircleAvatar), findsNothing);
      expect(tester.takeException(), isNull);
      await capture(tester, dark ? 'mbot-home-dark' : 'mbot-home-light');
      await tester.enterText(find.byType(TextField), 'Chief');
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('mbot.row.bot:bot_chief')), findsOneWidget);
      expect(find.byKey(const Key('mbot.avatar.main')), findsOneWidget);
      expect(find.text('研究助手'), findsNothing);
      await tester.pumpWidget(const SizedBox());
      state.dispose();
      api.close();
    });
  }

  testWidgets('editing can add another Main Bot and choose original artwork using the service contract', (
    tester,
  ) async {
    final api = _Api();
    api.bots[1]['isMain'] = false;
    api.serverOverride = {'skin': 'jade'}; // The displayed result must come from the subsequent state read.
    await tester.pumpWidget(app(BotProfilePage(api: api, botId: 'bot_design', store: MemoryPrefsStore())));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(TextButton, '编辑'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const Key('mbot.edit.main')));
    await tester.tap(find.byKey(const Key('mbot.edit.main')));
    final gold = find.byKey(const ValueKey('mbot.skin.gold'));
    await tester.ensureVisible(gold);
    await tester.tap(gold);
    await tester.tap(find.widgetWithText(TextButton, '保存'));
    await tester.pumpAndSettle();
    expect(api.updates.single['isMain'], true);
    expect(api.updates.single['skin'], 'gold');
    expect(api.updates.single.containsKey('avatar'), true);
    expect(api.updates.single['avatar'], isNull);
    expect(api.bots.where((row) => row['isMain'] == true), hasLength(2));
    expect(tester.widget<MbotAvatar>(find.byType(MbotAvatar).first).bot['skin'], 'jade');
    expect(find.byKey(const Key('mbot.avatar.main')), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    api.close();
  });

  testWidgets('the edit form cannot remove the final Main Bot', (tester) async {
    final api = _Api();
    api.bots[1]['isMain'] = false;
    await tester.pumpWidget(app(BotProfilePage(api: api, botId: 'bot_chief', store: MemoryPrefsStore())));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(TextButton, '编辑'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const Key('mbot.edit.main')));
    await tester.tap(find.byKey(const Key('mbot.edit.main')));
    await tester.pump();
    expect(tester.widget<TextButton>(find.widgetWithText(TextButton, '保存')).onPressed, isNull);
    expect(find.text('至少保留一个主 Bot。'), findsOneWidget);
    expect(api.updates, isEmpty);
    await tester.pumpWidget(const SizedBox());
    api.close();
  });

  testWidgets('expanded layout and large text have no overflow', (tester) async {
    tester.view.physicalSize = const Size(1024, 700);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final api = _Api();
    final state = BotController(api);
    await tester.pumpWidget(
      app(
        MediaQuery(
          data: const MediaQueryData(size: Size(1024, 700), textScaler: TextScaler.linear(1.3)),
          child: BotHome(controller: state),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('mbot.main.bot_chief')), findsOneWidget);
    expect(tester.takeException(), isNull);
    await capture(tester, 'mbot-home-tablet');
    await tester.pumpWidget(const SizedBox());
    state.dispose();
    api.close();
  });
}
