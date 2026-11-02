import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../l10n/l10n.dart';

class BotSettingsPage extends StatefulWidget {
  const BotSettingsPage({super.key});
  @override
  State<BotSettingsPage> createState() => _BotSettingsPageState();
}

class _BotSettingsPageState extends State<BotSettingsPage> {
  static const _channel = MethodChannel('minimax/bot-settings');
  final _base = TextEditingController();
  final _model = TextEditingController();
  final _key = TextEditingController();
  bool _busy = true;
  bool _hasKey = false;
  bool _loaded = false;
  bool _failed = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final settings = await _channel.invokeMapMethod<String, dynamic>('load');
      if (!mounted) return;
      _base.text = settings?['baseUrl'] as String? ?? '';
      _model.text = settings?['model'] as String? ?? '';
      _hasKey = settings?['hasKey'] == true;
      _loaded = true;
      _failed = false;
    } catch (_) {
      _failed = true;
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _save({bool clearKey = false}) async {
    final uri = Uri.tryParse(_base.text.trim());
    if (uri == null ||
        uri.scheme != 'https' ||
        uri.host.isEmpty ||
        uri.userInfo.isNotEmpty ||
        uri.hasQuery ||
        uri.hasFragment ||
        _model.text.trim().isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(context.l10n.mobileBotSettingsInvalid)));
      return;
    }
    setState(() => _busy = true);
    try {
      final settings = await _channel.invokeMapMethod<String, dynamic>('save', {
        'baseUrl': _base.text.trim(),
        'model': _model.text.trim(),
        'clearKey': clearKey,
        if (!clearKey && _key.text.isNotEmpty) 'key': _key.text.trim(),
      });
      if (!mounted) return;
      _key.clear();
      _hasKey = settings?['hasKey'] == true;
      _failed = false;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(context.l10n.mobileBotSettingsSaved)));
    } catch (_) {
      if (mounted) _failed = true;
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  void dispose() {
    _base.dispose();
    _model.dispose();
    _key.clear();
    _key.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: Text(context.l10n.mobileBotSettings)),
    body: Align(
      alignment: Alignment.topCenter,
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 680),
        child: ListView(
          padding: const EdgeInsets.all(24),
          children: [
            if (_busy) const LinearProgressIndicator(),
            if (_failed) Text(context.l10n.mobileBotSettingsFailed),
            TextField(
              controller: _base,
              enabled: !_busy,
              keyboardType: TextInputType.url,
              autocorrect: false,
              decoration: const InputDecoration(labelText: 'Base URL'),
            ),
            const SizedBox(height: 16),
            TextField(
              controller: _model,
              enabled: !_busy,
              autocorrect: false,
              decoration: InputDecoration(labelText: context.l10n.mobileBotModel),
            ),
            const SizedBox(height: 16),
            TextField(
              controller: _key,
              enabled: !_busy,
              obscureText: true,
              autocorrect: false,
              enableSuggestions: false,
              decoration: InputDecoration(
                labelText: 'API Key',
                helperText: _hasKey ? context.l10n.mobileBotKeySaved : context.l10n.mobileBotKeyMissing,
              ),
            ),
            const SizedBox(height: 24),
            FilledButton(onPressed: _busy || !_loaded ? null : _save, child: Text(context.l10n.mobileBotSave)),
            if (_hasKey)
              TextButton(
                onPressed: _busy ? null : () => _save(clearKey: true),
                child: Text(context.l10n.mobileBotKeyDelete),
              ),
            if (!_loaded && !_busy) TextButton(onPressed: _load, child: Text(context.l10n.mobileBotRetry)),
          ],
        ),
      ),
    ),
  );
}
