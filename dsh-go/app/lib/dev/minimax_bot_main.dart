// UI integration entry for the on-device Bot service.
//
// The service lives in the local runtime, so its endpoint and session token
// come from native: this entry asks for the live endpoint instead of
// assuming a port, and it shows the state plainly when no service is
// running.
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/material.dart';

import '../bot/bot_controller.dart';
import '../bot/bot_home.dart';
import '../bot/bot_runtime_endpoint.dart';
import '../l10n/l10n.dart';
import '../ui/theme/app_theme.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const BotRuntimeApp());
}

/// The Bot UI over the service the runtime actually published.
class BotRuntimeApp extends StatefulWidget {
  const BotRuntimeApp({super.key, this.endpoint = const BotRuntimeEndpoint()});

  final BotRuntimeEndpoint endpoint;

  @override
  State<BotRuntimeApp> createState() => _BotRuntimeAppState();
}

class _BotRuntimeAppState extends State<BotRuntimeApp> {
  late final Future<MiniMaxBotApi?> _service = widget.endpoint.liveService();

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'Bot',
      theme: buildDarkTheme(glassBlur: false),
      localizationsDelegates: L10n.localizationsDelegates,
      supportedLocales: AppLocale.supported,
      home: FutureBuilder<MiniMaxBotApi?>(
        future: _service,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const _BotUnavailable('Starting the local Bot service');
          }
          if (snapshot.hasError) {
            return const _BotUnavailable('The local Bot service cannot be reached');
          }
          final api = snapshot.data;
          if (api == null) {
            return const _BotUnavailable('No Bot service is running');
          }
          return BotHome(controller: BotController(api));
        },
      ),
    );
  }
}

/// A plain state, never a fake service and never a fake answer.
class _BotUnavailable extends StatelessWidget {
  const _BotUnavailable(this.message);

  final String message;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Text(message, textAlign: TextAlign.center),
        ),
      ),
    );
  }
}
