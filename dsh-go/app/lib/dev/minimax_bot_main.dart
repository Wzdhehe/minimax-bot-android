// UI integration entry for an already running local Bot service. The production
// entry will use the authenticated descriptor from the Android runtime launcher.
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/material.dart';

import '../bot/bot_controller.dart';
import '../bot/bot_home.dart';
import '../l10n/l10n.dart';
import '../ui/theme/app_theme.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  final api = MiniMaxBotApi(
    Uri.parse(const String.fromEnvironment('BOT_SERVICE_URL', defaultValue: 'http://127.0.0.1:8788')),
  );
  runApp(
    MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'Bot',
      theme: buildDarkTheme(glassBlur: false),
      localizationsDelegates: L10n.localizationsDelegates,
      supportedLocales: AppLocale.supported,
      home: BotHome(controller: BotController(api)),
    ),
  );
}
