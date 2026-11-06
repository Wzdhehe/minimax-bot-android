// SPDX-License-Identifier: AGPL-3.0-only
import 'dart:async';

import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/material.dart';
import 'package:mbot_settings/mbot_settings.dart';

import '../l10n/l10n.dart';
import '../launch/launch_state.dart';
import 'bot_controller.dart';
import 'bot_home.dart';
import 'bot_runtime_endpoint.dart';
import 'bot_settings_hub.dart';

/// What the phone last said about the Bot service.
enum _BotEntryPhase {
  /// The phone has not answered yet.
  connecting,

  /// No Bot service is running.
  stopped,

  /// The phone could not be reached, or gave an address that is not usable.
  failed,
}

/// The App's entry: the official Bot interface.
///
/// Native owns the Bot service: [BotRuntimeEndpoint] reports the service that
/// is running, or nothing when there is none. This page never invents a port,
/// a token or a service of its own.
///
/// While no service runs, the page says so and offers the settings, where the
/// key, the base URL and the model are entered and kept by native; the phone
/// is asked again once the settings close, because a service may have started
/// by then.
///
/// Leaving the page lets go of the client this page owns. It does not stop
/// the engine behind it: that one belongs to native.
class BotEntry extends StatefulWidget {
  const BotEntry({super.key, this.endpoint = const BotRuntimeEndpoint(), this.settingsPage});

  /// The phone's own report of the running service.
  final BotRuntimeEndpoint endpoint;

  /// The settings page (tests). The real one is [BotSettingsHub].
  final WidgetBuilder? settingsPage;

  @override
  State<BotEntry> createState() => _BotEntryState();
}

class _BotEntryState extends State<BotEntry> {
  /// The service and the controller this page owns.
  MiniMaxBotApi? _api;
  BotController? _controller;

  _BotEntryPhase _state = _BotEntryPhase.connecting;

  /// The read that owns the answer; a later read makes an earlier one late.
  int _read = 0;

  /// A read is running. A refresh asked for during it runs once more after,
  /// never at the same time.
  bool _reading = false;
  bool _again = false;

  @override
  void initState() {
    super.initState();
    // The first page is up: the launch screen may go now. This entry talks to
    // the Bot service, not to the old engine, so it waits for nothing.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      LaunchController.active.value?.ready(waitsForEngine: false);
    });
    unawaited(_connect());
  }

  @override
  void dispose() {
    // A late answer has nowhere to land any more.
    _read++;
    _release();
    super.dispose();
  }

  /// Lets go of the client this page owns. The engine behind it keeps running.
  void _release() {
    _controller?.dispose();
    _controller = null;
    _api?.close();
    _api = null;
  }

  /// Asks the phone for the running service.
  Future<void> _connect() async {
    if (_reading) {
      // The phone is still answering: one more read after this one, not two.
      _again = true;
      return;
    }
    _reading = true;
    try {
      do {
        _again = false;
        await _readService();
      } while (_again && mounted);
    } finally {
      _reading = false;
    }
  }

  Future<void> _readService() async {
    final read = ++_read;
    if (mounted && _controller == null && _state != _BotEntryPhase.connecting) {
      setState(() => _state = _BotEntryPhase.connecting);
    }
    MiniMaxBotApi? api;
    Object? failure;
    try {
      api = await widget.endpoint.liveService();
    } catch (error) {
      failure = error;
    }
    // A late answer belongs to a read that has been superseded: it changes
    // nothing, and its client is closed right away.
    if (!mounted || read != _read) {
      api?.close();
      return;
    }
    if (failure != null) {
      // The phone could not be asked. A service this page already holds may
      // still be running, so only a page without one says that it failed.
      if (_controller != null) return;
      setState(() => _state = _BotEntryPhase.failed);
      return;
    }
    if (api == null) {
      _release();
      setState(() => _state = _BotEntryPhase.stopped);
      return;
    }
    final controller = _controller;
    if (controller == null) {
      _api = api;
      _controller = BotController(api);
      setState(() {});
      return;
    }
    // The phone published a service. The one this page holds is the same run
    // unless it stopped answering: a restarted run publishes a new endpoint.
    await controller.refresh();
    if (!mounted || read != _read) {
      api.close();
      return;
    }
    if (controller.error != null) {
      _release();
      _api = api;
      _controller = BotController(api);
    } else {
      // The same service: this page keeps the client it already has.
      api.close();
    }
    setState(() {});
  }

  /// The settings, and the phone's answer to them afterwards.
  Future<void> _settings() async {
    final api = _api;
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (context) => widget.settingsPage?.call(context) ?? BotSettingsHub(botApi: api),
      ),
    );
    if (!mounted) return;
    // The phone may have started a service while the settings were open.
    await _connect();
  }

  @override
  Widget build(BuildContext context) {
    final controller = _controller;
    if (controller != null) {
      // A new controller is a new page: the old one is gone with its service.
      return BotHome(key: ValueKey(controller), controller: controller);
    }
    return _entry(context);
  }

  /// The page shown while there is no service to talk to.
  Widget _entry(BuildContext context) {
    final l10n = context.l10n;
    return Scaffold(
      backgroundColor: Theme.of(context).colorScheme.surface,
      body: SafeArea(
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 8, 0),
              child: Row(
                children: [
                  Text(l10n.mobileBotTitle, style: Theme.of(context).textTheme.headlineSmall),
                  const Spacer(),
                  IconButton(
                    tooltip: l10n.mobileBotSettings,
                    onPressed: _settings,
                    icon: const Icon(Icons.settings_outlined),
                  ),
                ],
              ),
            ),
            Expanded(
              child: MbotLoadTransition(
                loading: _state == _BotEntryPhase.connecting,
                child: Center(child: _message(context)),
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// What the phone said, in words, with the ways out.
  Widget _message(BuildContext context) {
    final l10n = context.l10n;
    final failed = _state == _BotEntryPhase.failed;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 32),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            failed ? Icons.cloud_off_outlined : Icons.play_circle_outline,
            size: 44,
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
          const SizedBox(height: 14),
          Text(
            failed ? l10n.mobileBotEntryFailedTitle : l10n.mobileBotEntryStoppedTitle,
            style: Theme.of(context).textTheme.titleMedium,
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 8),
          Text(
            failed ? l10n.mobileBotEntryFailedHint : l10n.mobileBotEntryStoppedHint,
            style: Theme.of(context).textTheme.bodyMedium,
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 20),
          Wrap(
            spacing: 12,
            alignment: WrapAlignment.center,
            children: [
              FilledButton(onPressed: _connect, child: Text(l10n.mobileBotRetry)),
              OutlinedButton(onPressed: _settings, child: Text(l10n.mobileBotSettings)),
            ],
          ),
        ],
      ),
    );
  }
}
